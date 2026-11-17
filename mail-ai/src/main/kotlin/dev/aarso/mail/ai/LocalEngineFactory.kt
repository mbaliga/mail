package dev.aarso.mail.ai

/**
 * How the app obtains an on-device engine whose runtime is too big to ship in the base install
 * (MediaPipe's LLM runtime). The implementation lives in a separate module that may be delivered
 * on demand, so the app finds it by name instead of linking to it.
 *
 * [context] is an android.content.Context; this module has no Android dependency.
 */
interface LocalEngineFactory {
    fun create(context: Any, modelPath: () -> String?): LlmEngine

    companion object {
        /** The class the app looks for; present only when the runtime module is installed. */
        const val IMPLEMENTATION = "dev.aarso.mail.ondevice.MediaPipeFactory"
    }
}
