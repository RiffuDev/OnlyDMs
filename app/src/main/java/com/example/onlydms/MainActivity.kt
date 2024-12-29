package com.example.onlydms

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.view.View
import android.webkit.*
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import com.example.onlydms.utils.Logger

class MainActivity : AppCompatActivity() {
    private val viewModel: MainViewModel by viewModels()
    private lateinit var webView: WebView
    private lateinit var backButtonOverlay: View
    private var snackbar: Snackbar? = null

    companion object {
        private const val TAG = "MainActivity"
        private const val INSTAGRAM_LOGIN_URL = "https://www.instagram.com/accounts/login/"
        private const val INSTAGRAM_DIRECT_URL = "https://www.instagram.com/direct/inbox/"
        private const val INSTAGRAM_BASE_URL = "https://www.instagram.com/"
        private const val INSTAGRAM_DIRECT_THREAD_PATTERN = """https://www\.instagram\.com/direct/t/\d+.*"""
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        try {
            webView = findViewById(R.id.webView)
            backButtonOverlay = findViewById(R.id.backButtonOverlay)
            setupWebView()
            observeLoginState()
            
            webView.loadUrl(INSTAGRAM_LOGIN_URL)
        } catch (e: Exception) {
            Log.e(TAG, "Error in onCreate", e)
            showError("Failed to initialize app")
        }
    }

    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val url = request?.url?.toString() ?: return false
                Logger.d(TAG, "shouldOverrideUrlLoading URL: $url")
                updateBackButtonOverlay(url)
                return handleUrlNavigation(url)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                try {
                    Logger.d(TAG, "onPageFinished URL: $url")
                    updateBackButtonOverlay(url)
                    
                    // Inject URL change monitoring JavaScript
                    view?.evaluateJavascript(
                        """
                        (function() {
                            // Monitor URL changes
                            let lastUrl = window.location.href;
                            const urlObserver = new MutationObserver(function() {
                                if (lastUrl !== window.location.href) {
                                    lastUrl = window.location.href;
                                    console.log('URL changed to: ' + lastUrl);
                                    AndroidInterface.onUrlChanged(lastUrl);
                                }
                            });
                            
                            urlObserver.observe(document.body, {
                                childList: true,
                                subtree: true
                            });
                            
                            // Check login state
                            if (document.querySelector('input[name="username"]') === null && 
                                window.location.pathname !== '/accounts/login/') {
                                return true;
                            }
                            return false;
                        })();
                        """.trimIndent()
                    ) { result ->
                        val isLoggedIn = result.equals("true", ignoreCase = true)
                        Log.d(TAG, "Login state check: $isLoggedIn")
                        if (isLoggedIn && !viewModel.isLoggedIn.value) {
                            viewModel.setLoggedIn(true)
                        }
                    }
                } catch (e: Exception) {
                    Logger.e(TAG, "Error in onPageFinished", e)
                }
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                Logger.d(TAG, "doUpdateVisitedHistory URL: $url, isReload: $isReload")
                url?.let { updateBackButtonOverlay(it) }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                Logger.e(TAG, "WebView error: ${error?.description}")
                showError("Failed to load page")
            }
        }

        // Add JavaScript interface to handle navigation
        webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun onUrlChanged(url: String) {
                Log.d(TAG, "JavaScript reported URL change: $url")
                runOnUiThread {
                    updateBackButtonOverlay(url)
                    handleUrlNavigation(url)
                }
            }
        }, "AndroidInterface")
    }

    private fun handleUrlNavigation(url: String): Boolean {
        return try {
            if (viewModel.isLoggedIn.value) {
                if (!url.startsWith(INSTAGRAM_DIRECT_URL) && 
                    !url.contains("/direct/") && 
                    url.startsWith(INSTAGRAM_BASE_URL)) {
                    showRestrictionMessage()
                    webView.loadUrl(INSTAGRAM_DIRECT_URL)
                    true
                } else {
                    false
                }
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in handleUrlNavigation", e)
            false
        }
    }

    private fun observeLoginState() {
        lifecycleScope.launch {
            try {
                viewModel.isLoggedIn.collectLatest { isLoggedIn ->
                    if (isLoggedIn) {
                        // Inject JavaScript to monitor navigation
                        webView.evaluateJavascript(
                            """
                            (function() {
                                const observer = new MutationObserver(function(mutations) {
                                    if (window.location.pathname !== '/direct/inbox/' && 
                                        !window.location.pathname.startsWith('/direct/')) {
                                        window.location.href = '${INSTAGRAM_DIRECT_URL}';
                                    }
                                });
                                observer.observe(document.body, {
                                    childList: true,
                                    subtree: true
                                });
                            })();
                            """.trimIndent(),
                            null
                        )
                        webView.loadUrl(INSTAGRAM_DIRECT_URL)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in observeLoginState", e)
            }
        }
    }

    private fun showRestrictionMessage() {
        showMessage("Only Direct Messages are accessible in this app")
    }

    private fun showError(message: String) {
        showMessage(message)
    }

    private fun showMessage(message: String) {
        snackbar?.dismiss()
        snackbar = Snackbar.make(
            webView,
            message,
            Snackbar.LENGTH_SHORT
        ).apply { show() }
    }

    private fun updateBackButtonOverlay(url: String?) {
        url?.let {
            Logger.d(TAG, "updateBackButtonOverlay called with URL: $it")

            val isMainInbox = isMainInboxUrl(it)
            Logger.d(TAG, "Is main inbox: $isMainInbox")
            
            backButtonOverlay.visibility = if (isMainInbox) {
                View.VISIBLE
            } else {
                View.GONE
            }
        }
    }

    private fun isMainInboxUrl(url: String): Boolean {
        val mainInboxUrls = setOf(
            "https://www.instagram.com/direct/inbox",
            "https://www.instagram.com/direct/inbox/",
            INSTAGRAM_DIRECT_URL,
            "${INSTAGRAM_DIRECT_URL}/",
            "https://instagram.com/direct/inbox",
            "https://instagram.com/direct/inbox/"
        )
        
        // Check if it's a thread URL (individual chat)
        if (url.contains("/direct/t/")) {
            return false
        }
        
        return url in mainInboxUrls || url.endsWith("/direct/inbox/") || url.endsWith("/direct/inbox")
    }

    override fun onBackPressed() {
        try {
            if (webView.canGoBack() && !viewModel.isLoggedIn.value) {
                webView.goBack()
            } else {
                super.onBackPressed()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in onBackPressed", e)
            super.onBackPressed()
        }
    }
}