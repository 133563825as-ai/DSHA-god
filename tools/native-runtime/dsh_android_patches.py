"""DSH 0.2.2-rc.1 的 Android 补丁表（2026-11-07 随固定输入 20261105e 重钉 4 个 original_sha256，锚点未变）。路径相对 node_modules 根（含 @deepseek-ai/dsh 的那一层）。

每一项都要写清：为什么改、改了什么、上游原始字节的 SHA-256。
"""

D = '@deepseek-ai/dsh/node_modules/'

REQUIRE_BUILTIN_JS = '''"use strict";
// [dsha-android] 原实现经 node-addon-native-custom-loader 加载预编译原生扩展来读取 Node 内部模块，
// 只有 linux/darwin/win32 版本，Android 上 dsh-app-boot 启动即失败。
// Android 原生运行环境启动 node 时在命令行带 --expose-internals（该参数不能放进 NODE_OPTIONS），
// 此时 createRequire 可以直接解析 "internal/..." 模块，接口保持一致。
const { createRequire } = require("node:module");
const req = createRequire(__filename);
function isAllowedInternalId(moduleId) {
    return typeof moduleId === "string" && moduleId.startsWith("internal/");
}
function requireBuiltin(moduleId) {
    if (!isAllowedInternalId(moduleId)) throw new Error("DSHA_ANDROID_REQUIRE_BUILTIN_DENIED: " + moduleId);
    if (!process.execArgv.includes("--expose-internals"))
        throw new Error("DSHA_ANDROID_REQUIRE_BUILTIN_NEEDS_EXPOSE_INTERNALS");
    return req(moduleId);
}
function getBindingInfo() {
    return { backend: "dsha-android-js", via: "--expose-internals" };
}
exports.requireBuiltin = requireBuiltin;
exports.isAllowedInternalId = isAllowedInternalId;
exports.getBindingInfo = getBindingInfo;
exports.default = { requireBuiltin, isAllowedInternalId, getBindingInfo };
'''

FLOCK_ANDROID_BRANCH = '''    const { platform, arch } = process;
    if (platform === 'android') {
        // [dsha-android] 依赖树里只有 linux/darwin 的 flock 原生扩展（glibc/musl）。Android 原生运行环境
        // 目前没有 bionic 版：退化为进程内独占锁（同一进程内按 dev+ino 互斥，fd 关闭后释放）。
        // 这只保护同一个 DSH 进程内的并发；跨进程（例如另起一个 dsh CLI 写同一会话）不受保护。
        // S3 换成 bionic 版原生扩展后删除本分支。
        return androidInProcessFlock();
    }
    if (platform !== 'linux' && platform !== 'darwin') {'''

FLOCK_ANDROID_HELPER = '''let binding;
// [dsha-android] 进程内独占锁表：key = dev:ino，value = 持有它的 fd。
const androidHolders = new Map();
function androidInProcessFlock() {
    const fs = createRequire(import.meta.url)('node:fs');
    const held = (fd, key) => {
        try {
            const st = fs.fstatSync(fd, { bigint: true });
            return `${st.dev}:${st.ino}` === key;
        } catch {
            return false;
        }
    };
    return {
        tryLock(fd, done) {
            let errno = 0;
            try {
                const st = fs.fstatSync(fd, { bigint: true });
                const key = `${st.dev}:${st.ino}`;
                const owner = androidHolders.get(key);
                if (owner !== undefined && owner !== fd && held(owner, key)) errno = 11; // EAGAIN
                else androidHolders.set(key, fd);
            } catch (error) {
                // 与原生绑定一致：回调收到正的 errno，tryLockExclusive 用 getSystemErrorName(-errno) 还原名称。
                errno = typeof error?.errno === 'number' ? Math.abs(error.errno) : 9; // EBADF
            }
            setImmediate(() => done(errno));
        },
    };
}'''

RUNTIME_FS_PLATFORM_OLD = "    if (process.platform !== 'linux') return link(source, target);\n"
RUNTIME_FS_PLATFORM_NEW = (
    "    // [dsha-android] Android 普通应用不能建硬链接（SELinux 拒绝 link），也走复制 + 排他发布。\n"
    "    if (process.platform !== 'linux' && process.platform !== 'android') return link(source, target);\n")

RUNTIME_FS_KERNEL_OLD = "async function kernelPublish(source, target) {\n  if (!native) {\n"
RUNTIME_FS_KERNEL_NEW = (
    "async function kernelPublish(source, target) {\n"
    "  if (process.platform === 'android') {\n"
    "    // [dsha-android] 没有 koffi 的 bionic 版，拿不到 renameat2：\n"
    "    // 会话与附件按原有降级走 lockedSessionPublish；普通文件用 COPYFILE_EXCL 保证绝不覆盖。\n"
    "    throw Object.assign(new Error('Android 原生运行环境没有 renameat2'), { code: 'ENOSYS' });\n"
    "  }\n"
    "  if (!native) {\n")

RUNTIME_FS_FALLBACK_OLD = (
    "        if (!privateSession || !['ENOSYS', 'EINVAL', 'EOPNOTSUPP'].includes(error.code)) throw error;\n"
    "        await lockedSessionPublish(temporary, target);\n")
RUNTIME_FS_FALLBACK_NEW = (
    "        if (process.platform === 'android' && !privateSession && error.code === 'ENOSYS') {\n"
    "          // [dsha-android] 普通文件：排他复制，目标已存在则 EEXIST，绝不覆盖用户文件。\n"
    "          await copyFile(temporary, target, constants.COPYFILE_EXCL);\n"
    "        } else {\n"
    "        if (!privateSession || !['ENOSYS', 'EINVAL', 'EOPNOTSUPP'].includes(error.code)) throw error;\n"
    "        await lockedSessionPublish(temporary, target);\n"
    "        }\n")

WORKER_LINK_OLD = "\tlink: node_fs_promises.link,\n"
WORKER_LINK_NEW = (
    "\t// [dsha-android] 迁移校验 worker 自带一份 fs 表；Android 上 link 会被 SELinux 拒绝，改走主线程同一发布器。\n"
    "\tlink: process.platform === \"android\" ? (source, target) => import(\"dsha-runtime-fs\").then((m) => m.publishSessionExclusive(source, target)) : node_fs_promises.link,\n")

RIPGREP_OLD = '''let resolved;
try {
    resolved = require.resolve(`${platformPkg}/bin/${binaryName}`);
} catch {'''
RIPGREP_NEW = '''let resolved;
try {
    // [dsha-android] 依赖树里只有 linux/darwin/win32 的 rg。Android 原生运行环境把 Termux 的 bionic rg
    // 随 APK 放进 nativeLibraryDir（filesDir 里的程序不能执行），由宿主通过 DSHA_RIPGREP_PATH 告知位置。
    if (process.platform === 'android') {
        const fromHost = process.env.DSHA_RIPGREP_PATH;
        if (typeof fromHost !== 'string' || !fromHost.startsWith('/')) throw new Error('DSHA_RIPGREP_PATH 未设置');
        resolved = fromHost;
    } else
    resolved = require.resolve(`${platformPkg}/bin/${binaryName}`);
} catch {'''

BASH_LOCAL_OLD = """	async execute(spec) {
		return this.executeArgv(spec, [
			"bash",
"""
BASH_LOCAL_NEW = """	async execute(spec) {
		return this.executeArgv(spec, [
			// [dsha-android] Android 没有 bash（只有 /system/bin/sh，即 mksh）；我们也不打包 GPLv3 的 bash。
			process.platform === "android" ? "/system/bin/sh" : "bash",
"""

BASH_SANDBOX_OLD = """		return this.ctx.sandbox.confine([
			"bash",
"""
BASH_SANDBOX_NEW = """		return this.ctx.sandbox.confine([
			// [dsha-android] 同 dsh-bash-local：Android 用系统自带 sh。
			process.platform === "android" ? "/system/bin/sh" : "bash",
"""

PATCHES = [
    {
        'id': 'bash-local-system-sh',
        'path': D + '@deepseek-ai/dsh-bash-local/lib/index.js',
        'original_sha256': '6d9b4426b8455198b79de398f57c0f5693e7292411059b66d5ac5eba608b59cb',
        'replace_exact': [(BASH_LOCAL_OLD, BASH_LOCAL_NEW)],
        'marker': '[dsha-android]',
    },
    {
        'id': 'bash-sandbox-system-sh',
        'path': D + '@deepseek-ai/dsh-bash-sandbox/lib/index.js',
        'original_sha256': '0f788f99113ba7411eb33af71cdafabd07b73cf012c1715c819e03f3f77f342d',
        'replace_exact': [(BASH_SANDBOX_OLD, BASH_SANDBOX_NEW)],
        'marker': '[dsha-android]',
    },
    {
        'id': 'ripgrep-android-path',
        'path': D + '@vscode/ripgrep/lib/index.js',
        'original_sha256': '107cf2aa0eaf6e9fb1a23cfe74cdbd322b1628af1812d05d9d46b650c4cc4c5d',
        'replace_exact': [(RIPGREP_OLD, RIPGREP_NEW)],
        'marker': '[dsha-android]',
    },
    {
        'id': 'flock-android-in-process',
        'path': D + '@deepseek-ai/node-addon-system/lib/flock.js',
        'original_sha256': 'e42af00c298d56322c073bf7cc66485096028318ab645a2e69dc2aa7481ba646',
        'replace_exact': [
            ("let binding;\n", FLOCK_ANDROID_HELPER + "\n"),
            ("    const { platform, arch } = process;\n    if (platform !== 'linux' && platform !== 'darwin') {",
             FLOCK_ANDROID_BRANCH),
        ],
        'marker': '[dsha-android]',
    },
    {
        'id': 'runtime-fs-android',
        'path': D + 'dsha-runtime-fs/index.js',
        'original_sha256': '224216a82f4ff20778433696aaa2996aac89b353e2d30602249dbc378ab87dd5',
        'replace_exact': [
            (RUNTIME_FS_PLATFORM_OLD, RUNTIME_FS_PLATFORM_NEW),
            (RUNTIME_FS_KERNEL_OLD, RUNTIME_FS_KERNEL_NEW),
            (RUNTIME_FS_FALLBACK_OLD, RUNTIME_FS_FALLBACK_NEW),
        ],
        'marker': '[dsha-android]',
    },
    {
        'id': 'session-worker-link',
        'path': D + '@deepseek-ai/dsh-session-persistence-jsonl/lib/worker.cjs',
        'original_sha256': 'c5c8ccb7a661ef343e9d0c792d8d5cba376ed783ea48c6489fe90eab264ae321',
        'replace_exact': [(WORKER_LINK_OLD, WORKER_LINK_NEW)],
        'marker': '[dsha-android]',
    },
    {
        'id': 'require-builtin-js',
        'path': D + 'node-addon-require-builtin/lib/index.js',
        'original_sha256': '73966bb0f824b021f77b03f02e00905a532554aad3c6c5a14c2a743aeb4a34a9',
        'replace_file': REQUIRE_BUILTIN_JS,
        'marker': '[dsha-android]',
    },
]

HELPER = "\n// [dsha-android] 原生运行环境没有 /root：凭据目录由宿主以 DSHA_BRIDGE_STATE_DIR 给出（规范化的绝对路径）。\n// 这是数据位置，不是代码位置；文件本身仍按原规则核验（普通文件、0600、O_NOFOLLOW、前后 dev/ino）。\nfunction dshaStateDirectory() {\n  if (typeof process === 'undefined' || process.platform !== 'android') return '/root/.dsh';\n  const value = process.env && process.env.DSHA_BRIDGE_STATE_DIR;\n  if (typeof value !== 'string' || !value.startsWith('/') || value.length > 1024\n      || value.split('/').some((part, index) => index > 0 && (part === '' || part === '.' || part === '..')))\n    throw Object.assign(Error('DSHA_BRIDGE_STATE_DIR'), { code: 'DSHA_BRIDGE_STATE_DIR' });\n  return value;\n}\n"

NATIVE_CONSENT_TOKEN_OLD = "const TOKEN = '/root/.dsh/.bridge_token';\n"
NATIVE_CONSENT_TOKEN_NEW = HELPER + "const TOKEN = dshaStateDirectory() + '/.bridge_token';\n"
NATIVE_CONSENT_PARENT_OLD = "  for (const parent of ['/root', '/root/.dsh']) {\n"
NATIVE_CONSENT_PARENT_NEW = (
    "  // [dsha-android] 容器里是 /root、/root/.dsh；原生里对凭据目录的每一级祖先都做同样的非链接检查。\n"
    "  const parents = TOKEN.split('/').slice(1, -1).map((_, i, all) => '/' + all.slice(0, i + 1).join('/'));\n"
    "  for (const parent of parents) {\n")
BRIDGE_PORT_OLD = "const PORT_FILE = '/root/.dsh/.bridge_port';\n"
BRIDGE_PORT_NEW = HELPER + "const PORT_FILE = dshaStateDirectory() + '/.bridge_port';\n"
POLICY_SDK_OLD = "    const sdk = require('/run/dsha-launcher/native-consent.cjs');\n"
POLICY_SDK_NEW = (
    "    // [dsha-android] 原生运行环境没有 /run/dsha-launcher；签名 SDK 随本包放在固定相对位置，不由环境变量指定。\n"
    "    const sdk = require(process.platform === 'android' ? './launcher/native-consent.cjs' : '/run/dsha-launcher/native-consent.cjs');\n")
POLICY = D + 'dsha-destructive-policy/'
LAUNCHER_PATCHES = [
    {
        'id': 'policy-native-consent-location',
        'path': POLICY + 'index.js',
        'original_sha256': 'c94f390cb21491a348909133e51b76b3eabed3942fc82a069f73ad566b56ac93',
        'replace_exact': [(POLICY_SDK_OLD, POLICY_SDK_NEW)],
        'marker': '[dsha-android]',
    },
    {
        'id': 'native-consent-state-dir',
        'path': POLICY + 'launcher/native-consent.cjs',
        'original_sha256': '4ee1b0ecec2884ccdfa7c484d48f17f604b9ea1b7c24650b8b6ef30068040d03',
        'replace_exact': [(NATIVE_CONSENT_TOKEN_OLD, NATIVE_CONSENT_TOKEN_NEW), (NATIVE_CONSENT_PARENT_OLD, NATIVE_CONSENT_PARENT_NEW)],
        'marker': '[dsha-android]',
    },
    {
        'id': 'bridge-port-state-dir',
        'path': POLICY + 'launcher/bridge-port.cjs',
        'original_sha256': 'be413341075a0c23b34314c9a85ebcc032cc9d3dbd83a9298b3f631733869dd0',
        'replace_exact': [(BRIDGE_PORT_OLD, BRIDGE_PORT_NEW)],
        'marker': '[dsha-android]',
    },
]
PATCHES += LAUNCHER_PATCHES


# ---------------------------------------------------------------- 内置插件（原生树 dsha-plugins/<目录>/）
# 只改凭据文件位置：android 上由 DSHA_BRIDGE_STATE_DIR 给出，其他平台仍是 /root/.dsh。
# HELPER 追加在文件末尾（函数声明会提升，CJS 与 ESM 都可用）。
PLUGIN_TOKEN = "dshaStateDirectory() + '/.bridge_token'"
PLUGIN_DIRS = {
    'dsh-web-mobile': 'dsha-web-mobile',
    'dsh-computer-use-android': 'dsha-computer-use-android',
    'dsh-task-notifier': 'dsha-task-notifier',
    'dsh-tool-vscreen': 'dsha-tool-vscreen',
    'dsh-status-overlay': 'dsha-status-overlay',
    'dsh-launch-target': 'dsha-launch-target',
    'dsh-device-shell-guide': 'dsha-device-shell-guide',
    'dsh-app-integration': 'dsha-app-integration',
}
BRIDGE_PORT_SHA = 'be413341075a0c23b34314c9a85ebcc032cc9d3dbd83a9298b3f631733869dd0'
PLUGIN_PATCHES = [
    {
        'id': 'plugin-%s-bridge-port' % d,
        'path': d + '/lib/bridge-port.cjs',
        'original_sha256': BRIDGE_PORT_SHA,
        'replace_exact': [(BRIDGE_PORT_OLD, BRIDGE_PORT_NEW)],
        'marker': '[dsha-android]',
    }
    for d in ('dsha-computer-use-android', 'dsha-task-notifier', 'dsha-tool-vscreen', 'dsha-status-overlay', 'dsha-launch-target',
              'dsha-device-shell-guide')
] + [
    {
        'id': 'plugin-computer-use-token',
        'path': 'dsha-computer-use-android/lib/server.cjs',
        'original_sha256': '084dd7629e904d2e11bf3df829308751d2a3da7a326cfaa61ca83e599216c24a',
        'replace_exact': [("dependencies.fs.readFile('/root/.dsh/.bridge_token','utf8')",
                           "dependencies.fs.readFile(" + PLUGIN_TOKEN + ",'utf8')")],
        'append': HELPER,
        'marker': '[dsha-android]',
    },
    {
        'id': 'plugin-vscreen-token',
        'path': 'dsha-tool-vscreen/lib/server.cjs',
        'original_sha256': 'e4681b5e642990151477ffd1791efe3a4abbafaeca4a0fd9db950144b62bc42c',
        'replace_exact': [
            ("const TOKEN_FILE='/root/.dsh/.bridge_token',", "const TOKEN_FILE=" + PLUGIN_TOKEN + ","),
            (" for(const path of ['/root','/root/.dsh']){",
             " // [dsha-android] 对凭据目录的每一级祖先做同样的非链接检查（容器里就是 /root、/root/.dsh）。\n"
             " for(const path of TOKEN_FILE.split('/').slice(1,-1).map((_,i,all)=>'/'+all.slice(0,i+1).join('/'))){"),
        ],
        'append': HELPER,
        'marker': '[dsha-android]',
    },
    {
        'id': 'plugin-task-notifier-token',
        'path': 'dsha-task-notifier/lib/index.js',
        'original_sha256': 'b87ecc4daccd6f504fe35d2bb8ee0011c1e7e86cd475902083be5de27b0afc7d',
        'replace_exact': [("readFile('/root/.dsh/.bridge_token', 'utf8')", "readFile(" + PLUGIN_TOKEN + ", 'utf8')")],
        'append': HELPER,
        'marker': '[dsha-android]',
    },
    {
        'id': 'plugin-status-overlay-token',
        'path': 'dsha-status-overlay/lib/index.js',
        'original_sha256': 'f7de73c02273948b52bfd0d5de16852a675de029e04f88783607893efdafe351',
        'replace_exact': [("const TOKEN_PATH = '/root/.dsh/.bridge_token'", "const TOKEN_PATH = " + PLUGIN_TOKEN)],
        'append': HELPER,
        'marker': '[dsha-android]',
    },
    {
        'id': 'plugin-launch-target-token',
        'path': 'dsha-launch-target/lib/index.js',
        'original_sha256': 'fc9145cbad5de78e50f8e13eae94557587b2fb9cb4219d1b6bcf2d5b6b706d81',
        'replace_exact': [("readFile('/root/.dsh/.bridge_token', 'utf8')", "readFile(" + PLUGIN_TOKEN + ", 'utf8')")],
        'append': HELPER,
        'marker': '[dsha-android]',
    },
]

import pathlib as _pathlib
DEVICE_GUIDE_NATIVE = (_pathlib.Path(__file__).with_name('device-guide-native.js')).read_text(encoding='utf-8')
PLUGIN_PATCHES.append({
    'id': 'plugin-device-guide-native',
    'path': 'dsha-device-shell-guide/lib/index.js',
    'original_sha256': '08632be1e7701089040c85cfe9e65bb46a5fe22c85a2248dd56dd04ef080bca5',
    'replace_exact': [
        ("fs.openSync('/root/.dsh/.bridge_token', ", "fs.openSync(" + PLUGIN_TOKEN + ", "),
        ("export function promptText(language = fallbackLanguage()) {\n  let known;\n",
         "export function promptText(language = fallbackLanguage()) {\n"
         "  // [dsha-android] 原生运行环境没有容器、curl、adb-shell：换成原生版引导。\n"
         "  if (process.platform === 'android') return dshaNativePromptText(language);\n"
         "  let known;\n"),
    ],
    'append': HELPER + DEVICE_GUIDE_NATIVE,
    'marker': '[dsha-android]',
})

PLUGIN_PATCHES.append({
    'id': 'plugin-app-integration-activity',
    'path': 'dsha-app-integration/index.js',
    'original_sha256': 'a8b14d4d82d7623e7f8049e1ebb624a91ef8b044865afbca0cf6c586326ac161',
    'replace_exact': [(
        "    const file = '/root/.dsha-web-activity.json';\n",
        "    // [dsha-android] 原生运行环境没有 /root：活动快照写到宿主给的位置（App 按本次运行环境读取）。\n"
        "    const file = process.platform === 'android' && process.env.DSHA_ACTIVITY_FILE?.startsWith('/')\n"
        "      ? process.env.DSHA_ACTIVITY_FILE : '/root/.dsha-web-activity.json';\n")],
    'marker': '[dsha-android]',
})
