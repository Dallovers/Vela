package com.dallovers.phoneagent

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Toast
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.Locale

internal class ReplySpeech(context: Context) {
    private val app = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    var speakingId by mutableStateOf<String?>(null)
        private set
    private var ready = false
    private var closed = false
    private var token = 0
    private var engine: TextToSpeech? = null
    init {
        engine = TextToSpeech(app) { status ->
            handler.post {
                if (!closed && status == TextToSpeech.SUCCESS) {
                    val supported = engine?.setLanguage(Locale.CHINA)
                    ready = supported != null && supported >= TextToSpeech.LANG_AVAILABLE
                }
            }
        }
        engine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) = Unit
            override fun onDone(id: String?) { handler.post { if (id == "$token:last") speakingId = null } }
            @Deprecated("Legacy platform callback")
            override fun onError(id: String?) { handler.post {
                if (id?.startsWith("$token:") == true) { stop(); message("朗读失败，请检查系统语音设置") }
            } }
        })
    }
    fun toggle(id: String, text: String) {
        if (closed) return
        if (speakingId == id) { stop(); return }
        if (!ready) { message("系统中文语音尚未就绪，请检查文字转语音设置"); return }
        stop()
        speakingId = id
        val chunks = text.chunked((TextToSpeech.getMaxSpeechInputLength() - 1).coerceAtLeast(1))
        chunks.forEachIndexed { index, chunk ->
            val utterance = "$token:" + if (index == chunks.lastIndex) "last" else index.toString()
            if (engine?.speak(chunk, TextToSpeech.QUEUE_ADD, null, utterance) == TextToSpeech.ERROR) {
                stop(); message("朗读失败，请检查系统语音设置"); return
            }
        }
    }
    fun stop() { token++; engine?.stop(); speakingId = null }
    fun close() { closed = true; stop(); engine?.shutdown(); engine = null }
    private fun message(text: String) { Toast.makeText(app, text, Toast.LENGTH_SHORT).show() }
}

@Composable
internal fun rememberReplySpeech(sessionId: String?): ReplySpeech {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val speech = remember(context, lifecycle) { ReplySpeech(context) }
    DisposableEffect(speech, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) speech.stop() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); speech.close() }
    }
    DisposableEffect(sessionId) { onDispose { speech.stop() } }
    return speech
}
