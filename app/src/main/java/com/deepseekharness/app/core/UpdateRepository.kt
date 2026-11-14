package com.deepseekharness.app.core

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.deepseekharness.app.util.SensitiveData
import com.deepseekharness.app.util.UiText
import com.deepseekharness.app.util.UpdatePolicy
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executor

/** 页面只订阅应用范围的下载状态；关闭页面不会取消前台下载。 */
class UpdateRepository : AndroidViewModel {
    private val engine: UpdateEngine
    private val verifyExecutor: Executor
    private val verifyApk: Callable<File>
    private val main = Handler(Looper.getMainLooper())
    private val installation = MutableLiveData(InstallState(false, null, null))
    private var installRequest = 0L
    private var cleared = false
    private var installRelease: UpdatePolicy.Release? = null
    private var installChannel: String? = null

    enum class Stage {
        IDLE,
        CHECKING,
        DOWNLOADING,
        VERIFYING,
    }

    class State(
        @JvmField val message: String?,
        @JvmField val stage: Stage?,
        @JvmField val downloaded: Long,
        @JvmField val total: Long,
        @JvmField val release: UpdatePolicy.Release?,
        @JvmField val apk: File?,
        @JvmField val channel: String?,
        @JvmField val sourceChannel: String?,
    ) {
        @JvmField val busy = stage != Stage.IDLE

        constructor(
            text: String?,
            busy: Boolean,
            n: Long,
            total: Long,
            release: UpdatePolicy.Release?,
            apk: File?,
        ) : this(text, busy, n, total, release, apk, null, null)

        constructor(
            text: String?,
            busy: Boolean,
            n: Long,
            total: Long,
            release: UpdatePolicy.Release?,
            apk: File?,
            channel: String?,
            sourceChannel: String?,
        ) : this(
            text,
            if (busy) Stage.DOWNLOADING else Stage.IDLE,
            n,
            total,
            release,
            apk,
            channel,
            sourceChannel,
        )
    }

    class InstallState(
        @JvmField val verifying: Boolean,
        @JvmField val apk: File?,
        @JvmField val error: String?,
    ) {
        fun pending(): Boolean = verifying || apk != null
    }

    constructor(app: Application) : this(app, UpdateEngine.get(app))

    private constructor(
        app: Application,
        engine: UpdateEngine,
    ) : this(app, engine, engine.verificationExecutor(), null)

    // 可控执行器用于校验中重建、离开页面和重复领取的确定性自测。
    constructor(
        app: Application,
        engine: UpdateEngine,
        executor: Executor,
        verifier: Callable<File>?,
    ) : super(app) {
        this.engine = engine
        verifyExecutor = executor
        verifyApk = verifier ?: Callable { engine.installableApk() }
    }

    fun state(): LiveData<State> = engine.state()

    fun channel(): String = engine.channel()

    fun setChannel(channel: String?) {
        if (!installationPending()) engine.setChannel(channel)
    }

    fun hasTask(): Boolean = engine.hasTask()

    fun check() {
        if (!installationPending()) engine.check()
    }

    fun download() {
        if (!installationPending()) engine.download()
    }

    fun cancel() {
        if (!installationPending()) engine.cancel()
    }

    @Throws(Exception::class) fun installableApk(): File = engine.installableApk()

    fun installation(): LiveData<InstallState> = installation

    fun installationPending(): Boolean = installation.value!!.pending()

    fun requestInstall() {
        val current = state().value
        if (cleared || installationPending() || current == null || current.busy) return
        if (current.apk == null || current.release == null) {
            installFailed(UiText.text("请先在当前通道下载并校验安装包"))
            return
        }
        installRelease = current.release
        installChannel = engine.channel()
        val request = ++installRequest
        installation.value = InstallState(true, null, null)
        verifyExecutor.execute {
            var apk: File? = null
            var error: String? = null
            try {
                apk = verifyApk.call()
            } catch (failure: Exception) {
                error = SensitiveData.redact(failure.message)
            }
            val result = apk
            val message = error
            main.post {
                if (cleared || request != installRequest) return@post
                installation.value =
                    InstallState(
                        false,
                        result,
                        if (result == null)
                            UiText.format("安装前校验失败：%s；请重试", message ?: UiText.text("无法读取安装包"))
                        else null,
                    )
            }
        }
    }

    /** 新 Activity 接手同一请求，且结果只领取一次；只有前台页面负责打开系统安装器。 */
    fun takeInstallReady(): File? {
        val current = installation.value!!
        if (cleared || current.verifying || current.apk == null) return null
        if (!engine.isCurrentInstall(current.apk, installRelease, installChannel)) {
            installFailed(UiText.text("通道或候选已改变，请重新检查后重试安装"))
            return null
        }
        installation.value = InstallState(false, null, null)
        return current.apk
    }

    fun installFailed(reason: String?) {
        installation.value = InstallState(false, null, SensitiveData.redact(reason))
    }

    fun restoreInterruptedInstall(wasPending: Boolean) {
        val current = installation.value!!
        if (wasPending && !current.pending() && current.error == null)
            installFailed(UiText.text("安装前校验已因进程重建中断，请点击「安装更新」重试"))
    }

    override fun onCleared() {
        cleared = true
        installRequest++
        super.onCleared()
    }

    companion object {
        const val FEED = UpdateEngine.FEED
    }
}
