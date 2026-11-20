package com.dallovers.phoneagent

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import androidx.compose.runtime.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.*

internal class ComposerDictation(private val context: Context, private val scope: CoroutineScope,
                                private val onEvent: (String, Int) -> Unit) {
    var phase by mutableStateOf("")
        private set
    var transcript by mutableStateOf("")
        private set
    val active get() = phase.isNotEmpty()
    var elapsedSeconds by mutableIntStateOf(0)
        private set
    private var job: Job? = null
    @Volatile private var finishing = false

    fun stop() { if (active) { finishing = true; phase = "正在转写…" } }
    fun cancel() {
        if (active) onEvent("voice_cancelled", elapsedSeconds)
        job?.cancel(); job = null; phase = ""; transcript = ""
    }

    @SuppressLint("MissingPermission")
    fun start(onText: (String) -> Unit, onError: (String) -> Unit) {
        if (active) return
        transcript = ""
        elapsedSeconds = 0
        finishing = false
        phase = "准备听写…"
        onEvent("voice_started", 0)
        job = scope.launch {
            try {
                val result = coroutineScope {
                    val samples = VoiceSamples()
                    val capture = async(Dispatchers.IO) { capture(samples) }
                    withContext(Dispatchers.IO) {
                        VoiceRecognition.open(context).use { recognizer ->
                            var lastSize = 0
                            var lastText = ""
                            while (!capture.isCompleted) {
                                ensureActive()
                                if (!finishing && samples.size() - lastSize >= 40000) {
                                    val snapshot = samples.snapshot()
                                    lastText = recognizer.decode(snapshot)
                                    lastSize = snapshot.size
                                    withContext(Dispatchers.Main) { transcript = lastText }
                                }
                                delay(100)
                            }
                            capture.await()
                            withContext(Dispatchers.Main) { phase = "正在转写…" }
                            val final = samples.snapshot()
                            ensureActive()
                            val text = if (final.size == lastSize) lastText else recognizer.decode(final)
                            ensureActive()
                            text
                        }
                    }
                }
                onEvent("voice_decoded", result.length)
                if (result.isBlank()) onError("没有听清，点麦克风再说一次") else onText(result)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                onEvent("voice_failed", elapsedSeconds)
                onError("听写未完成，请检查麦克风权限和可用空间后重试")
            }
            finally { if (job === coroutineContext[Job]) { phase = ""; transcript = ""; job = null } }
        }
    }
    @SuppressLint("MissingPermission")
    private suspend fun capture(samples: VoiceSamples) {
        val rate = VoiceRecognition.SAMPLE_RATE
        val size = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(size > 0) { "麦克风暂时不可用" }
        val mic = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(size, 16384))
        try {
            mic.startRecording()
            check(mic.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "麦克风被占用" }
            withContext(Dispatchers.Main) { if (!finishing) phase = "正在听…" }
            val block = ShortArray(1600)
            val deadline = SystemClock.elapsedRealtime() + VoiceRecognition.MAX_SECONDS * 1000L
            var drainUntil = Long.MAX_VALUE
            var lastSecond = -1
            while (SystemClock.elapsedRealtime() < minOf(deadline, drainUntil)) {
                currentCoroutineContext().ensureActive()
                if (finishing && drainUntil == Long.MAX_VALUE) drainUntil = SystemClock.elapsedRealtime() + 250
                val count = mic.read(block, 0, block.size, AudioRecord.READ_NON_BLOCKING)
                check(count >= 0) { "录音中断" }
                if (count == 0) {
                    if (finishing) break
                    delay(20)
                    continue
                }
                if (!samples.append(block, count)) break
                val second = samples.size() / rate
                if (second != lastSecond) {
                    lastSecond = second
                    withContext(Dispatchers.Main) { elapsedSeconds = second }
                }
            }
        } finally {
            if (mic.recordingState == AudioRecord.RECORDSTATE_RECORDING) runCatching { mic.stop() }
            mic.release()
        }
    }
}

internal data class VoiceInput(val recorder: ComposerDictation, val start: () -> Unit)

@Composable
internal fun rememberVoiceInput(owner: String, onText: (String) -> Unit, onError: (String) -> Unit,
                                onEvent: (String, Int) -> Unit): VoiceInput {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val recorder = remember(owner) { ComposerDictation(context.applicationContext, scope, onEvent) }
    val deliver by rememberUpdatedState(onText)
    val error by rememberUpdatedState(onError)
    var permissionOwner by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (permissionOwner == owner) {
            if (granted) recorder.start({ deliver(it) }, { error(it) })
            else error("开启 Vela 的麦克风权限后就能听写")
        }
        permissionOwner = null
    }
    DisposableEffect(owner, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) recorder.cancel() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); recorder.cancel() }
    }
    return VoiceInput(recorder) {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
            recorder.start({ deliver(it) }, { error(it) })
        else { permissionOwner = owner; permission.launch(Manifest.permission.RECORD_AUDIO) }
    }
}
