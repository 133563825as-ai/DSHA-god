package com.deepseekharness.app.contract

import android.content.Context
import android.content.SharedPreferences
import java.util.Objects

/** 随包可信内置模块登记；法定许可聚合继续由宿主与关于页兜底。 */
class BuiltinModules private constructor() {
    companion object {
        @JvmField val ABOUT_MODULE_ID = ModuleId("builtin.about")
        const val ABOUT_CONTRIBUTION_ID = "about"
        @JvmField val ABOUT_CONTRIBUTION_KEY = ABOUT_MODULE_ID.value() + "#" + ABOUT_CONTRIBUTION_ID
        const val ABOUT_TITLE = "关于与许可"
        const val ABOUT_TITLE_EN = "About and licenses"
        @JvmField
        val ABOUT_DESCRIPTOR =
            ModuleDescriptor(
                ABOUT_MODULE_ID,
                1,
                ABOUT_TITLE,
                java.util.Set.of(),
                ModuleDescriptor.Enablement.ALWAYS_ON,
            )
        @JvmField val STORAGE_DEMO_MODULE_ID = ModuleId("builtin.storage-demo")
        @JvmField val STORAGE_DEMO_CONTRIBUTION = Contribution.card(
            STORAGE_DEMO_MODULE_ID, "overview", "存储总览 · 合成演示", "Storage overview · Synthetic demo", 10)
        private val STORAGE_DEMO_DESCRIPTOR = ModuleDescriptor(
            STORAGE_DEMO_MODULE_ID, 1, "存储合成演示", java.util.Set.of(),
            ModuleDescriptor.Enablement.ALWAYS_ON)
        @JvmField val PROCESS_EPOCH: String = java.util.UUID.randomUUID().toString()
        @JvmField val THEME_MODULE_ID = ModuleId("builtin.theme")
        @JvmField val THEME_CONTRIBUTION = Contribution.card(
            THEME_MODULE_ID, "appearance", "主题与外观", "Theme and appearance", 5)
        @JvmField val THEME_DESCRIPTOR = ModuleDescriptor(
            THEME_MODULE_ID, 1, "主题与外观", java.util.Set.of(),
            ModuleDescriptor.Enablement.OPTIONAL_USER)
        private const val THEME_ENABLED_KEY = "module.builtin.theme.enabled"
        private val registryPreferences = java.util.WeakHashMap<ModuleRegistry, SharedPreferences>()
        private val themeRuntimes = java.util.WeakHashMap<ModuleRegistry, ThemeRuntime>()
        private val LOCK = Any()
        private val BUILTIN_DIAGNOSTICS = DiagnosticsSink.discarding()
        private var cachedPreferences: SharedPreferences? = null
        private var cachedRegistry: ModuleRegistry? = null

        /** 同一应用上下文的独立布局命名空间复用同一进程内注册表。 */
        @JvmStatic
        fun ensureRegistered(context: Context?): ModuleRegistry {
            if (context == null) throw IllegalArgumentException("CONTEXT_REQUIRED")
            val owner = context.applicationContext ?: context
            return ensureRegistered(
                owner.getSharedPreferences(
                    SharedPreferencesLayoutStore.NAMESPACE,
                    Context.MODE_PRIVATE,
                )
            )
        }

        // final 类的 protected static 保留原 Java 同包入口；外包无法继承扩大可见性。
        @JvmStatic
        protected fun ensureRegistered(preferences: SharedPreferences?): ModuleRegistry {
            Objects.requireNonNull(preferences, "preferences")
            synchronized(LOCK) {
                val previous = cachedRegistry
                if (cachedPreferences === preferences && previous != null) return previous
                val registry =
                    ModuleRegistry(
                        SharedPreferencesLayoutStore(preferences, BUILTIN_DIAGNOSTICS),
                        SafeModeGate(),
                        BUILTIN_DIAGNOSTICS,
                    )
                registryPreferences[registry] = preferences!!
                ensureRegistered(registry)
                cachedPreferences = preferences
                cachedRegistry = registry
                return registry
            }
        }

        /** 首版真实装配；旧 about-only 契约和显式合成演示入口保持不变。 */
        @JvmStatic
        fun ensureProductModules(context: Context): ModuleRegistry {
            val registry = ensureRegistered(context)
            ensureProductModules(registry)
            return registry
        }

        @JvmStatic
        fun ensureProductModules(registry: ModuleRegistry): ContractResult {
            val about = ensureRegistered(registry)
            // 可选模块冲突不影响关于与宿主恢复入口；也不能收编已占用的 ID。
            synchronized(LOCK) {
                if (themeRuntimes.containsKey(registry)) return about
                val runtime = ThemeRuntime()
                themeRuntimes[registry] = runtime
                val registered = registry.register(THEME_DESCRIPTOR, ModuleFactory {
                    ThemeInstance().also { runtime.instance = it }
                }, true)
                if (registered.rejected()) return registered
                runtime.registered = true
                val enabled = registryPreferences[registry]?.getBoolean(THEME_ENABLED_KEY, true) ?: true
                val installed = if (enabled) registry.install(THEME_MODULE_ID) else ContractResult.ok()
                return if (about.rejected()) about else installed
            }
        }

        /** 隐藏仅影响入口；页面权限绑定安装代次而不是可见性。 */
        @JvmStatic
        fun themeLease(registry: ModuleRegistry): GenerationLease? {
            val instance = synchronized(LOCK) { themeRuntimes[registry]?.instance }
            val lease = if (instance == null || instance.closed) null else registry.lease(THEME_MODULE_ID)
            return if (lease?.active() == true) lease else null
        }

        @JvmStatic
        fun themeActive(lease: GenerationLease): Boolean =
            lease.owner() == THEME_MODULE_ID && lease.active()

        /** 只订阅本页的退休清理，不管理 Web、PTY 或配置本身。 */
        @JvmStatic
        fun observeThemeRetirement(registry: ModuleRegistry, lease: GenerationLease,
            cleanup: Runnable): AutoCloseable = synchronized(LOCK) {
            val instance = themeRuntimes[registry]?.instance
            if (instance == null || instance.closed || registry.lease(THEME_MODULE_ID) !== lease ||
                !lease.active()) {
                cleanup.run()
                AutoCloseable { }
            } else {
                instance.cleanups.add(cleanup)
                AutoCloseable { synchronized(LOCK) { instance.cleanups.remove(cleanup) } }
            }
        }

        @JvmStatic
        fun setThemeEnabled(registry: ModuleRegistry, enabled: Boolean): ContractResult {
            val owned = synchronized(LOCK) { themeRuntimes[registry]?.registered == true }
            if (!owned) return ContractResult.rejected(ContractCodes.MODULE_DUPLICATE_ID)
            val result = registry.setEnabled(THEME_MODULE_ID, enabled)
            if (result.accepted()) {
                val preferences = synchronized(LOCK) { registryPreferences[registry] }
                preferences?.edit()?.putBoolean(THEME_ENABLED_KEY, enabled)?.apply()
            }
            return result
        }

        private class ThemeRuntime {
            var registered = false
            var instance: ThemeInstance? = null
        }

        private class ThemeInstance : ModuleInstance {
            var closed = false
            val cleanups = ArrayList<Runnable>()
            override fun contributions(): List<Contribution> = java.util.List.of(THEME_CONTRIBUTION)
            override fun close(reason: ModuleInstance.CloseReason?) {
                val retiring = synchronized(LOCK) {
                    closed = true
                    ArrayList(cleanups).also { cleanups.clear() }
                }
                for (cleanup in retiring) {
                    try { cleanup.run() } catch (_: RuntimeException) {
                        // 一个页面清理失败不能阻断同代其它页面退休。
                    }
                }
            }
        }

        /** Optional synthetic-only module; failure cannot prevent the legal About fallback. */
        @JvmStatic
        fun ensureStorageDemo(registry: ModuleRegistry): ContractResult {
            if (!registry.isRegistered(STORAGE_DEMO_MODULE_ID)) {
                val result = registry.register(STORAGE_DEMO_DESCRIPTOR, ModuleFactory {
                    object : ModuleInstance {
                        override fun contributions(): List<Contribution> =
                            java.util.List.of(STORAGE_DEMO_CONTRIBUTION)
                    }
                })
                if (result.rejected()) return result
            }
            if (!registry.isInstalled(STORAGE_DEMO_MODULE_ID)) {
                val result = registry.install(STORAGE_DEMO_MODULE_ID)
                if (result.rejected()) return result
            }
            return ContractResult.ok()
        }

        /** 幂等登记并安装 about 模块。 */
        @JvmStatic
        fun ensureRegistered(registry: ModuleRegistry?): ContractResult {
            if (registry == null) throw IllegalArgumentException("REGISTRY_REQUIRED")
            if (registry.isRegistered(ABOUT_MODULE_ID)) {
                return if (registry.isInstalled(ABOUT_MODULE_ID)) ContractResult.ok()
                else registry.install(ABOUT_MODULE_ID)
            }
            val registered = registry.register(ABOUT_DESCRIPTOR, ModuleFactory { aboutInstance() })
            if (registered.rejected()) return registered
            return registry.install(ABOUT_MODULE_ID)
        }

        private fun aboutInstance(): ModuleInstance =
            object : ModuleInstance {
                private val contributions =
                    java.util.List.of(
                        Contribution.card(
                            ABOUT_MODULE_ID,
                            ABOUT_CONTRIBUTION_ID,
                            ABOUT_TITLE,
                            ABOUT_TITLE_EN,
                            0,
                        )
                    )

                override fun contributions(): List<Contribution> = contributions
            }
    }
}
