package com.rubup.worker

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var settings: SettingsStore
    private lateinit var runtime: WebViewRuntime
    private lateinit var webContainer: FrameLayout
    private lateinit var status: TextView
    private lateinit var diagnostic: TextView
    private lateinit var tokenInput: EditText
    private lateinit var adminInput: EditText
    private lateinit var usernameInput: EditText
    private lateinit var modeInput: CheckBox
    private lateinit var nativePanel: LinearLayout
    private lateinit var webLoginButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = SettingsStore(this)
        runtime = WebViewRuntime.get(this)
        buildUi()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(249, 250, 251))
        }
        val controls = ScrollView(this)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 18, 24, 18)
        }
        status = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.rgb(17, 24, 39))
            setPadding(0, 0, 0, 12)
        }
        panel.addView(status)
        diagnostic = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.rgb(109, 40, 117))
            setPadding(0, 0, 0, 12)
        }
        panel.addView(diagnostic)
        tokenInput = input("Telegram Bot Token", settings.telegramToken, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        adminInput = input("Ваш числовой Telegram ID", settings.adminIds.joinToString(","), InputType.TYPE_CLASS_NUMBER)
        usernameInput = input("Логин TikTok без @", settings.tiktokUsername, InputType.TYPE_CLASS_TEXT)
        panel.addView(tokenInput); panel.addView(adminInput); panel.addView(usernameInput)
        modeInput = CheckBox(this).apply {
            text = "Работать в установленном приложении TikTok"
            isChecked = settings.useInstalledTikTok
            setOnCheckedChangeListener { _, checked ->
                settings.useInstalledTikTok = checked
                updateMode()
            }
        }
        panel.addView(modeInput)
        panel.addView(button("Сохранить и запустить службу") { saveAndStart() })
        panel.addView(button("Проверить Telegram: токен и сеть") { checkTelegram() })
        nativePanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        nativePanel.addView(button("1. Включить Accessibility для RubUP") { openAccessibilitySettings() })
        nativePanel.addView(button("2. Открыть установленный TikTok") { openInstalledTikTok() })
        panel.addView(nativePanel)
        webLoginButton = button("Войти в TikTok через WebView (резерв)") {
            runtime.open("https://www.tiktok.com/login")
            status.text = "Резервный WebView: войдите в один аккаунт TikTok. Для installed-режима включите переключатель выше."
        }
        panel.addView(webLoginButton)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(button("▶ Авто") { startServiceAction(WorkerService.ACTION_AUTO) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(button("⛔ Стоп") { startServiceAction(WorkerService.ACTION_STOP) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        panel.addView(row)
        controls.addView(panel)
        root.addView(controls, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        webContainer = FrameLayout(this)
        root.addView(webContainer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        updateMode()
    }

    private fun updateMode() {
        val installed = modeInput.isChecked
        nativePanel.visibility = if (installed) View.VISIBLE else View.GONE
        webLoginButton.visibility = if (installed) View.GONE else View.VISIBLE
        webContainer.visibility = if (installed) View.GONE else View.VISIBLE
        if (installed) {
            runtime.detach(webContainer)
            status.text = "RubUP Worker — установленное приложение TikTok\n" +
                if (TikTokAccessibilityService.isReady()) {
                    "Accessibility подключена. Войдите в TikTok, сохраните настройки и отправьте /start боту."
                } else {
                    "Сначала нажмите «Включить Accessibility», найдите RubUP Worker в системном списке и разрешите доступ."
                }
        } else {
            status.text = "Резервный режим WebView. Для работы в установленном TikTok включите переключатель выше."
        }
        updateDiagnostic()
    }

    private fun updateDiagnostic() {
        if (::diagnostic.isInitialized) {
            diagnostic.text = settings.telegramDiagnostic.ifBlank { "Telegram-диагностика пока не запускалась." }
        }
    }

    private fun input(hint: String, value: String, type: Int): EditText = EditText(this).apply {
        this.hint = hint
        this.setText(value)
        this.inputType = type
        this.setSingleLine(true)
        this.setPadding(12, 8, 12, 8)
    }

    private fun button(text: String, action: () -> Unit): Button = Button(this).apply {
        this.text = text
        this.textSize = 13f
        this.setOnClickListener { action() }
    }

    private fun saveAndStart() {
        try {
            settings.useInstalledTikTok = modeInput.isChecked
            settings.save(tokenInput.text.toString(), adminInput.text.toString(), usernameInput.text.toString())
            startServiceAction(WorkerService.ACTION_START)
            settings.telegramDiagnostic = "Служба запущена; подключаю Telegram…"
            updateDiagnostic()
            status.text = if (modeInput.isChecked) {
                "Настройки сохранены. Включите Accessibility, войдите в установленный TikTok и отправьте /start боту."
            } else {
                "Настройки сохранены. Войдите в TikTok в WebView и отправьте /start боту."
            }
        } catch (error: Exception) {
            status.text = "Ошибка настроек или запуска службы: ${error.message}"
            settings.telegramDiagnostic = status.text.toString()
            updateDiagnostic()
            Toast.makeText(this, error.message, Toast.LENGTH_LONG).show()
        }
    }

    private fun checkTelegram() {
        if (!settings.isConfigured) {
            status.text = "Сначала заполните все три поля и нажмите «Сохранить и запустить службу»."
            return
        }
        val token = settings.telegramToken
        settings.telegramDiagnostic = "Проверяю Telegram Bot API…"
        updateDiagnostic()
        Thread {
            try {
                val me = TelegramClient(settings).api("getMe")
                val username = me.optString("username").ifBlank { "бот без username" }
                runOnUiThread {
                    settings.telegramDiagnostic = "Telegram отвечает: @$username. Если в чате тишина, проверьте числовой ID и запуск службы."
                    updateDiagnostic()
                }
            } catch (error: Exception) {
                val detail = (error.message ?: error.javaClass.simpleName).replace(token, "[токен скрыт]")
                runOnUiThread {
                    settings.telegramDiagnostic = "Ошибка Telegram: $detail"
                    updateDiagnostic()
                }
            }
        }.start()
    }

    private fun openAccessibilitySettings() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, "В списке выберите RubUP Worker и включите доступ", Toast.LENGTH_LONG).show()
        } catch (error: Exception) {
            Toast.makeText(this, error.message, Toast.LENGTH_LONG).show()
        }
    }

    private fun openInstalledTikTok() {
        try {
            val packageName = TikTokAppLauncher.open(this)
            Toast.makeText(this, "Найден TikTok: $packageName", Toast.LENGTH_LONG).show()
        } catch (error: Exception) {
            Toast.makeText(this, error.message, Toast.LENGTH_LONG).show()
        }
    }

    private fun startServiceAction(action: String) {
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 41)
        }
        val intent = Intent(this, WorkerService::class.java).setAction(action)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
    }

    override fun onResume() {
        super.onResume()
        runtime.manualFileChooser = { openImagePicker() }
        if (::modeInput.isInitialized) {
            updateMode()
            if (!modeInput.isChecked) runtime.attach(webContainer)
        }
    }

    override fun onPause() {
        runtime.detach(webContainer)
        runtime.manualFileChooser = null
        super.onPause()
    }

    private fun openImagePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        startActivityForResult(intent, FILE_REQUEST)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == FILE_REQUEST) {
            val uri = if (resultCode == RESULT_OK) data?.data else null
            runtime.deliverManualFiles(uri?.let { arrayOf(it) })
        }
    }

    companion object {
        private const val FILE_REQUEST = 73
    }
}
