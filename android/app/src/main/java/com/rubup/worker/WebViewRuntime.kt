package com.rubup.worker

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class WebViewRuntime private constructor(private val appContext: Context) {
    private val main = Handler(Looper.getMainLooper())
    private var webView: WebView? = null
    private var pageLatch: CountDownLatch? = null
    private var pendingImage: Uri? = null
    private var imageDelivered = AtomicBoolean(false)
    private var manualFileCallback: ValueCallback<Array<Uri>>? = null

    @Volatile
    var lastNavigationError: String = ""
        private set

    var manualFileChooser: ((ValueCallback<Array<Uri>>) -> Unit)? = null

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView {
        val view = WebView(appContext)
        view.setBackgroundColor(Color.WHITE)
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadsImagesAutomatically = true
            allowFileAccess = false
            allowContentAccess = true
            mediaPlaybackRequiresUserGesture = false
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, false)
        view.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                pageLatch?.countDown()
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    lastNavigationError = error.description?.toString().orEmpty()
                    pageLatch?.countDown()
                }
            }
        }
        view.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: WebChromeClient.FileChooserParams,
            ): Boolean {
                val image = pendingImage
                if (image != null) {
                    pendingImage = null
                    filePathCallback.onReceiveValue(arrayOf(image))
                    imageDelivered.set(true)
                    return true
                }
                val launcher = manualFileChooser
                if (launcher != null) {
                    manualFileCallback = filePathCallback
                    launcher(filePathCallback)
                } else {
                    filePathCallback.onReceiveValue(null)
                }
                return true
            }
        }
        webView = view
        return view
    }

    private fun obtainOnMain(): WebView {
        check(Looper.myLooper() == Looper.getMainLooper()) { "WebView must be created on the main thread" }
        return webView ?: createWebView()
    }

    fun attach(container: ViewGroup) {
        check(Looper.myLooper() == Looper.getMainLooper())
        val view = obtainOnMain()
        (view.parent as? ViewGroup)?.removeView(view)
        if (view.parent == null) {
            container.addView(view, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
    }

    fun detach(container: ViewGroup) {
        check(Looper.myLooper() == Looper.getMainLooper())
        val view = webView ?: return
        if (view.parent === container) container.removeView(view)
    }

    fun open(url: String) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            obtainOnMain().loadUrl(url)
        } else {
            main.post { obtainOnMain().loadUrl(url) }
        }
    }

    fun navigate(url: String, timeoutSeconds: Long = 45) {
        val latch = CountDownLatch(1)
        main.post {
            lastNavigationError = ""
            pageLatch = latch
            obtainOnMain().loadUrl(url)
        }
        if (!latch.await(timeoutSeconds, TimeUnit.SECONDS)) {
            throw BotFailure("Таймаут загрузки TikTok: $url")
        }
        if (lastNavigationError.isNotBlank()) {
            throw BotFailure("Ошибка загрузки TikTok: $lastNavigationError")
        }
    }

    fun evaluate(script: String, timeoutSeconds: Long = 45): String {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Do not block the Android main thread" }
        val latch = CountDownLatch(1)
        var result = ""
        var failure: Throwable? = null
        main.post {
            try {
                obtainOnMain().evaluateJavascript(script) { raw ->
                    result = raw ?: "null"
                    latch.countDown()
                }
            } catch (error: Throwable) {
                failure = error
                latch.countDown()
            }
        }
        if (!latch.await(timeoutSeconds, TimeUnit.SECONDS)) {
            throw BotFailure("Таймаут операции WebView")
        }
        failure?.let { throw BotFailure("WebView: ${it.message ?: it.javaClass.simpleName}") }
        return result
    }

    fun prepareImage(uri: Uri) {
        imageDelivered.set(false)
        pendingImage = uri
    }

    fun awaitImageDelivered(timeoutSeconds: Long = 12) {
        val deadline = System.currentTimeMillis() + timeoutSeconds * 1000
        while (!imageDelivered.get()) {
            if (System.currentTimeMillis() >= deadline) {
                pendingImage = null
                throw ManualActionRequired("WebView не открыл выбор фото для комментария")
            }
            Thread.sleep(100)
        }
    }

    fun deliverManualFiles(uris: Array<Uri>?) {
        manualFileCallback?.onReceiveValue(uris)
        manualFileCallback = null
    }

    fun close() {
        val latch = CountDownLatch(1)
        main.post {
            pageLatch?.countDown()
            pageLatch = null
            pendingImage = null
            manualFileCallback?.onReceiveValue(null)
            manualFileCallback = null
            webView?.let { view ->
                (view.parent as? ViewGroup)?.removeView(view)
                view.stopLoading()
                view.clearHistory()
                view.removeAllViews()
                view.destroy()
            }
            webView = null
            latch.countDown()
        }
        if (Looper.myLooper() == Looper.getMainLooper()) return
        latch.await(10, TimeUnit.SECONDS)
    }

    companion object {
        @Volatile
        private var instance: WebViewRuntime? = null

        fun get(context: Context): WebViewRuntime = instance ?: synchronized(this) {
            instance ?: WebViewRuntime(context.applicationContext).also { instance = it }
        }
    }
}
