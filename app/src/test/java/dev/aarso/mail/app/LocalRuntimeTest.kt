package dev.aarso.mail.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import dev.aarso.mail.ai.AiTask
import dev.aarso.mail.ai.Availability
import dev.aarso.mail.ai.EngineException
import dev.aarso.mail.ai.LlmEngine
import dev.aarso.mail.ai.LlmRequest
import dev.aarso.mail.ai.LlmResponse
import dev.aarso.mail.ai.LocalEngineFactory
import dev.aarso.mail.app.ai.LocalModelEngine
import dev.aarso.mail.app.ai.LocalRuntime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LocalRuntimeTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    private val fake = object : LocalEngineFactory {
        override fun create(context: Any, modelPath: () -> String?) = object : LlmEngine {
            override val id = "local"; override val label = "fake"; override val onDevice = true
            override val maxInputTokens = 100; override val supportsStructuredOutput = false
            override suspend fun availability() = Availability.Ready
            override suspend fun generate(request: LlmRequest) = LlmResponse("hello ${modelPath()}", id, true)
        }
    }
    private val request = LlmRequest(AiTask.SUMMARIZE, "s", "p")

    @Test fun noModelChosenIsUnavailableWhateverTheRuntime() = runBlocking {
        val e = LocalModelEngine(context, LocalRuntime(context, delivered = true, lookup = { fake })) { null }
        assertTrue(e.availability() is Availability.Unavailable)
    }

    @Test fun playBuildWithoutTheModuleOffersItAsADownload() = runBlocking {
        val e = LocalModelEngine(context, LocalRuntime(context, delivered = true, lookup = { null })) { "/m.task" }
        assertEquals(Availability.Downloadable(LocalRuntime.APPROX_BYTES), e.availability())
        try { e.generate(request); error("should not run") } catch (x: EngineException) { assertTrue(!x.retryable) }
    }

    @Test fun buildWithoutAnyRuntimeSaysSo() = runBlocking {
        val e = LocalModelEngine(context, LocalRuntime(context, delivered = false, lookup = { null })) { "/m.task" }
        assertTrue(e.availability() is Availability.Unavailable)
    }

    @Test fun onceTheRuntimeIsThereTheEngineDelegates() = runBlocking {
        var installed = false
        val e = LocalModelEngine(context, LocalRuntime(context, delivered = true, lookup = { if (installed) fake else null })) { "/m.task" }
        assertTrue(e.availability() is Availability.Downloadable)
        installed = true // the module arrives while the app is running
        assertEquals(Availability.Ready, e.availability())
        assertEquals("hello /m.task", e.generate(request).text)
    }
}
