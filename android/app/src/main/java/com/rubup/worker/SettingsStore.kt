package com.rubup.worker

import android.content.Context
import android.content.SharedPreferences

class SettingsStore(context: Context) {
    private val prefs: SharedPreferences = context.applicationContext
        .getSharedPreferences("worker-settings", Context.MODE_PRIVATE)

    var telegramToken: String
        get() = prefs.getString("telegram_token", "").orEmpty()
        set(value) = prefs.edit().putString("telegram_token", value.trim()).apply()

    var adminIds: Set<Long>
        get() = prefs.getStringSet("admin_ids", emptySet()).orEmpty().mapNotNull { it.toLongOrNull() }.toSet()
        set(value) = prefs.edit().putStringSet("admin_ids", value.map(Long::toString).toSet()).apply()

    var tiktokUsername: String
        get() = prefs.getString("tiktok_username", "").orEmpty()
        set(value) = prefs.edit().putString("tiktok_username", value.trim().removePrefix("@").lowercase()).apply()

    var useInstalledTikTok: Boolean
        get() = prefs.getBoolean("use_installed_tiktok", true)
        set(value) = prefs.edit().putBoolean("use_installed_tiktok", value).apply()

    var telegramDiagnostic: String
        get() = prefs.getString("telegram_diagnostic", "").orEmpty()
        set(value) = prefs.edit().putString("telegram_diagnostic", value.take(1000)).apply()

    var tiktokDiagnostic: String
        get() = prefs.getString("tiktok_diagnostic", "").orEmpty()
        set(value) = prefs.edit().putString("tiktok_diagnostic", value.take(1500)).apply()

    var autoEnabled: Boolean
        get() = prefs.getBoolean("auto_enabled", false)
        set(value) = prefs.edit().putBoolean("auto_enabled", value).apply()

    var tagCursor: Int
        get() = prefs.getInt("tag_cursor", 0)
        set(value) = prefs.edit().putInt("tag_cursor", value).apply()

    var tags: List<String>
        get() = prefs.getString("tags", null)?.split(',')?.filter(String::isNotBlank) ?: Defaults.TAGS
        set(value) = prefs.edit().putString("tags", value.joinToString(",")).apply()

    val isConfigured: Boolean
        get() = telegramToken.contains(":") && adminIds.isNotEmpty() && tiktokUsername.isNotBlank()

    fun validate(): String? = when {
        !telegramToken.contains(":") -> "Введите токен Telegram от @BotFather"
        adminIds.isEmpty() -> "Введите числовой Telegram ID администратора"
        tiktokUsername.isBlank() -> "Введите точный логин TikTok: он нужен для своих комментариев и лайков"
        else -> null
    }

    fun save(token: String, adminRaw: String, username: String) {
        val ids = adminRaw.split(',', ' ', '\n').mapNotNull { it.trim().toLongOrNull() }.toSet()
        if (ids.isEmpty()) throw IllegalArgumentException("ADMIN_USER_IDS должен содержать числовой Telegram ID")
        if (!token.trim().contains(":")) throw IllegalArgumentException("Проверьте токен Telegram")
        if (username.isBlank()) throw IllegalArgumentException("Введите логин TikTok")
        telegramToken = token
        adminIds = ids
        tiktokUsername = username
    }
}
