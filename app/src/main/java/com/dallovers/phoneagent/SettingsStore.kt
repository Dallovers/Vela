package com.dallovers.phoneagent

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class ModelSettings(val endpoint: String = "", val model: String = "", val apiKey: String = "", val thinking: Boolean = true) {
    fun validate() {
        require(endpoint.isNotBlank()) { "请填写模型接口地址" }
        require(model.isNotBlank()) { "请填写模型名称" }
    }
}

data class ModelProfile(val id: String, val name: String, val settings: ModelSettings)

class SettingsStore(context: Context) {
    private val preferences = context.getSharedPreferences("model_settings", Context.MODE_PRIVATE)
    private val alias = "phone_agent_api_key"

    fun load(): ModelSettings {
        val encrypted = preferences.getString("key", "").orEmpty()
        val apiKey = if (encrypted.isBlank()) "" else runCatching { decrypt(encrypted) }.getOrDefault("")
        return ModelSettings(
            preferences.getString("endpoint", "").orEmpty(),
            preferences.getString("model", "").orEmpty(),
            apiKey,
            preferences.getBoolean("thinking", true),
        )
    }

    fun save(settings: ModelSettings) {
        val encrypted = if (settings.apiKey.isEmpty()) "" else encrypt(settings.apiKey)
        check(preferences.edit()
            .putString("endpoint", settings.endpoint.trim())
            .putString("model", settings.model.trim())
            .putString("key", encrypted)
            .putBoolean("thinking", settings.thinking)
            .commit()) { "模型配置保存失败，请重试" }
    }

    fun appearance(): String = preferences.getString("appearance", "system").orEmpty()
    fun setAppearance(value: String) {
        require(value in listOf("system", "light", "dark"))
        check(preferences.edit().putString("appearance", value).commit())
    }

    fun profiles(): List<ModelProfile> {
        val rows = org.json.JSONArray(preferences.getString("profiles", "[]"))
        return (0 until rows.length()).map { i -> rows.getJSONObject(i).let {
            ModelProfile(it.getString("id"), it.getString("name"), ModelSettings(it.getString("endpoint"), it.getString("model"),
                it.optString("key").let { key -> if (key.isBlank()) "" else decrypt(key) }, it.optBoolean("thinking", true)))
        } }
    }

    fun saveProfile(name: String, settings: ModelSettings) {
        require(name.isNotBlank() && name.trim().length <= 40) { "给这套配置起个名字，最多 40 字" }
        val existing = profiles()
        require(existing.size < 20 || existing.any { it.name == name.trim() }) { "最多保存 20 套配置" }
        val match = existing.firstOrNull { it.name == name.trim() }
        writeProfiles(existing.filterNot { it.id == match?.id } + ModelProfile(match?.id ?: java.util.UUID.randomUUID().toString(), name.trim(), settings))
    }
    fun removeProfile(id: String) = writeProfiles(profiles().filterNot { it.id == id })
    private fun writeProfiles(rows: List<ModelProfile>) {
        val json = org.json.JSONArray()
        rows.forEach { json.put(org.json.JSONObject().put("id", it.id).put("name", it.name)
            .put("endpoint", it.settings.endpoint.trim()).put("model", it.settings.model.trim())
            .put("thinking", it.settings.thinking).put("key", if (it.settings.apiKey.isBlank()) "" else encrypt(it.settings.apiKey))) }
        check(preferences.edit().putString("profiles", json.toString()).commit()) { "配置保存失败" }
    }

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
        }.generateKey()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + "." +
            Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String {
        val parts = value.split('.', limit = 2)
        require(parts.size == 2)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
        return cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)).toString(Charsets.UTF_8)
    }
}
