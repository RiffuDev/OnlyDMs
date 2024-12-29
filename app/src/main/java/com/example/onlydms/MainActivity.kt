package com.example.onlydms

import android.annotation.SuppressLint
import android.content.Intent
import android.content.UriPermission
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Parcelable
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.webkit.*
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import com.example.onlydms.utils.Logger
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {
    private val viewModel: MainViewModel by viewModels()
    private lateinit var webView: WebView
    private lateinit var backButtonOverlay: View
    private var snackbar: Snackbar? = null
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var cameraPhotoPath: String? = null

    companion object {
        private const val TAG = "MainActivity"
        private const val INSTAGRAM_LOGIN_URL = "https://www.instagram.com/accounts/login/"
        private const val INSTAGRAM_DIRECT_URL = "https://www.instagram.com/direct/inbox/"
        private const val INSTAGRAM_BASE_URL = "https://www.instagram.com/"
        private const val INSTAGRAM_DIRECT_THREAD_PATTERN = """https://www\.instagram\.com/direct/t/\d+.*"""
        private const val INPUT_FILE_REQUEST_CODE = 1
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
            allowFileAccess = true
            allowContentAccess = true
            mediaPlaybackRequiresUserGesture = false
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

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePathCallback

                try {
                    // Create camera intent
                    val takePictureIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                    var photoFile: File? = null

                    try {
                        photoFile = createImageFile()
                    } catch (ex: IOException) {
                        Logger.e(TAG, "Error creating image file", ex)
                    }

                    // Continue only if the File was successfully created
                    if (photoFile != null) {
                        cameraPhotoPath = "file:" + photoFile.absolutePath
                        val photoURI = FileProvider.getUriForFile(
                            this@MainActivity,
                            "${applicationContext.packageName}.fileprovider",
                            photoFile
                        )
                        takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, photoURI)
                    } else {
                        takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, null as Uri?)
                    }

                    // Create gallery intent
                    val contentSelectionIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "image/*"
                    }

                    // Create chooser intent
                    val chooserIntent = Intent(Intent.ACTION_CHOOSER).apply {
                        putExtra(Intent.EXTRA_INTENT, contentSelectionIntent)
                        putExtra(Intent.EXTRA_TITLE, "Select Image Source")
                        
                        // Only add camera intent if we successfully created the file
                        if (photoFile != null) {
                            putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf<Parcelable>(takePictureIntent))
                        }
                    }

                    startActivityForResult(chooserIntent, INPUT_FILE_REQUEST_CODE)
                    return true
                } catch (e: Exception) {
                    Logger.e(TAG, "File chooser error", e)
                    filePathCallback?.onReceiveValue(null)
                    this@MainActivity.filePathCallback = null
                    return false
                }
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

    private fun createImageFile(): File {
        // Create an image file name
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val imageFileName = "JPEG_${timeStamp}_"
        val storageDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        return File.createTempFile(
            imageFileName,  /* prefix */
            ".jpg",        /* suffix */
            storageDir     /* directory */
        ).apply {
            // Save a file: path for use with ACTION_VIEW intents
            cameraPhotoPath = absolutePath
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == INPUT_FILE_REQUEST_CODE) {
            filePathCallback?.let { callback ->
                val results = when {
                    resultCode != RESULT_OK -> null
                    data?.data != null -> arrayOf(data.data!!)
                    cameraPhotoPath != null -> arrayOf(Uri.parse("file://$cameraPhotoPath"))
                    else -> null
                }
                callback.onReceiveValue(results)
                filePathCallback = null
            }
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
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