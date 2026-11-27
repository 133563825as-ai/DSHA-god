package com.deepseekharness.app.saf

import android.content.Context
import android.content.SharedPreferences
import java.util.LinkedHashSet
import java.util.Objects

/**
 * 授权簿的 Android 持久化适配器。
 *
 * [NAMESPACE] 只属于 SAF 能力层，不写 ConfigStore，不读写历史 saf_grants 键，也不碰模块布局的
 * dsha_module_layout。保存时只删除本版本认识的 key；未知 key 与结构损坏但需保留的字段原样留在 prefs。
 *
 * 本类不做业务判定，调用方负责串行化自己的写操作；同一进程内建议只持有一个实例。
 */
class SafLedgerStore(preferences: SharedPreferences?) {
    private val preferences: SharedPreferences =
        Objects.requireNonNull(preferences, "preferences")!!

    /** 读取授权簿；prefs 不可读或全为坏值时返回空簿，不抛异常、不误删磁盘内容。 */
    fun load(): SafGrantLedger {
        return loadDecoded().ledger!!
    }

    /** 读取并保留解码警告与未知条目，供迁移/诊断使用（诊断只输出短码，不输出 URI）。 */
    fun loadDecoded(): SafLedgerCodec.Decoded {
        try {
            return SafLedgerCodec.decode(preferences.all)
        } catch (unreadable: RuntimeException) {
            return SafLedgerCodec.Decoded(
                SafGrantLedger.empty(),
                java.util.Map.of(),
                java.util.List.of("STORE_UNREADABLE"),
            )
        }
    }

    /** 是否已完成初始化/迁移。用 schema 键做标记：账本可以为空（用户移除全部授权），不能按记录为空判断， 避免把旧命名空间里的历史授权反复迁移回来。 */
    fun initialized(): Boolean {
        try {
            return preferences.contains(SafLedgerCodec.PREF_SCHEMA)
        } catch (unreadable: RuntimeException) {
            return false
        }
    }

    /** 原子保存整份授权簿；false 表示读取或提交失败，调用方不得假装已保存。 */
    fun save(ledger: SafGrantLedger?): Boolean {
        Objects.requireNonNull(ledger, "ledger")
        val desired = SafLedgerCodec.encode(ledger)
        val current: Map<String, *>?
        try {
            current = preferences.all
        } catch (unreadable: RuntimeException) {
            return false
        }
        val currentSchema = current!![SafLedgerCodec.PREF_SCHEMA]
        if (currentSchema != null && SafLedgerCodec.SCHEMA.toString() != currentSchema.toString()) {
            // 磁盘上是更新/损坏的 schema：本版本读不懂，也不能覆盖成 1 后删掉未来字段。
            return false
        }
        // decode 标记为结构损坏但需原样保留的已知 key 不能在保存时被清掉。
        val preserved = SafLedgerCodec.decode(current).preserved.keys
        val editor = preferences.edit()
        try {
            for (key in current.keys) {
                if (desired.containsKey(key) || preserved.contains(key)) continue
                if (SafLedgerCodec.knownKey(key)) editor.remove(key)
            }
            for ((key, value) in desired) {
                if (same(current[key], value)) continue
                if (value is Set<*>) {
                    val strings = LinkedHashSet<String>()
                    for (item in value) {
                        if (item !is String) return false
                        strings.add(item)
                    }
                    editor.putStringSet(key, strings)
                } else if (value is String) {
                    editor.putString(key, value)
                } else {
                    // encode 只产出 String/StringSet；其它类型说明内部不变量已坏，拒绝写入半份状态。
                    return false
                }
            }
            return editor.commit()
        } catch (rejected: RuntimeException) {
            return false
        }
    }

    companion object {
        /** 独立命名空间；与 saf_grants（旧授权页）和 dsha_module_layout 分开。 */
        const val NAMESPACE = "saf_capability_ledger"

        @JvmStatic
        fun open(context: Context?): SafLedgerStore {
            if (context == null) throw IllegalArgumentException("CONTEXT_REQUIRED")
            return SafLedgerStore(context.getSharedPreferences(NAMESPACE, Context.MODE_PRIVATE))
        }

        @JvmStatic
        private fun same(left: Any?, right: Any?): Boolean {
            if (left == null || right == null) return left === right
            if (left is Set<*> && right is Set<*>) return left == right
            return left == right
        }
    }
}
