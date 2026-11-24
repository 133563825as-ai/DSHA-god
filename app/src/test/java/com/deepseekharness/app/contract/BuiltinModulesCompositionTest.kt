package com.deepseekharness.app.contract

import org.junit.Assert.*
import org.junit.Test

/** 产品装配另测，不改变既有 about-only 回归与合成演示断言。 */
class BuiltinModulesCompositionTest {
    private class MemoryLayout : LayoutStore {
        var value = LayoutSnapshot.empty()
        override fun load(): LayoutSnapshot = value
        override fun save(snapshot: LayoutSnapshot?) { value = requireNotNull(snapshot) }
    }

    private fun registry() = ModuleRegistry(MemoryLayout(), SafeModeGate(), DiagnosticsSink.discarding())

    private fun restored(preferences: android.content.SharedPreferences): ModuleRegistry {
        // 既有 protected JVM 同包 Java 接缝；Kotlin 不支持 Java 的同包 protected 规则。
        val method = BuiltinModules::class.java.getDeclaredMethod(
            "ensureRegistered", android.content.SharedPreferences::class.java)
        method.isAccessible = true
        return method.invoke(null, preferences) as ModuleRegistry
    }

    @Test
    fun disableReenableChangesLeaseWithoutChangingLayout() {
        val registry = registry()
        assertTrue(BuiltinModules.ensureProductModules(registry).accepted())
        assertEquals(setOf(BuiltinModules.ABOUT_CONTRIBUTION_KEY,
            BuiltinModules.THEME_CONTRIBUTION.globalKey(),
            BuiltinModules.STARTUP_HISTORY_CONTRIBUTION.globalKey()),
            registry.contributions().map { it.key() }.toSet())
        assertFalse(registry.isRegistered(BuiltinModules.STORAGE_DEMO_MODULE_ID))
        val lease = BuiltinModules.themeLease(registry)!!
        val key = BuiltinModules.THEME_CONTRIBUTION.globalKey()
        assertTrue(registry.setContributionHidden(key, true).accepted())
        assertTrue(lease.active())
        var retired = 0
        BuiltinModules.observeThemeRetirement(registry, lease, Runnable { retired++ })
        assertTrue(BuiltinModules.setThemeEnabled(registry, false).accepted())
        assertFalse(lease.active())
        assertEquals(1, retired)
        assertNull(BuiltinModules.themeLease(registry))
        assertTrue(BuiltinModules.ensureProductModules(registry).accepted())
        assertFalse(registry.isInstalled(BuiltinModules.THEME_MODULE_ID))
        assertTrue(BuiltinModules.setThemeEnabled(registry, true).accepted())
        assertNotEquals(lease.generation(), BuiltinModules.themeLease(registry)!!.generation())
        assertTrue(registry.layout().isHidden(key))
        assertTrue(registry.isInstalled(BuiltinModules.ABOUT_MODULE_ID))
        assertFalse(lease.runIfActive(Runnable { fail("retired generation committed") }))

        val fresh = BuiltinModules.themeLease(registry)!!
        assertTrue(registry.revoke(key).accepted())
        assertFalse(fresh.active())
        assertEquals(setOf(BuiltinModules.ABOUT_CONTRIBUTION_KEY,
            BuiltinModules.STARTUP_HISTORY_CONTRIBUTION.globalKey()),
            registry.contributions().map { it.key() }.toSet())
        assertTrue(BuiltinModules.setThemeEnabled(registry, true).accepted())
        assertNotEquals(fresh.generation(), BuiltinModules.themeLease(registry)!!.generation())

        // 新 registry 模拟冷进程装配，共享同布局命名空间而非 ConfigStore。
        val preferences = ContractTestSupport.FakeSharedPreferences()
        val persisted = restored(preferences)
        BuiltinModules.ensureProductModules(persisted)
        BuiltinModules.setThemeEnabled(persisted, false)
        restored(ContractTestSupport.FakeSharedPreferences()) // 丢进程缓存
        val cold = restored(preferences)
        BuiltinModules.ensureProductModules(cold)
        assertNull(BuiltinModules.themeLease(cold))
        assertTrue(cold.isInstalled(BuiltinModules.ABOUT_MODULE_ID))
        BuiltinModules.setThemeEnabled(cold, true)
        cold.revoke(key)
        restored(ContractTestSupport.FakeSharedPreferences())
        val afterWithdrawal = restored(preferences)
        BuiltinModules.ensureProductModules(afterWithdrawal)
        assertNotNull(BuiltinModules.themeLease(afterWithdrawal)) // 自动撤回不持久化用户禁用

        // 已开始提交的 lease 可完成；撤回等待它，但绝不能持 registry monitor 等待。
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val completed = java.util.concurrent.CountDownLatch(1)
        val racingLease = BuiltinModules.themeLease(afterWithdrawal)!!
        val workerResult = java.util.concurrent.atomic.AtomicReference<Boolean>()
        val registryRead = java.util.concurrent.atomic.AtomicBoolean(false)
        val revokeResult = java.util.concurrent.atomic.AtomicReference<ContractResult>()
        val threadFailure = java.util.concurrent.atomic.AtomicReference<Throwable>()
        val worker = Thread {
            try {
                workerResult.set(racingLease.runIfActive(Runnable {
                    entered.countDown()
                    check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    val observed = afterWithdrawal.contributions() // 必须真正读到 registry
                    registryRead.set(observed.any { it.key() == BuiltinModules.ABOUT_CONTRIBUTION_KEY })
                }))
            } catch (error: Throwable) { threadFailure.compareAndSet(null, error) }
        }.apply { isDaemon = true }
        val revoker = Thread {
            try { revokeResult.set(afterWithdrawal.revoke(key)) }
            catch (error: Throwable) { threadFailure.compareAndSet(null, error) }
            finally { completed.countDown() }
        }.apply { isDaemon = true }
        worker.start()
        try {
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
        } catch (error: Throwable) {
            release.countDown()
            worker.join(5000)
            throw error
        }
        revoker.start()
        try {
            // 等待已经摘掉 entry 的确定状态，不靠 sleep 猜 revoke 的进度。
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
            while (afterWithdrawal.isInstalled(BuiltinModules.THEME_MODULE_ID) &&
                System.nanoTime() < deadline) Thread.yield()
            assertFalse(afterWithdrawal.isInstalled(BuiltinModules.THEME_MODULE_ID))
            assertTrue(afterWithdrawal.isInstalled(BuiltinModules.ABOUT_MODULE_ID))
            assertEquals(ContractCodes.MODULE_DISABLED, afterWithdrawal.revoke(key).code())
            assertEquals(ContractCodes.MODULE_DISABLED,
                afterWithdrawal.setEnabled(BuiltinModules.THEME_MODULE_ID, true).code())
            assertEquals(1L, completed.count) // 不能已报成功却让旧 lease 仍可运行
        } finally {
            release.countDown()
            worker.join(5000)
            revoker.join(5000)
        }
        assertFalse("worker must exit within the bound", worker.isAlive)
        assertFalse("revoker must exit within the bound", revoker.isAlive)
        assertEquals("revoker must report completion", 0L, completed.count)
        assertNull("background exceptions must fail the test", threadFailure.get())
        assertEquals("lease callback must actually succeed, not swallow a timeout", true, workerResult.get())
        assertTrue("callback must actually read legal About from registry", registryRead.get())
        assertNotNull("revoke must return a result", revokeResult.get())
        assertTrue("revoke must succeed after the active callback exits", revokeResult.get().accepted())
        assertFalse(racingLease.active())
        assertTrue(BuiltinModules.setThemeEnabled(afterWithdrawal, true).accepted())
        assertFalse(racingLease.runIfActive(Runnable { fail("old racing lease committed") }))
    }

    @Test
    fun duplicateAndPartialRegistrationAreRejectedLocally() {
        val registry = registry()
        var closed = 0
        assertTrue(registry.register(BuiltinModules.THEME_DESCRIPTOR, ModuleFactory {
            object : ModuleInstance {
                override fun contributions(): List<Contribution> = listOf(
                    BuiltinModules.THEME_CONTRIBUTION, BuiltinModules.THEME_CONTRIBUTION)
                override fun close(reason: ModuleInstance.CloseReason?) { closed++ }
            }
        }).accepted())
        assertEquals(ContractCodes.MODULE_INVALID_CONTRIBUTION,
            registry.install(BuiltinModules.THEME_MODULE_ID).code())
        assertEquals(1, closed)
        assertEquals(ContractCodes.MODULE_DUPLICATE_ID,
            BuiltinModules.ensureProductModules(registry).code())
        assertNull(BuiltinModules.themeLease(registry))
        assertTrue(BuiltinModules.setThemeEnabled(registry, true).rejected())
        assertEquals(1, closed) // 不再次执行冲突 factory
        assertEquals(listOf(BuiltinModules.ABOUT_CONTRIBUTION_KEY,
            BuiltinModules.STARTUP_HISTORY_CONTRIBUTION.globalKey()),
            registry.visibleContributions().map { it.key() })
        assertEquals(ContractCodes.MODULE_ALWAYS_ON,
            registry.setEnabled(BuiltinModules.ABOUT_MODULE_ID, false).code())
    }
}
