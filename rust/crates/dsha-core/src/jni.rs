//! JNI 胶水：唯一允许 `unsafe` 的边界之一（ADR-0004 决定四 / 方案 §4.1、§4.7）。
//!
//! R0 用 §4.2 的固定符号名导出全部首期入口，并固定“每个入口 `catch_unwind`、
//! panic 映射错误码、绝不跨 FFI 展开”的形状。R1 只把 `digest` 接上真实实现：
//!
//! - 只调用 JNI 的 `GetDirectBufferAddress` / `GetDirectBufferCapacity` 与
//!   `ExceptionCheck`（不抛 Java 异常、不做反射、不取字段 id）；
//! - 每次 JNI 调用后立刻 `ExceptionCheck`；若 pending，先 `ExceptionClear` 恢复
//!   JVM 状态（评审 P2-1），再返回 `ERR_JNI_PENDING` 且不再调用业务 JNI；
//! - 摘要先算到本地定长数组，成功后才 `copy_nonoverlapping` 写入 direct
//!   `ByteBuffer`；任何错误都不写输出（无半成品）；
//! - 输出缓冲不足返回 `ERR_OUTPUT_BOUND`（调用方换更大缓冲重试）。
//!
//! R2 把 `verifyPlan` 接上真实实现（A3 §3.8）：`view`/`reasons` 都是 direct
//! `ByteBuffer`，原因只在返回 0/1 时写入（`u32 count` + `count` 个小端 u32）；
//! `OUTPUT_BOUND` 不写半成品，参数/缓冲契约错误返回 `INVALID_ARGUMENT`。
//!
//! `archivePackUpdate` 的输入与输出 direct 缓冲可能来自同一块 JVM 内存（Java 侧可用
//! `duplicate`/`slice` 共享）；构造 `&[u8]` / `&mut [u8]` 之前先按 `checked_add`
//! 计算实际地址区间并拒绝任何交叠（RUST-1），不把别名内存交给安全 Rust 引用。
//!
//! 本文件不接收路径字符串、不打开文件、不创建进程/线程、不安装信号处理器、
//! 不写 stdout/stderr。`digest` 的 fd 读取在 `crate::fd`（另一个 `unsafe` 边界）。
//!
//! JNI 函数表只按标准布局声明到用到的三个槽位（`ExceptionCheck` = 228、
//! `GetDirectBufferAddress` = 230、`GetDirectBufferCapacity` = 231，见 JNI
//! Specification 的 `JNINativeInterface`）。不引入 jni-rs（方案 §4.7 注）。

use std::ffi::c_void;
use std::panic::{catch_unwind, AssertUnwindSafe};

use crate::abi::{
    silence_panic_output, ERR_INPUT_BOUND, ERR_INVALID_ARGUMENT, ERR_JNI_PENDING, ERR_OK,
    ERR_OUTPUT_BOUND, ERR_PANIC,
};
use crate::gzip;

// 只使用 JNI 类型的最小投影，不引入 jni-rs（方案 §4.7 注）。
type JniEnv = *mut c_void;
type JClass = *mut c_void;
type JObject = *mut c_void;

/// `JNINativeInterface` 的最小投影：只声明到索引 231 的三个所需槽位。
///
/// JNI 规范的函数表布局是跨 Android ART / 桌面 JVM 稳定的 ABI；未使用的槽位
/// 用不透明指针占位。`size` / `offset_of!` 断言在编译期钉住槽位，写错即编译失败。
#[repr(C)]
struct JniNativeInterface {
    reserved: [*const c_void; 4],
    get_version: *const c_void,
    // 5..=16：DefineClass/FindClass/.../ExceptionDescribe（本 crate 不使用）。
    skip_to_exception_clear: [*const c_void; 12],
    exception_clear: Option<unsafe extern "system" fn(*mut c_void)>,
    // 18..=227：其余 JNI 函数（本 crate 不使用）。
    skip_to_exception_check: [*const c_void; 210],
    exception_check: Option<unsafe extern "system" fn(*mut c_void) -> u8>,
    new_direct_byte_buffer: *const c_void,
    get_direct_buffer_address:
        Option<unsafe extern "system" fn(*mut c_void, *mut c_void) -> *mut c_void>,
    get_direct_buffer_capacity: Option<unsafe extern "system" fn(*mut c_void, *mut c_void) -> i64>,
}

/// 函数表共 232 个指针槽位（0..=231）。
const _: [(); 232] =
    [(); core::mem::size_of::<JniNativeInterface>() / core::mem::size_of::<*const c_void>()];
const _: () = assert!(
    core::mem::offset_of!(JniNativeInterface, exception_clear)
        == 17 * core::mem::size_of::<*const c_void>()
);
const _: () = assert!(
    core::mem::offset_of!(JniNativeInterface, exception_check)
        == 228 * core::mem::size_of::<*const c_void>()
);
const _: () = assert!(
    core::mem::offset_of!(JniNativeInterface, get_direct_buffer_address)
        == 230 * core::mem::size_of::<*const c_void>()
);
const _: () = assert!(
    core::mem::offset_of!(JniNativeInterface, get_direct_buffer_capacity)
        == 231 * core::mem::size_of::<*const c_void>()
);

/// 所有入口的统一保护层：`catch_unwind` 捕获可展开 panic 并映射为 `ERR_PANIC`。
///
/// 只覆盖可展开 panic；abort / SIGSEGV / OOM 按 ADR-0004 失败域矩阵不可捕获，
/// 由有界输入与最小 `unsafe` 预防（不在本层伪造“已处理”）。
pub(crate) fn guard(body: impl FnOnce() -> i64) -> i64 {
    match catch_unwind(AssertUnwindSafe(body)) {
        Ok(value) => value,
        Err(_) => i64::from(ERR_PANIC),
    }
}

/// FFI 入口专用：先安装静默 panic hook，再进入 `guard`（G8：不写 stderr）。
fn ffi_guard(body: impl FnOnce() -> i64) -> i64 {
    silence_panic_output();
    guard(body)
}

/// 取 JNI 函数表；JVM 传入的 `JNIEnv*` 在本次调用期间有效，不跨调用保存。
fn jni_table(env: JniEnv) -> Option<&'static JniNativeInterface> {
    if env.is_null() {
        return None;
    }
    // SAFETY: env 是 JVM 在本次 native 调用中传入的 JNIEnv*（C 侧为
    // `const JNINativeInterface**`），调用期间有效且只读；函数表指针非空校验后
    // 才解引用，指针只在本函数返回的引用生命周期内使用。
    let raw = unsafe { *(env as *const *const JniNativeInterface) };
    if raw.is_null() {
        None
    } else {
        // SAFETY: raw 是 JVM 的 JNINativeInterface 表；引用不跨越 native 调用。
        Some(unsafe { &*raw })
    }
}

/// 每次 JNI 调用后立刻检查 pending 异常；若存在则 `ExceptionClear` 后再复核。
///
/// 本入口仍返回 `ERR_JNI_PENDING` 并且**不再调用业务 JNI**；清除只是把 JVM 恢复成
/// 无 pending 状态，避免“native 返回后 JVM 带着未处理异常继续跑”被后续 Java 代码
/// 误当成业务异常（评审 P2-1）。函数表缺槽位时按“pending”处理（fail-closed）。
fn exception_pending(table: &JniNativeInterface, env: JniEnv) -> bool {
    let pending = match table.exception_check {
        // SAFETY: ExceptionCheck 是 JNI 规范规定的只读查询，不抛 Java 异常。
        Some(check) => unsafe { check(env) != 0 },
        None => return true,
    };
    if !pending {
        return false;
    }
    if let Some(clear) = table.exception_clear {
        // SAFETY: ExceptionClear 是 JNI 规范规定的异常清理，无参数错误、不抛异常。
        unsafe { clear(env) };
        if let Some(check) = table.exception_check {
            // SAFETY: 清除后复核，确认 JVM 已无 pending 异常（结果不改变本次返回码）。
            let _ = unsafe { check(env) };
        }
    }
    true
}

/// 取 direct `ByteBuffer` 的地址与容量；非 direct / null / 调用抛异常均失败。
fn direct_buffer(
    table: &JniNativeInterface,
    env: JniEnv,
    buffer: JObject,
) -> Result<(*mut u8, i64), i32> {
    if buffer.is_null() {
        return Err(ERR_INVALID_ARGUMENT);
    }
    let address = match table.get_direct_buffer_address {
        // SAFETY: buffer 是本次调用传入的 jobject；函数只读查询，不保存引用。
        Some(call) => unsafe { call(env, buffer) },
        None => return Err(ERR_INVALID_ARGUMENT),
    };
    if exception_pending(table, env) {
        return Err(ERR_JNI_PENDING);
    }
    let capacity = match table.get_direct_buffer_capacity {
        // SAFETY: 同上；容量查询不改变缓冲区。
        Some(call) => unsafe { call(env, buffer) },
        None => return Err(ERR_INVALID_ARGUMENT),
    };
    if exception_pending(table, env) {
        return Err(ERR_JNI_PENDING);
    }
    if address.is_null() || capacity < 0 {
        // 非 direct ByteBuffer（或只读实现不暴露地址）：属于调用方契约错误。
        return Err(ERR_INVALID_ARGUMENT);
    }
    Ok((address as *mut u8, capacity))
}

/// 计算 direct 缓冲区的半开地址区间 `[start, end)`。
///
/// 地址与长度都来自 JVM 声明；`checked_add` 溢出时返回 `None`，由调用方按
/// `INVALID_ARGUMENT` 拒绝，绝不把回绕后的区间当成“不重叠”。
fn checked_range(address: *const u8, length: usize) -> Option<(usize, usize)> {
    let start = address as usize;
    let end = start.checked_add(length)?;
    Some((start, end))
}

/// 两个半开地址区间是否交叠；空区间（长度 0）与任何区间都不交叠。
fn ranges_overlap(first: (usize, usize), second: (usize, usize)) -> bool {
    first.0 < first.1 && second.0 < second.1 && first.0 < second.1 && second.0 < first.1
}

/// 在构造输入/输出切片前证明两个 direct 映射区间可安全共存。
///
/// 任一区间 `checked_add` 溢出或非空区间交叠都返回 `INVALID_ARGUMENT`；调用方必须
/// 在返回错误时不写任何字节、不推进会话。输出长度为 0 时不构造输出切片，无需比较。
fn ensure_disjoint_buffers(
    input_address: *mut u8,
    input_length: usize,
    output_address: *mut u8,
    output_length: usize,
) -> Result<(), i32> {
    let input_range = checked_range(input_address, input_length).ok_or(ERR_INVALID_ARGUMENT)?;
    if output_length == 0 {
        return Ok(());
    }
    let output_range = checked_range(output_address, output_length).ok_or(ERR_INVALID_ARGUMENT)?;
    if ranges_overlap(input_range, output_range) {
        return Err(ERR_INVALID_ARGUMENT);
    }
    Ok(())
}

/// `digest` 的真实实现：先做纯参数校验（不碰 JNI），再取 direct 缓冲、算摘要、
/// 一次写入。任何错误都不写 `out`。
fn digest_inner(
    env: JniEnv,
    fd: i32,
    offset: i64,
    length: i64,
    algorithm: i32,
    out: JObject,
) -> Result<i64, i32> {
    let (_, _, algorithm_value) = crate::digest::validate(fd, offset, length, algorithm)?;
    let table = jni_table(env).ok_or(ERR_INVALID_ARGUMENT)?;
    let (address, capacity) = direct_buffer(table, env, out)?;
    let needed = algorithm_value.digest_bytes() as i64;
    if capacity < needed {
        return Err(ERR_OUTPUT_BOUND);
    }
    let result = crate::digest::digest_fd(fd, offset, length, algorithm)?;
    // SAFETY: 上面的容量检查保证 capacity >= digest 长度；Kotlin 输入契约要求
    // out 是可写 direct ByteBuffer，并在调用期间保持强引用、不做并发修改。
    // 摘要先完整算在本地数组，失败路径不会走到这里（不写半成品）。
    unsafe {
        core::ptr::copy_nonoverlapping(result.digest().as_ptr(), address, result.digest().len());
    }
    Ok(result.bytes_read() as i64)
}

#[no_mangle]
pub extern "system" fn Java_com_deepseekharness_app_util_RustNative_abiVersion(
    _env: JniEnv,
    _class: JClass,
) -> i32 {
    ffi_guard(|| i64::from(crate::abi::ABI_VERSION)) as i32
}

#[no_mangle]
pub extern "system" fn Java_com_deepseekharness_app_util_RustNative_digest(
    env: JniEnv,
    _class: JClass,
    fd: i32,
    offset: i64,
    length: i64,
    algorithm: i32,
    out: JObject,
) -> i64 {
    ffi_guard(
        || match digest_inner(env, fd, offset, length, algorithm, out) {
            Ok(bytes_read) => bytes_read,
            Err(code) => i64::from(code),
        },
    )
}

/// `archive_pack_update` 的真实实现：direct 缓冲契约 + 有界 gzip 编码会话。
fn archive_pack_update_inner(
    env: JniEnv,
    session: i64,
    input: JObject,
    out: JObject,
) -> Result<i64, i32> {
    let table = jni_table(env).ok_or(ERR_INVALID_ARGUMENT)?;
    let (input_address, input_capacity) = direct_buffer(table, env, input)?;
    let (output_address, output_capacity) = direct_buffer(table, env, out)?;
    if input_capacity < 1 {
        return Err(ERR_INVALID_ARGUMENT);
    }
    if input_capacity > gzip::MAX_INPUT_CHUNK as i64 {
        return Err(ERR_INPUT_BOUND);
    }
    let input_length = input_capacity as usize;
    // 只按上界映射输出区域：超过 pack_bound(MAX_INPUT_CHUNK) 的容量不参与切片，
    // 避免用 JVM 声明的巨大容量构造超长切片。
    let output_length = output_capacity.min(gzip::MAX_PACK_OUTPUT as i64) as usize;
    // RUST-1：Java 的 duplicate/slice 能让输入与输出共享同一块 direct 内存；两个
    // 参数各自容量合法也不代表区间不交叠。构造切片前按实际映射区间核验，任一
    // `checked_add` 溢出同样拒绝；否则 `&[u8]` 与 `&mut [u8]` 会指向别名内存。
    // 拒绝发生在任何写入/会话推进之前。
    ensure_disjoint_buffers(input_address, input_length, output_address, output_length)?;
    // SAFETY: direct_buffer 已核验地址非空；输入容量 = input_length（≤256 KiB），
    // 输出容量 ≤ MAX_PACK_OUTPUT；上面的 check 已证明两个映射区间不交叠且未溢出；
    // JVM 在本次 native 调用期间保持 ByteBuffer 强引用、不做并发修改；切片只在本
    // 调用内使用，不跨调用保存。
    let input_bytes: &[u8] = unsafe { core::slice::from_raw_parts(input_address, input_length) };
    let output_bytes: &mut [u8] = if output_length == 0 {
        &mut []
    } else {
        // SAFETY: 同上；地址与容量来自 direct_buffer。
        unsafe { core::slice::from_raw_parts_mut(output_address, output_length) }
    };
    gzip::pack_update(session as u64, input_bytes, output_bytes)
}

/// `archive_pack_finish` 的真实实现：结束后会话从表中移除。
fn archive_pack_finish_inner(env: JniEnv, session: i64, out: JObject) -> Result<i64, i32> {
    let table = jni_table(env).ok_or(ERR_INVALID_ARGUMENT)?;
    let (output_address, output_capacity) = direct_buffer(table, env, out)?;
    if output_capacity < gzip::FINISH_BOUND as i64 {
        return Err(ERR_OUTPUT_BOUND);
    }
    let output_length = gzip::FINISH_BOUND;
    // SAFETY: direct_buffer 已核验地址非空；只映射固定 FINISH_BOUND 字节（JVM 容量
    // 已核验 ≥ 该值），调用期间保持强引用、不做并发修改。
    let output_bytes: &mut [u8] =
        unsafe { core::slice::from_raw_parts_mut(output_address, output_length) };
    gzip::pack_finish(session as u64, output_bytes)
}

/// `archive_unpack_write` 的真实实现：有界压缩输入喂入会话。
fn archive_unpack_write_inner(env: JniEnv, session: i64, input: JObject) -> Result<i64, i32> {
    let table = jni_table(env).ok_or(ERR_INVALID_ARGUMENT)?;
    let (input_address, input_capacity) = direct_buffer(table, env, input)?;
    if input_capacity < 1 {
        return Err(ERR_INVALID_ARGUMENT);
    }
    if input_capacity > gzip::MAX_INPUT_CHUNK as i64 {
        return Err(ERR_INPUT_BOUND);
    }
    let input_length = input_capacity as usize;
    // SAFETY: direct_buffer 已核验地址非空；容量 = input_length（≤256 KiB）；调用期间
    // JVM 保持强引用、不做并发修改；切片只在本调用内使用。
    let input_bytes: &[u8] = unsafe { core::slice::from_raw_parts(input_address, input_length) };
    gzip::unpack_write(session as u64, input_bytes)
}

/// `archive_unpack_read` 的真实实现：返回 `(produced << 32) | flags`。
fn archive_unpack_read_inner(env: JniEnv, session: i64, out: JObject) -> Result<i64, i32> {
    let table = jni_table(env).ok_or(ERR_INVALID_ARGUMENT)?;
    let (output_address, output_capacity) = direct_buffer(table, env, out)?;
    if output_capacity < 1 || output_capacity > gzip::MAX_OUTPUT_CHUNK as i64 {
        return Err(ERR_INVALID_ARGUMENT);
    }
    let output_length = output_capacity as usize;
    // SAFETY: direct_buffer 已核验地址非空；容量已核验在 1..=256 KiB；调用期间 JVM
    // 保持强引用、不做并发修改；切片只在本调用内使用。
    let output_bytes: &mut [u8] =
        unsafe { core::slice::from_raw_parts_mut(output_address, output_length) };
    gzip::unpack_read(session as u64, output_bytes)
}

#[no_mangle]
pub extern "system" fn Java_com_deepseekharness_app_util_RustNative_archivePackCreate(
    _env: JniEnv,
    _class: JClass,
    level: i32,
) -> i64 {
    ffi_guard(|| match gzip::pack_create(level) {
        Ok(handle) => handle as i64,
        Err(code) => i64::from(code),
    })
}

#[no_mangle]
pub extern "system" fn Java_com_deepseekharness_app_util_RustNative_archivePackUpdate(
    env: JniEnv,
    _class: JClass,
    session: i64,
    input: JObject,
    out: JObject,
) -> i64 {
    ffi_guard(
        || match archive_pack_update_inner(env, session, input, out) {
            Ok(value) => value,
            Err(code) => i64::from(code),
        },
    )
}

#[no_mangle]
pub extern "system" fn Java_com_deepseekharness_app_util_RustNative_archivePackFinish(
    env: JniEnv,
    _class: JClass,
    session: i64,
    out: JObject,
) -> i64 {
    ffi_guard(|| match archive_pack_finish_inner(env, session, out) {
        Ok(value) => value,
        Err(code) => i64::from(code),
    })
}

#[no_mangle]
pub extern "system" fn Java_com_deepseekharness_app_util_RustNative_archivePackAbort(
    _env: JniEnv,
    _class: JClass,
    session: i64,
) -> i32 {
    ffi_guard(|| match gzip::pack_abort(session as u64) {
        Ok(value) => i64::from(value),
        Err(code) => i64::from(code),
    }) as i32
}

#[no_mangle]
pub extern "system" fn Java_com_deepseekharness_app_util_RustNative_archiveUnpackCreate(
    _env: JniEnv,
    _class: JClass,
) -> i64 {
    ffi_guard(|| match gzip::unpack_create() {
        Ok(handle) => handle as i64,
        Err(code) => i64::from(code),
    })
}

#[no_mangle]
pub extern "system" fn Java_com_deepseekharness_app_util_RustNative_archiveUnpackWrite(
    env: JniEnv,
    _class: JClass,
    session: i64,
    input: JObject,
) -> i64 {
    ffi_guard(|| match archive_unpack_write_inner(env, session, input) {
        Ok(value) => value,
        Err(code) => i64::from(code),
    })
}

#[no_mangle]
pub extern "system" fn Java_com_deepseekharness_app_util_RustNative_archiveUnpackRead(
    env: JniEnv,
    _class: JClass,
    session: i64,
    out: JObject,
) -> i64 {
    ffi_guard(|| match archive_unpack_read_inner(env, session, out) {
        Ok(value) => value,
        Err(code) => i64::from(code),
    })
}

#[no_mangle]
pub extern "system" fn Java_com_deepseekharness_app_util_RustNative_archiveUnpackAbort(
    _env: JniEnv,
    _class: JClass,
    session: i64,
) -> i32 {
    ffi_guard(|| match gzip::unpack_abort(session as u64) {
        Ok(value) => i64::from(value),
        Err(code) => i64::from(code),
    }) as i32
}

/// `verify_plan` 的真实实现（A3 §3.8）：结构/范围/策略版本/有效期只读校验。
///
/// 返回 `0` = VALID、`1` = INVALID（原因已写入 `reasons`）、负数 = 错误码。
/// 参数/缓冲契约错误返回 `INVALID_ARGUMENT`；原因条数超过 `maxReasons` 返回
/// `OUTPUT_BOUND` 且不写任何字节（调用方放大缓冲重试，不产生半成品）。
fn verify_plan_inner(
    env: JniEnv,
    view: JObject,
    view_length: i32,
    reasons: JObject,
    max_reasons: i32,
) -> Result<i32, i32> {
    // 标量参数先于 JNI 调用校验：null env 时也能安全返回冻结错误码。
    if view.is_null() || reasons.is_null() || view_length < 0 || max_reasons < 0 {
        return Err(ERR_INVALID_ARGUMENT);
    }
    let table = jni_table(env).ok_or(ERR_INVALID_ARGUMENT)?;
    let (view_address, view_capacity) = direct_buffer(table, env, view)?;
    if view_capacity < i64::from(view_length) {
        return Err(ERR_INVALID_ARGUMENT);
    }
    let (reasons_address, reasons_capacity) = direct_buffer(table, env, reasons)?;
    // 调用约定：reasons 容量 >= 4 + 4*maxReasons（§3.8）；不足属调用方契约错误。
    let required_capacity = 4i64
        .checked_add(4i64.saturating_mul(i64::from(max_reasons)))
        .ok_or(ERR_INVALID_ARGUMENT)?;
    if reasons_capacity < required_capacity {
        return Err(ERR_INVALID_ARGUMENT);
    }
    // public JNI 可接收 same/duplicate/slice 共享内存；在任何切片、视图消费或原因写入前，
    // 核验 view 与整个声明的 reasons 写区间（唯一输出）不交叠且地址 end 不溢出。
    // 零长 view 不占区间，仍保留原有 PLAN_STRUCTURE / OUTPUT_BOUND 语义。
    let reasons_length = usize::try_from(required_capacity).map_err(|_| ERR_INVALID_ARGUMENT)?;
    ensure_disjoint_buffers(
        view_address,
        view_length as usize,
        reasons_address,
        reasons_length,
    )?;
    let view_bytes: &[u8] = if view_length == 0 {
        // 0 长度视图不构造空切片指针（避免对 null 指针做 from_raw_parts）。
        &[]
    } else {
        // SAFETY: direct_buffer 已核验地址非空且容量 >= view_length；上面的区间校验
        // 已排除与 reasons 写区间交叠及地址溢出。JVM 在本次 native 调用期间保持
        // ByteBuffer 强引用、不做并发修改；视图上限仍由 plan::validate 的 2 MiB 规则处理。
        unsafe { core::slice::from_raw_parts(view_address, view_length as usize) }
    };
    // JNI 入口没有 nowMs 参数（ABI 冻结）：使用视图内嵌时间，保持纯函数语义。
    let now_ms = crate::plan::embedded_now_ms(view_bytes).unwrap_or(0);
    match crate::plan::validate(view_bytes, now_ms) {
        Ok(()) => {
            // SAFETY: reasons 容量 >= 4（max_reasons >= 0），写入 u32 count=0。
            unsafe { write_u32_le(reasons_address, 0) };
            Ok(0)
        }
        Err(found) => {
            let count = found.len();
            if count > max_reasons as usize {
                // 不产生半成品：此时一个字节都不写。
                return Err(ERR_OUTPUT_BOUND);
            }
            // SAFETY: 容量已校验为 >= 4 + 4*count；只在本调用内写入，不跨调用保存指针。
            unsafe {
                write_u32_le(reasons_address, count as u32);
                for (index, code) in found.codes().iter().enumerate() {
                    let offset = 4 + index * 4;
                    write_u32_le(reasons_address.add(offset), *code);
                }
            }
            Ok(1)
        }
    }
}

/// 把 `value` 以小端写入 direct buffer 的指定地址。
///
/// # Safety
/// `address` 必须指向至少 4 字节的可写 direct buffer 区域，且该缓冲在本次 native
/// 调用期间由 JVM 保持强引用、不做并发修改；调用方必须先核验容量。
unsafe fn write_u32_le(address: *mut u8, value: u32) {
    let bytes = value.to_le_bytes();
    // SAFETY: 由调用方按上面的契约保证目标区域可写且足够长。
    unsafe {
        core::ptr::copy_nonoverlapping(bytes.as_ptr(), address, bytes.len());
    }
}

#[no_mangle]
pub extern "system" fn Java_com_deepseekharness_app_util_RustNative_verifyPlan(
    env: JniEnv,
    _class: JClass,
    view: JObject,
    view_length: i32,
    reasons: JObject,
    max_reasons: i32,
) -> i32 {
    ffi_guard(
        || match verify_plan_inner(env, view, view_length, reasons, max_reasons) {
            Ok(value) => i64::from(value),
            Err(code) => i64::from(code),
        },
    ) as i32
}

// `ERR_OK` 目前只用于文档/冻结；显式引用避免 clippy 把它当成未使用模块项。
const _: i32 = ERR_OK;

#[cfg(test)]
mod tests {
    use super::*;

    /// 用整数伪造 direct 缓冲地址；测试只计算区间，不解引用任何指针。
    fn range(start: usize, length: usize) -> (usize, usize) {
        match checked_range(start as *const u8, length) {
            Some(range) => range,
            None => panic!("地址区间溢出: start={start} length={length}"),
        }
    }

    #[test]
    fn same_buffer_duplicate_and_partial_slices_overlap() {
        // 同一个 direct buffer 作为输入与输出（Java 传同一个对象）。
        let whole = range(0x1000, 300 * 1024);
        assert!(ranges_overlap(whole, whole));
        // duplicate：同一地址、相同区间。
        assert!(ranges_overlap(range(0x1000, 300 * 1024), range(0x1000, 300 * 1024)));
        // 同一块内存的两个部分重叠 slice。
        assert!(ranges_overlap(range(0x1000, 2 * 1024), range(0x1000 + 512, 4 * 1024)));
        // 输入完全包含输出、输出完全包含输入。
        assert!(ranges_overlap(range(0x1000, 4 * 1024), range(0x1000 + 64, 128)));
        assert!(ranges_overlap(range(0x1000 + 64, 128), range(0x1000, 4 * 1024)));
    }

    #[test]
    fn adjacent_and_disjoint_ranges_do_not_overlap() {
        // 相邻（end == start）不算交叠。
        assert!(!ranges_overlap(range(0x1000, 0x100), range(0x1100, 0x100)));
        assert!(!ranges_overlap(range(0x1100, 0x100), range(0x1000, 0x100)));
        // 完全分离的普通独立缓冲。
        assert!(!ranges_overlap(range(0x1000, 0x100), range(0x4000, 0x100)));
        assert!(!ranges_overlap(range(0x4000, 0x100), range(0x1000, 0x100)));
    }

    #[test]
    fn zero_length_ranges_never_overlap() {
        assert!(!ranges_overlap(range(0x1000, 0), range(0x1000, 0x100)));
        assert!(!ranges_overlap(range(0x1000, 0x100), range(0x1000, 0)));
        // archivePackUpdate 对 0 字节输出不构造切片，也不做交叠判断。
        assert!(!ranges_overlap(range(0x1000, 0x100), range(0x1000, 0)));
    }

    fn fake_address(value: usize) -> *mut u8 {
        value as *mut u8
    }

    #[test]
    fn pack_buffer_overlap_and_overflow_are_rejected_before_slicing() {
        // same object：输入输出是同一个 direct buffer。
        assert_eq!(
            ensure_disjoint_buffers(fake_address(0x1000), 0x400, fake_address(0x1000), 0x400),
            Err(ERR_INVALID_ARGUMENT)
        );
        // duplicate/slice：地址与容量相同或部分重叠。
        assert_eq!(
            ensure_disjoint_buffers(fake_address(0x1000), 0x100, fake_address(0x1080), 0x100),
            Err(ERR_INVALID_ARGUMENT)
        );
        assert_eq!(
            ensure_disjoint_buffers(fake_address(0x1080), 0x100, fake_address(0x1000), 0x100),
            Err(ERR_INVALID_ARGUMENT)
        );
        // 相邻（end == start）不交叠。
        assert_eq!(
            ensure_disjoint_buffers(fake_address(0x1000), 0x100, fake_address(0x1100), 0x100),
            Ok(())
        );
        // 普通独立缓冲。
        assert_eq!(
            ensure_disjoint_buffers(fake_address(0x1000), 0x100, fake_address(0x4000), 0x100),
            Ok(())
        );
        // 0 字节输出不构造切片，允许与输入同址。
        assert_eq!(
            ensure_disjoint_buffers(fake_address(0x1000), 0x100, fake_address(0x1000), 0),
            Ok(())
        );
        // checked_add 溢出：即使两段“看起来”不重叠也拒绝。
        assert_eq!(
            ensure_disjoint_buffers(fake_address(usize::MAX - 1), 4, fake_address(0x4000), 0x100),
            Err(ERR_INVALID_ARGUMENT)
        );
        assert_eq!(
            ensure_disjoint_buffers(fake_address(0x1000), 0x100, fake_address(usize::MAX - 1), 4),
            Err(ERR_INVALID_ARGUMENT)
        );
    }

    #[test]
    fn verify_plan_buffer_alias_pairs_are_rejected_before_slicing() {
        // view 是唯一输入，reasons 是唯一输出；same 与 duplicate 的地址区间相同。
        for _alias_kind in ["same", "duplicate"] {
            assert_eq!(
                ensure_disjoint_buffers(fake_address(0x1000), 0x100, fake_address(0x1000), 36),
                Err(ERR_INVALID_ARGUMENT)
            );
        }
        // 部分重叠 slice：reasons 从 view 内部开始，或 reasons 的尾部覆盖 view 开头。
        for (view_start, reasons_start) in [(0x1000, 0x10f0), (0x1010, 0x1000)] {
            assert_eq!(
                ensure_disjoint_buffers(
                    fake_address(view_start),
                    0x100,
                    fake_address(reasons_start),
                    36,
                ),
                Err(ERR_INVALID_ARGUMENT)
            );
        }
        // 相邻两种方向合法；maxReasons=0 的声明写区间仍含 4 字节 count。
        for (view_start, reasons_start) in [(0x1000, 0x1100), (0x1004, 0x1000)] {
            assert_eq!(
                ensure_disjoint_buffers(
                    fake_address(view_start),
                    0x100,
                    fake_address(reasons_start),
                    4,
                ),
                Ok(())
            );
        }
        // 空 view 可在 reasons 的开头、内部或末尾；不改变原有结构错误处理。
        for view_start in [0x1000, 0x1002, 0x1004] {
            assert_eq!(
                ensure_disjoint_buffers(fake_address(view_start), 0, fake_address(0x1000), 4),
                Ok(())
            );
            assert!(!ranges_overlap(range(0x1000, 4), range(view_start, 0)));
            assert!(!ranges_overlap(range(view_start, 0), range(0x1000, 4)));
        }
        // 任一 end 回绕都拒绝；即使 view 为空也必须核验 reasons end。
        assert_eq!(
            ensure_disjoint_buffers(fake_address(usize::MAX - 1), 4, fake_address(0x1000), 4),
            Err(ERR_INVALID_ARGUMENT)
        );
        for view_length in [0, 0x100] {
            assert_eq!(
                ensure_disjoint_buffers(
                    fake_address(0x1000),
                    view_length,
                    fake_address(usize::MAX - 1),
                    4,
                ),
                Err(ERR_INVALID_ARGUMENT)
            );
        }
    }

    #[test]
    fn checked_range_rejects_pointer_plus_length_overflow() {
        // 构造切片前必须先证明 address + length 不回绕；回绕区间不能当作不重叠。
        assert_eq!(checked_range((usize::MAX - 1) as *const u8, 4), None);
        assert_eq!(checked_range(usize::MAX as *const u8, 1), None);
        assert_eq!(
            checked_range((usize::MAX - 3) as *const u8, 3),
            Some((usize::MAX - 3, usize::MAX))
        );
    }
}

