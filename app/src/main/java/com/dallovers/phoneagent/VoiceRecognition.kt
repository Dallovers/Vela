package com.dallovers.phoneagent

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.security.MessageDigest

/** One decoder per dictation. The model and audio remain on the phone. */
internal class VoiceRecognition private constructor(private val recognizer: OfflineRecognizer) : AutoCloseable {
    fun decode(samples: FloatArray): String {
        if (samples.size < 1600 || samples.all { kotlin.math.abs(it) < .0005f }) return ""
        val stream = recognizer.createStream()
        return try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            recognizer.decode(stream)
            recognizer.getResult(stream).text.replace(Regex("<\\|[^|]+\\|>"), "").trim()
        } finally { stream.release() }
    }

    override fun close() { recognizer.release() }

    companion object {
        const val SAMPLE_RATE = 16000
        const val MAX_SECONDS = 60
        private val installLock = Mutex()
        private val files = mapOf(
            "model.int8.onnx" to "c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51",
            "tokens.txt" to "f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc",
        )

        suspend fun open(context: Context): VoiceRecognition {
            val directory = installLock.withLock {
                val installed = File(context.noBackupFilesDir, "sensevoice-int8-2024-07-17")
                val marker = File(installed, "verified.txt")
                val version = files.entries.joinToString("\n") { "${it.key} ${it.value}" }
                val complete = marker.isFile && marker.readText() == version &&
                    File(installed, "model.int8.onnx").length() == 239233841L &&
                    File(installed, "tokens.txt").length() == 315894L
                if (!complete) {
                    installed.mkdirs()
                    marker.delete()
                    for ((name, expected) in files) {
                        currentCoroutineContext().ensureActive()
                        val stage = File.createTempFile("voice-", ".part", installed)
                        try {
                            val hash = MessageDigest.getInstance("SHA-256")
                            context.assets.open("sensevoice/$name").use { input ->
                                stage.outputStream().use { output ->
                                    val block = ByteArray(65536)
                                    while (true) {
                                        currentCoroutineContext().ensureActive()
                                        val count = input.read(block)
                                        if (count < 0) break
                                        output.write(block, 0, count)
                                        hash.update(block, 0, count)
                                    }
                                    output.fd.sync()
                                }
                            }
                            check(hash.digest().joinToString("") { "%02x".format(it) } == expected) { "语音模型校验失败" }
                            java.nio.file.Files.move(stage.toPath(), File(installed, name).toPath(),
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                        } finally { stage.delete() }
                    }
                    marker.writeText(version)
                }
                installed
            }
            currentCoroutineContext().ensureActive()
            val config = OfflineRecognizerConfig(modelConfig = OfflineModelConfig(
                senseVoice = OfflineSenseVoiceModelConfig(model = File(directory, "model.int8.onnx").absolutePath,
                    language = "auto", useInverseTextNormalization = true),
                tokens = File(directory, "tokens.txt").absolutePath, numThreads = 2, provider = "cpu",
            ))
            return VoiceRecognition(OfflineRecognizer(config = config))
        }
    }
}

/** Capture never waits for decoding, so a slow preview cannot drop incoming audio. */
internal class VoiceSamples {
    private val samples = FloatArray(VoiceRecognition.SAMPLE_RATE * VoiceRecognition.MAX_SECONDS)
    private var count = 0
    @Synchronized fun append(block: ShortArray, length: Int): Boolean {
        require(length in 0..block.size)
        val take = minOf(length, samples.size - count)
        for (i in 0 until take) samples[count + i] = block[i] / 32768f
        count += take
        return count < samples.size
    }
    @Synchronized fun size() = count
    @Synchronized fun snapshot() = samples.copyOf(count)
}
