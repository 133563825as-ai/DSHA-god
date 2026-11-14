package com.deepseekharness.app.contract

import java.util.Objects
import java.util.concurrent.atomic.AtomicLong

/**
 * 编译期模块的登记、安装、贡献、启停和清理；不加载外部代码。
 *
 * contractVersion/describe 校验、启停意图持久化与 ModuleHost scope 继续 deferred。 install 仍在 registry 锁内运行
 * factory（N3 保留）。
 */
class ModuleRegistry(
    layoutStore: LayoutStore?,
    safeMode: SafeModeGate?,
    diagnostics: DiagnosticsSink?,
) {
    private val diagnostics = Objects.requireNonNull(diagnostics, "diagnostics")!!
    private val safeMode = Objects.requireNonNull(safeMode, "safeMode")!!
    private val catalog =
        ContributionCatalog(Objects.requireNonNull(layoutStore, "layoutStore"), diagnostics)
    private val routes = RouteTable(diagnostics)
    private val modules = LinkedHashMap<ModuleId?, Entry>()

    @Synchronized
    fun register(descriptor: ModuleDescriptor?, factory: ModuleFactory?): ContractResult {
        if (descriptor == null || factory == null) {
            diagnostics.report(
                ContractCodes.MODULE_INVALID,
                "register requires descriptor and factory",
            )
            return ContractResult.rejected(ContractCodes.MODULE_INVALID)
        }
        if (modules.containsKey(descriptor.id())) {
            diagnostics.report(
                ContractCodes.MODULE_DUPLICATE_ID,
                "module already registered: ${descriptor.id()}",
            )
            return ContractResult.rejected(ContractCodes.MODULE_DUPLICATE_ID)
        }
        modules[descriptor.id()] = Entry(descriptor, factory)
        return ContractResult.ok()
    }

    @Synchronized
    fun install(id: ModuleId?): ContractResult {
        val entry = modules[id]
        if (entry == null) {
            diagnostics.report(ContractCodes.MODULE_UNKNOWN, "install unknown module: $id")
            return ContractResult.rejected(ContractCodes.MODULE_UNKNOWN)
        }
        if (entry.instance != null) return ContractResult.ok()
        if (safeMode.forcedSafeMode() && !entry.descriptor.alwaysOn()) {
            diagnostics.report(
                ContractCodes.SAFE_MODE_RESTRICTED,
                "safe mode rejects optional module: $id",
            )
            return ContractResult.rejected(ContractCodes.SAFE_MODE_RESTRICTED)
        }
        val instance =
            try {
                entry.factory.create()
            } catch (error: RuntimeException) {
                diagnostics.report(
                    ContractCodes.MODULE_INSTALL_FAILED,
                    "module factory failed: $id (${error.javaClass.simpleName})",
                )
                return ContractResult.rejected(ContractCodes.MODULE_INSTALL_FAILED)
            }
        if (instance == null) {
            diagnostics.report(
                ContractCodes.MODULE_INSTALL_FAILED,
                "module factory returned null: $id",
            )
            return ContractResult.rejected(ContractCodes.MODULE_INSTALL_FAILED)
        }
        val contributions =
            try {
                instance.contributions()
            } catch (error: RuntimeException) {
                closeQuietly(instance)
                diagnostics.report(
                    ContractCodes.MODULE_INSTALL_FAILED,
                    "module contributions failed: $id (${error.javaClass.simpleName})",
                )
                return ContractResult.rejected(ContractCodes.MODULE_INSTALL_FAILED)
            } ?: java.util.List.of()
        val keys = HashSet<String>()
        for (contribution in contributions) {
            if (
                contribution == null ||
                    id != contribution.owner() ||
                    !keys.add(contribution.globalKey()) ||
                    catalog.contains(contribution.globalKey())
            ) {
                closeQuietly(instance)
                diagnostics.report(
                    ContractCodes.MODULE_INVALID_CONTRIBUTION,
                    "invalid contribution for module: $id",
                )
                return ContractResult.rejected(ContractCodes.MODULE_INVALID_CONTRIBUTION)
            }
        }
        val nextGeneration = GENERATION.incrementAndGet()
        val lease = GenerationLease.create(id, nextGeneration, diagnostics)
        val addedKeys = ArrayList<String>(contributions.size)
        for (item in contributions) {
            val contribution = item!!
            val added = catalog.add(contribution, nextGeneration)
            if (added.rejected()) {
                revokeKeys(addedKeys)
                closeQuietly(instance)
                diagnostics.report(
                    added.code(),
                    "catalog add rejected: ${contribution.globalKey()}",
                )
                return added
            }
            addedKeys.add(contribution.globalKey())
            if (contribution.kind() == Contribution.Kind.ROUTE) {
                val routed =
                    routes.register(
                        contribution.globalKey(),
                        RouteTable.Target.module(contribution.routeTarget()),
                    )
                if (routed.rejected()) {
                    revokeKeys(addedKeys)
                    closeQuietly(instance)
                    diagnostics.report(
                        routed.code(),
                        "route registration rejected: ${contribution.globalKey()}",
                    )
                    return routed
                }
            }
        }
        entry.instance = instance
        entry.lease = lease
        @Suppress("UNCHECKED_CAST")
        val copied = java.util.List.copyOf(contributions) as List<Contribution>
        entry.contributions = copied
        return ContractResult.ok()
    }

    /** 停用必须在 registry 锁外 invalidate，避免 registry→lease ABBA。 */
    fun setEnabled(id: ModuleId?, enabled: Boolean): ContractResult {
        synchronized(this) {
            val entry = modules[id]
            if (entry == null) {
                diagnostics.report(ContractCodes.MODULE_UNKNOWN, "setEnabled unknown module: $id")
                return ContractResult.rejected(ContractCodes.MODULE_UNKNOWN)
            }
            if (!enabled) {
                if (entry.descriptor.alwaysOn()) {
                    diagnostics.report(
                        ContractCodes.MODULE_ALWAYS_ON,
                        "always-on module cannot be disabled: $id",
                    )
                    return ContractResult.rejected(ContractCodes.MODULE_ALWAYS_ON)
                }
            } else {
                if (safeMode.forcedSafeMode() && !entry.descriptor.alwaysOn()) {
                    diagnostics.report(
                        ContractCodes.SAFE_MODE_RESTRICTED,
                        "safe mode rejects optional module: $id",
                    )
                    return ContractResult.rejected(ContractCodes.SAFE_MODE_RESTRICTED)
                }
                return install(id)
            }
        }
        return close(id, ModuleInstance.CloseReason.USER_DISABLED)
    }

    /** 锁内只摘除状态，锁外依次 invalidate、close、撤销贡献和诊断。 */
    fun close(id: ModuleId?, reason: ModuleInstance.CloseReason?): ContractResult {
        val instance: ModuleInstance
        val lease: GenerationLease?
        val contributions: List<Contribution>
        synchronized(this) {
            val entry = modules[id]
            if (entry == null) {
                diagnostics.report(ContractCodes.MODULE_UNKNOWN, "close unknown module: $id")
                return ContractResult.rejected(ContractCodes.MODULE_UNKNOWN)
            }
            instance = entry.instance ?: return ContractResult.ok()
            GENERATION.incrementAndGet()
            lease = entry.lease
            entry.lease = null
            entry.instance = null
            contributions = entry.contributions
            entry.contributions = java.util.List.of()
        }
        lease?.invalidateFromHost()
        val actualReason = reason ?: ModuleInstance.CloseReason.HOST_CLOSE
        var failure: Throwable? = null
        try {
            instance.close(actualReason)
        } catch (error: Throwable) {
            failure = error
            reportQuietly(
                ContractCodes.MODULE_CLOSE_FAILED,
                "module close failed: $id (${error.javaClass.simpleName})",
            )
        }
        for (contribution in contributions) {
            try {
                revokeContribution(contribution)
            } catch (error: Throwable) {
                if (failure == null) failure = error
                reportQuietly(
                    ContractCodes.MODULE_CLOSE_FAILED,
                    "module contribution cleanup failed: $id (${error.javaClass.simpleName})",
                )
            }
        }
        reportQuietly(ContractCodes.MODULE_DISABLED, "module closed: $id ($actualReason)")
        if (failure is Error) throw failure
        return if (failure == null) ContractResult.ok()
        else ContractResult.rejected(ContractCodes.MODULE_CLOSE_FAILED)
    }

    fun enterSafeMode(): ContractResult {
        safeMode.enterFromHost()
        val optionalInstalled = ArrayList<ModuleId>()
        synchronized(this) {
            for (entry in modules.values) {
                if (!entry.descriptor.alwaysOn() && entry.instance != null)
                    optionalInstalled.add(entry.descriptor.id())
            }
        }
        for (id in optionalInstalled) close(id, ModuleInstance.CloseReason.SAFE_MODE)
        diagnostics.report(ContractCodes.SAFE_MODE_ENTERED, "safe mode entered")
        return ContractResult.ok()
    }

    @Synchronized
    fun exitSafeMode() {
        safeMode.exitFromHost()
    }

    @Synchronized
    fun revoke(contributionKey: String?): ContractResult {
        val revoked = catalog.revoke(contributionKey)
        if (revoked.accepted() && routes.contains(contributionKey)) routes.revoke(contributionKey)
        return revoked
    }

    @Synchronized
    fun contributions(): List<ContributionView> {
        val all = catalog.contributions()
        if (!safeMode.forcedSafeMode()) return all
        val filtered = ArrayList<ContributionView>()
        for (view in all) {
            val entry = modules[view.owner()]
            if (entry != null && entry.descriptor.alwaysOn()) filtered.add(view)
        }
        return java.util.List.copyOf(filtered)
    }

    @Synchronized
    fun visibleContributions(): List<ContributionView> {
        val visible = ArrayList<ContributionView>()
        for (view in contributions()) if (!view.hidden()) visible.add(view)
        return java.util.List.copyOf(visible)
    }

    @Synchronized fun layout(): LayoutSnapshot = catalog.layout()

    @Synchronized
    fun setContributionHidden(contributionKey: String?, hidden: Boolean): ContractResult =
        catalog.setHidden(contributionKey, hidden)

    @Synchronized
    fun setContributionOrder(contributionKeys: List<String?>?): ContractResult =
        catalog.setOrder(contributionKeys)

    @Synchronized
    fun resolveRoute(routeKey: String?): RouteTable.Target {
        if (safeMode.forcedSafeMode()) {
            val owner = routeOwner(routeKey)
            val entry = if (owner == null) null else modules[owner]
            if (entry == null || !entry.descriptor.alwaysOn()) {
                diagnostics.report(
                    ContractCodes.SAFE_MODE_RESTRICTED,
                    "safe mode route fallback: ${routeKey ?: ""}",
                )
                return RouteTable.Target.host()
            }
        }
        return routes.resolve(routeKey)
    }

    @Synchronized fun lease(id: ModuleId?): GenerationLease? = modules[id]?.lease

    @Synchronized fun generation(id: ModuleId?): Long = lease(id)?.generation() ?: 0

    /** registry 锁外进入 lease；失效后不补发、不重放。 */
    fun dispatch(id: ModuleId?, callbackGeneration: Long, callback: Runnable?): ContractResult {
        val lease: GenerationLease
        synchronized(this) {
            val entry = modules[id]
            if (entry == null) {
                diagnostics.report(ContractCodes.MODULE_UNKNOWN, "dispatch unknown module: $id")
                return ContractResult.rejected(ContractCodes.MODULE_UNKNOWN)
            }
            if (callback == null) {
                diagnostics.report(
                    ContractCodes.INVALID_CALLBACK,
                    "dispatch requires callback: $id",
                )
                return ContractResult.rejected(ContractCodes.INVALID_CALLBACK)
            }
            val current = entry.lease
            if (
                current == null || !current.active() || current.generation() != callbackGeneration
            ) {
                diagnostics.report(
                    ContractCodes.STALE_GENERATION,
                    "stale callback generation for $id: $callbackGeneration",
                )
                return ContractResult.rejected(ContractCodes.STALE_GENERATION)
            }
            lease = current
        }
        if (!lease.runIfActive(callback)) {
            if (!lease.active()) {
                diagnostics.report(
                    ContractCodes.STALE_GENERATION,
                    "stale callback generation for $id: $callbackGeneration",
                )
                return ContractResult.rejected(ContractCodes.STALE_GENERATION)
            }
            return ContractResult.rejected(ContractCodes.MODULE_CALLBACK_FAILED)
        }
        return ContractResult.ok()
    }

    @Synchronized fun isRegistered(id: ModuleId?): Boolean = id != null && modules.containsKey(id)

    @Synchronized fun isInstalled(id: ModuleId?): Boolean = modules[id]?.instance != null

    @Synchronized fun descriptor(id: ModuleId?): ModuleDescriptor? = modules[id]?.descriptor

    fun safeModeGate(): SafeModeGate = safeMode

    private fun revokeContribution(contribution: Contribution) {
        val key = contribution.globalKey()
        if (catalog.contains(key)) catalog.revoke(key)
        if (routes.contains(key)) routes.revoke(key)
    }

    private fun revokeKeys(keys: List<String>) {
        for (key in keys) {
            if (catalog.contains(key)) catalog.revoke(key)
            if (routes.contains(key)) routes.revoke(key)
        }
    }

    private fun reportQuietly(code: String, detail: String) {
        try {
            diagnostics.report(code, detail)
        } catch (_: Throwable) {
            // 诊断故障（含 Error）不得中断清理，真正的清理失败由调用者保留。
        }
    }

    private class Entry(val descriptor: ModuleDescriptor, val factory: ModuleFactory) {
        var instance: ModuleInstance? = null
        var lease: GenerationLease? = null
        var contributions: List<Contribution> = java.util.List.of()
    }

    companion object {
        private val GENERATION = AtomicLong()

        private fun routeOwner(routeKey: String?): ModuleId? {
            if (routeKey == null) return null
            val separator = routeKey.indexOf('#')
            if (separator <= 0) return null
            return try {
                ModuleId(routeKey.substring(0, separator))
            } catch (_: IllegalArgumentException) {
                null
            }
        }

        private fun closeQuietly(instance: ModuleInstance) {
            try {
                instance.close(ModuleInstance.CloseReason.HOST_CLOSE)
            } catch (_: RuntimeException) {
                // 安装失败的兜底清理；异常不冒泡，也不重放。
            }
        }
    }
}
