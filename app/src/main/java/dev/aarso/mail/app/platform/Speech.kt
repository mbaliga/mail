package dev.aarso.mail.app.platform

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * The ear button: the briefing and each tl;dr read aloud with the platform's on-device voice.
 * [current] is the id of the line being spoken, so the list can light the row in step with it.
 */
class Speech(context: Context, private val _current: MutableStateFlow<String?> = MutableStateFlow(null)) {
    private val ready = CompletableDeferred<Boolean>()
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status -> ready.complete(status == TextToSpeech.SUCCESS) }

    val current: StateFlow<String?> = _current.asStateFlow()

    private var lastId: String? = null

    init {
        tts.setAudioAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
        )
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { if (utteranceId?.endsWith(".gap") != true) _current.value = utteranceId }
            override fun onDone(utteranceId: String?) { if (utteranceId == lastId) _current.value = null }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { if (utteranceId == lastId) _current.value = null }
            override fun onStop(utteranceId: String?, interrupted: Boolean) { _current.value = null }
        })
    }

    /** Speak [lines] (id to text) in order, replacing anything already playing. */
    suspend fun speak(lines: List<Pair<String, String>>, locale: Locale = Locale.getDefault()): Boolean {
        if (!ready.await() || lines.isEmpty()) return false
        if (tts.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE) tts.language = locale
        lastId = lines.last().first
        lines.forEachIndexed { i, (id, text) ->
            tts.speak(text, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, id)
            tts.playSilentUtterance(250, TextToSpeech.QUEUE_ADD, "$id.gap")
        }
        return true
    }

    val isSpeaking: Boolean get() = tts.isSpeaking

    fun stop() {
        tts.stop()
        _current.value = null
    }

    fun shutdown() = tts.shutdown()
}
