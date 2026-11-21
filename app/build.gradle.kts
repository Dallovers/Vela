import java.net.URI
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.dallovers.phoneagent"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.dallovers.phoneagent"
        minSdk = 30
        targetSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = 25
        versionName = "0.3.8"
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

fun voiceHash(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val block = ByteArray(65536)
        while (true) {
            val count = input.read(block)
            if (count < 0) break
            digest.update(block, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
fun downloadVoiceFile(file: File, url: String, digest: String) {
    if (file.isFile && voiceHash(file) == digest) return
    file.parentFile.mkdirs()
    val temporary = File.createTempFile("voice-", ".download", file.parentFile)
    try {
        URI(url).toURL().openConnection().apply {
            connectTimeout = 30000
            readTimeout = 120000
        }.getInputStream().use { input -> temporary.outputStream().use { input.copyTo(it) } }
        check(voiceHash(temporary) == digest) { "Voice dependency failed SHA-256 verification" }
        temporary.copyTo(file, overwrite = true)
    } finally { temporary.delete() }
}
val prepareVoiceRuntime by tasks.registering {
    val library = rootProject.file("cache/voice/sherpa-onnx-1.12.30.aar")
    outputs.file(library)
    inputs.property("sha256", "0a44f3aa516c31eb74cef0ea6bbacf76095434bf6c89be728226c7ecf550232f")
    doLast { downloadVoiceFile(library,
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.12.30/sherpa-onnx-1.12.30.aar",
        "0a44f3aa516c31eb74cef0ea6bbacf76095434bf6c89be728226c7ecf550232f") }
}

dependencies {
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    implementation(files(prepareVoiceRuntime))
    androidTestImplementation("com.alphacephei:vosk-android:0.3.75")
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

// The model is installed from app assets, so listening does not depend on network access.
val voiceAssets = layout.buildDirectory.dir("generated/voice-assets")
val prepareVoiceModel by tasks.registering {
    val destination = voiceAssets.map { it.dir("sensevoice") }
    val archive = rootProject.file("cache/voice/sensevoice-int8.tar.bz2")
    inputs.property("modelArchiveSha256", "7d1efa2138a65b0b488df37f8b89e3d91a60676e416f515b952358d83dfd347e")
    outputs.dir(destination)
    doLast {
        voiceAssets.get().file("mandarin-0.22.zip").asFile.delete()
        downloadVoiceFile(archive,
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2",
            "7d1efa2138a65b0b488df37f8b89e3d91a60676e416f515b952358d83dfd347e")
        copy {
            from(tarTree(resources.bzip2(archive)))
            include("**/model.int8.onnx", "**/tokens.txt", "**/LICENSE", "**/README.md")
            eachFile { relativePath = org.gradle.api.file.RelativePath(true, name) }
            includeEmptyDirs = false
            into(destination)
        }
        check(voiceHash(destination.get().file("model.int8.onnx").asFile) == "c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51")
        check(voiceHash(destination.get().file("tokens.txt").asFile) == "f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc")
    }
}
android.sourceSets.getByName("main").assets.srcDir(voiceAssets)
tasks.named("preBuild") { dependsOn(prepareVoiceModel) }
