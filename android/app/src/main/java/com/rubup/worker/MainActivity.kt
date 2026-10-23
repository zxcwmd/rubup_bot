package com.rubup.worker

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.Button
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
    private lateinit var tokenInput: EditText
    private lateinit var adminInput: EditText
    private lateinit var usernameInput: EditText

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
            text = "RubUP Worker — Android 10+\nЗаполните настройки, сохраните и войдите в TikTok."
            textSize = 15f
            setTextColor(Color.rgb(17, 24, 39))
            setPadding(0, 0, 0, 12)
        }
        panel.addView(status)
        tokenInput = input("Telegram Bot Token", settings.telegramToken, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        adminInput = input("Ваш числовой Telegram ID", settings.adminIds.joinToString(","), InputType.TYPE_CLASS_NUMBER)
        usernameInput = input("Логин TikTok без @", settings.tiktokUsername, InputType.TYPE_CLASS_TEXT)
        panel.addView(tokenInput); panel.addView(adminInput); panel.addView(usernameInput)
        panel.addView(button("Сохранить и запустить службу") { saveAndStart() })
        panel.addView(button("Войти в TikTok в WebView") {
            runtime.open("https://www.tiktok.com/login")
            status.text = "Войдите в один аккаунт TikTok в окне ниже. После входа управляйте ботом с основного телефона."
        })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(button("▶ Авто") { startServiceAction(WorkerService.ACTION_AUTO) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(button("⛔ Стоп") { startServiceAction(WorkerService.ACTION_STOP) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        panel.addView(row)
        controls.addView(panel)
        root.addView(controls, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        webContainer = FrameLayout(this)
        root.addView(webContainer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
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
            settings.save(tokenInput.text.toString(), adminInput.text.toString(), usernameInput.text.toString())
            startServiceAction(WorkerService.ACTION_START)
            status.text = "Настройки сохранены. Служба запущена. Теперь войдите в TikTok и отправьте /start своему боту с основного телефона."
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
        runtime.attach(webContainer)
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
