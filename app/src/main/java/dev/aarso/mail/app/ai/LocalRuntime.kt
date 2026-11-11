package dev.aarso.mail.app.ai

import android.app.PendingIntent
import android.content.Context
import com.google.android.play.core.splitcompat.SplitCompat
import com.google.android.play.core.splitinstall.SplitInstallManagerFactory
import com.google.android.play.core.splitinstall.SplitInstallRequest
import com.google.android.play.core.splitinstall.SplitInstallStateUpdatedListener
import com.google.android.play.core.splitinstall.model.SplitInstallSessionStatus
import dev.aarso.mail.ai.Availability
import dev.aarso.mail.ai.EngineException
import dev.aarso.mail.ai.LlmEngine
import dev.aarso.mail.ai.LlmRequest
import dev.aarso.mail.ai.LlmResponse
import dev.aarso.mail.ai.LocalEngineFactory
import dev.aarso.mail.app.BuildConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * The runtime that executes downloaded local models (MediaPipe). It is not linked into the app:
 * in Play builds it is the on-demand module "ondevice", installed the first time a local model is
 * wanted; in every other build it is inside the base app. Either way it is found by name.
 */
class LocalRuntime(
    private val context: Context,
    /** Whether this build delivers the runtime as a separate module. */
    val delivered: Boolean = BuildConfig.SPLIT_ON_DEVICE,
    private val lookup: () -> LocalEngineFactory? = { defaultLookup() },
) {
    private val _status = MutableStateFlow<String?>(null)
    /** "Installing the on-device runtime · 40%" while Play fetches the module; null otherwise. */
    val status: StateFlow<String?> = _status.asStateFlow()

    private val _confirmations = MutableSharedFlow<PendingIntent>(extraBufferCapacity = 1)
    /** Play wants the person's OK for a large download (mobile data); the UI launches this. */
    val confirmations: SharedFlow<PendingIntent> = _confirmations.asSharedFlow()

    @Volatile private var factory: LocalEngineFactory? = null

    fun factory(): LocalEngineFactory? = factory ?: lookup()?.also { factory = it }

    val present: Boolean get() = factory() != null

    /** True once the runtime can be used, installing the module first if this build delivers it. */
    suspend fun ensure(): Boolean {
        if (present) return true
        if (!delivered) return false
        val manager = SplitInstallManagerFactory.create(context)
        if (MODULE !in manager.installedModules) {
            val ok = install(manager)
            if (!ok) return false
        }
        SplitCompat.install(context)
        return present
    }

    private suspend fun install(manager: com.google.android.play.core.splitinstall.SplitInstallManager): Boolean =
        suspendCancellableCoroutine { cont ->
            lateinit var listener: SplitInstallStateUpdatedListener
            fun done(ok: Boolean) {
                manager.unregisterListener(listener)
                _status.value = null
                if (cont.isActive) cont.resume(ok)
            }
            listener = SplitInstallStateUpdatedListener { st ->
                when (st.status()) {
                    SplitInstallSessionStatus.DOWNLOADING -> _status.value = "Installing the on-device runtime" +
                        if (st.totalBytesToDownload() > 0) " · ${(100 * st.bytesDownloaded() / st.totalBytesToDownload())}%" else ""
                    SplitInstallSessionStatus.INSTALLING -> _status.value = "Installing the on-device runtime"
                    SplitInstallSessionStatus.REQUIRES_USER_CONFIRMATION -> st.resolutionIntent()?.let { _confirmations.tryEmit(it) }
                    SplitInstallSessionStatus.INSTALLED -> done(true)
                    SplitInstallSessionStatus.FAILED, SplitInstallSessionStatus.CANCELED -> done(false)
                }
            }
            manager.registerListener(listener)
            _status.value = "Installing the on-device runtime"
            manager.startInstall(SplitInstallRequest.newBuilder().addModule(MODULE).build())
                .addOnFailureListener { done(false) }
            cont.invokeOnCancellation { manager.unregisterListener(listener); _status.value = null }
        }

    companion object {
        const val MODULE = "ondevice"
        /** Roughly what the module adds, for "this will download X MB" wording. */
        const val APPROX_BYTES = 27_000_000L

        private fun defaultLookup(): LocalEngineFactory? = try {
            Class.forName(LocalEngineFactory.IMPLEMENTATION).getDeclaredConstructor().newInstance() as LocalEngineFactory
        } catch (_: Throwable) {
            null // not installed (yet)
        }
    }
}

/**
 * The engine the router sees for local models. It holds no runtime itself: it asks [LocalRuntime]
 * for one, so the same code works whether the runtime is in the base app or still on Play.
 */
class LocalModelEngine(
    private val context: Context,
    private val runtime: LocalRuntime,
    private val path: () -> String?,
) : LlmEngine {
    override val id = "local"
    override val label = "Local model on this phone"
    override val onDevice = true
    override val maxInputTokens = 3_000
    override val supportsStructuredOutput = false

    private var inner: LlmEngine? = null

    private fun engine(): LlmEngine? = inner ?: runtime.factory()?.create(context, path)?.also { inner = it }

    override suspend fun availability(): Availability {
        if (path() == null) return Availability.Unavailable("No model chosen")
        val e = engine() ?: return if (runtime.delivered) Availability.Downloadable(LocalRuntime.APPROX_BYTES)
        else Availability.Unavailable("This build has no local-model runtime")
        return e.availability()
    }

    override suspend fun generate(request: LlmRequest): LlmResponse =
        (engine() ?: throw EngineException("The on-device runtime isn't installed", false)).generate(request)

    override fun stream(request: LlmRequest): Flow<String> = engine()?.stream(request) ?: super.stream(request)

    /** Release the loaded model (before deleting its file). */
    fun close() {
        (inner as? AutoCloseable)?.close()
        inner = null
    }
}
