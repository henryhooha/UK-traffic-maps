package uk.roadahead.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/** Text-to-speech that ducks music/podcasts while speaking, like Waze. */
class Speaker(ctx: Context) {
    private val am = ctx.getSystemService(AudioManager::class.java)
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attrs).build()
    private var ready = false
    private val tts: TextToSpeech = TextToSpeech(ctx.applicationContext) { status ->
        if (status == TextToSpeech.SUCCESS) {
            ready = true
            tts.language = Locale.UK
            tts.setAudioAttributes(attrs)
        }
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { if (!tts.isSpeaking) am.abandonAudioFocusRequest(focus) }
            @Deprecated("Deprecated in Java") override fun onError(id: String?) { am.abandonAudioFocusRequest(focus) }
        })
    }

    fun speak(text: String, urgent: Boolean = false) {
        if (!ready || text.isBlank()) return
        am.requestAudioFocus(focus)
        tts.speak(text, if (urgent) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, System.nanoTime().toString())
    }

    fun shutdown() { tts.stop(); tts.shutdown() }
}
