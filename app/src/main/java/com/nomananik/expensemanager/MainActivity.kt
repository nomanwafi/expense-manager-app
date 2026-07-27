package com.nomananik.expensemanager

import android.Manifest
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.nomananik.expensemanager.databinding.ActivityMainBinding
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var cameraImageUri: Uri? = null
    private var pendingDownloadId: Long = -1L
    private var siteUrl: String = ""

    companion object {
        const val EXTRA_WEBSITE_URL = "extra_website_url"
        const val EXTRA_ANNOUNCEMENT_TITLE = "extra_announcement_title"
        const val EXTRA_ANNOUNCEMENT_MESSAGE = "extra_announcement_message"
        const val EXTRA_SOFT_UPDATE_MESSAGE = "extra_soft_update_message"
        const val EXTRA_UPDATE_URL = "extra_update_url"
    }

    // ---- Activity result launchers (must be registered before STARTED) ----

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = filePathCallback
        filePathCallback = null
        if (callback == null) return@registerForActivityResult

        val results: Array<Uri>? = if (result.resultCode == RESULT_OK) {
            val data = result.data
            val singleUri = data?.data
            val clipData = data?.clipData
            when {
                singleUri != null -> arrayOf(singleUri)
                clipData != null -> Array(clipData.itemCount) { i -> clipData.getItemAt(i).uri }
                cameraImageUri != null -> arrayOf(cameraImageUri!!)
                else -> null
            }
        } else null

        callback.onReceiveValue(results)
        cameraImageUri = null
    }

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(this, R.string.permission_denied_camera, Toast.LENGTH_SHORT).show()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* no-op: notifications are best-effort */ }

    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(this, R.string.permission_denied_storage, Toast.LENGTH_SHORT).show()
        }
    }

    // ---- Download-complete receiver ----

    private val downloadCompleteReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            if (id == -1L) return
            handleDownloadComplete(id)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)

        // The website URL normally comes from SplashActivity after checking remote_config.json,
        // so it can be changed at any time without releasing a new app update. Falls back to the
        // built-in default if MainActivity is ever launched directly (e.g. from a shortcut).
        siteUrl = intent.getStringExtra(EXTRA_WEBSITE_URL)?.takeIf { it.isNotBlank() }
            ?: getString(R.string.web_url)

        setupWebView()
        setupSwipeRefresh()
        setupBackPress()
        setupRetryButton()
        requestNotificationPermissionIfNeeded()
        registerDownloadReceiver()
        showAnnouncementIfPresent()
        showSoftUpdateIfPresent()

        if (isNetworkAvailable()) {
            loadSite()
        } else {
            showNoInternet()
        }
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(downloadCompleteReceiver)
        } catch (_: IllegalArgumentException) {
            // already unregistered
        }
        binding.webView.destroy()
        super.onDestroy()
    }

    // ---------------------------------------------------------------------
    // WebView setup
    // ---------------------------------------------------------------------

    private fun setupWebView() {
        val webView = binding.webView
        val settings: WebSettings = webView.settings

        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.mediaPlaybackRequiresUserGesture = false

        // Security hardening
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        @Suppress("DEPRECATION")
        settings.allowFileAccessFromFileURLs = false
        @Suppress("DEPRECATION")
        settings.allowUniversalAccessFromFileURLs = false
        settings.setSupportMultipleWindows(false)
        settings.javaScriptCanOpenWindowsAutomatically = false

        // Session persistence via cookies + DOM/local storage
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        // Hardware acceleration (also declared in the manifest)
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val url = request.url.toString()
                val scheme = request.url.scheme ?: ""
                return if (scheme == "http" || scheme == "https") {
                    false // keep navigation inside the WebView
                } else {
                    // tel:, mailto:, intent:, whatsapp:, upi:, etc — hand off to the OS
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, request.url))
                    } catch (_: Exception) {
                        // No app can handle it, ignore silently
                    }
                    true
                }
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                binding.progressBar.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                binding.progressBar.visibility = View.GONE
                binding.swipeRefresh.isRefreshing = false
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                super.onReceivedError(view, request, error)
                if (request.isForMainFrame) {
                    showNoInternet()
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {

            override fun onProgressChanged(view: WebView, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                binding.progressBar.progress = newProgress
                if (newProgress >= 100) {
                    binding.progressBar.visibility = View.GONE
                }
            }

            override fun onShowFileChooser(
                webView: WebView,
                filePathCb: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams
            ): Boolean {
                // Cancel any pending previous callback
                filePathCallback?.onReceiveValue(null)
                filePathCallback = filePathCb

                val contentIntent = fileChooserParams.createIntent().apply {
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    addCategory(Intent.CATEGORY_OPENABLE)
                    if (type == null) type = "*/*"
                }

                val initialIntents = mutableListOf<Intent>()
                createCameraCaptureIntent()?.let { initialIntents.add(it) }

                val chooser = Intent(Intent.ACTION_CHOOSER).apply {
                    putExtra(Intent.EXTRA_INTENT, contentIntent)
                    putExtra(Intent.EXTRA_TITLE, "Select or capture a file")
                    if (initialIntents.isNotEmpty()) {
                        putExtra(Intent.EXTRA_INITIAL_INTENTS, initialIntents.toTypedArray())
                    }
                }

                return try {
                    fileChooserLauncher.launch(chooser)
                    true
                } catch (e: Exception) {
                    this@MainActivity.filePathCallback = null
                    Toast.makeText(this@MainActivity, "No app available to open file picker", Toast.LENGTH_SHORT).show()
                    false
                }
            }
        }

        // File download support
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            startDownload(url, userAgent, contentDisposition, mimeType)
        }
    }

    private fun loadSite() {
        hideNoInternet()
        binding.webView.loadUrl(siteUrl)
    }

    private fun showAnnouncementIfPresent() {
        val title = intent.getStringExtra(EXTRA_ANNOUNCEMENT_TITLE)?.takeIf { it.isNotBlank() }
        val message = intent.getStringExtra(EXTRA_ANNOUNCEMENT_MESSAGE)?.takeIf { it.isNotBlank() }
        if (message == null) return

        AlertDialog.Builder(this)
            .setTitle(title ?: getString(R.string.announcement_default_title))
            .setMessage(message)
            .setPositiveButton(R.string.ok, null)
            .setCancelable(true)
            .show()
    }

    private fun showSoftUpdateIfPresent() {
        val message = intent.getStringExtra(EXTRA_SOFT_UPDATE_MESSAGE)?.takeIf { it.isNotBlank() }
        val updateUrl = intent.getStringExtra(EXTRA_UPDATE_URL)?.takeIf { it.isNotBlank() }
        if (message == null) return

        val snackbar = com.google.android.material.snackbar.Snackbar.make(
            binding.root, message, com.google.android.material.snackbar.Snackbar.LENGTH_INDEFINITE
        )
        if (updateUrl != null) {
            snackbar.setAction(R.string.update_now) {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(updateUrl)))
                } catch (_: Exception) {
                    // No app can handle it, ignore silently
                }
            }
        }
        snackbar.show()
    }

    // ---------------------------------------------------------------------
    // Camera capture for uploads
    // ---------------------------------------------------------------------

    private fun createCameraCaptureIntent(): Intent? {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            // Ask for permission now; camera option will be offered on the next attempt.
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            return null
        }
        return try {
            val photoFile = createTempImageFile()
            val uri = FileProvider.getUriForFile(
                this, "$packageName.fileprovider", photoFile
            )
            cameraImageUri = uri
            Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_OUTPUT, uri)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
        } catch (e: IOException) {
            null
        }
    }

    private fun createTempImageFile(): File {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val dir = File(cacheDir, "captures").apply { if (!exists()) mkdirs() }
        return File(dir, "IMG_$timestamp.jpg")
    }

    // ---------------------------------------------------------------------
    // Downloads
    // ---------------------------------------------------------------------

    private fun registerDownloadReceiver() {
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(downloadCompleteReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(downloadCompleteReceiver, filter)
        }
    }

    private fun startDownload(url: String, userAgent: String, contentDisposition: String, mimeType: String?) {
        // Legacy devices (<= API 28) need the runtime storage permission for public Downloads dir.
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }

        try {
            val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
            val request = DownloadManager.Request(url.toUri()).apply {
                addRequestHeader("User-Agent", userAgent)
                CookieManager.getInstance().getCookie(url)?.let { cookie ->
                    addRequestHeader("cookie", cookie)
                }
                setMimeType(mimeType)
                setTitle(fileName)
                setDescription(getString(R.string.app_name))
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)
            }

            val downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            pendingDownloadId = downloadManager.enqueue(request)
            Toast.makeText(this, getString(R.string.download_started, fileName), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, R.string.download_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleDownloadComplete(downloadId: Long) {
        val downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val query = DownloadManager.Query().setFilterById(downloadId)
        val cursor = downloadManager.query(query) ?: return

        cursor.use {
            if (it.moveToFirst()) {
                val statusIdx = it.getColumnIndex(DownloadManager.COLUMN_STATUS)
                val uriIdx = it.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                val titleIdx = it.getColumnIndex(DownloadManager.COLUMN_TITLE)

                if (statusIdx >= 0 && it.getInt(statusIdx) == DownloadManager.STATUS_SUCCESSFUL && uriIdx >= 0) {
                    val localUri = it.getString(uriIdx)
                    val title = if (titleIdx >= 0) it.getString(titleIdx) else getString(R.string.download_complete_title)
                    showDownloadCompleteNotification(localUri, title, downloadId)
                }
            }
        }
    }

    private fun showDownloadCompleteNotification(localUriString: String, title: String, downloadId: Long) {
        val fileUri = Uri.parse(localUriString)
        val file = fileUri.path?.let { File(it) }

        val contentUri = if (file != null && file.exists()) {
            FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        } else {
            fileUri
        }

        val extension = MimeTypeMap.getFileExtensionFromUrl(localUriString)
        val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "*/*"

        val openIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(contentUri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val pendingIntent = android.app.PendingIntent.getActivity(
            this,
            downloadId.toInt(),
            openIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, ExpenseManagerApp.DOWNLOAD_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(getString(R.string.download_complete_title))
            .setContentText(title)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
        ) {
            NotificationManagerCompat.from(this).notify(downloadId.toInt(), notification)
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // ---------------------------------------------------------------------
    // Pull-to-refresh
    // ---------------------------------------------------------------------

    private fun setupSwipeRefresh() {
        binding.swipeRefresh.setColorSchemeResources(R.color.brand_primary)
        binding.swipeRefresh.setOnRefreshListener {
            if (isNetworkAvailable()) {
                binding.webView.reload()
            } else {
                binding.swipeRefresh.isRefreshing = false
                showNoInternet()
            }
        }
    }

    // ---------------------------------------------------------------------
    // Back button handling
    // ---------------------------------------------------------------------

    private fun setupBackPress() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.webView.canGoBack()) {
                    binding.webView.goBack()
                } else {
                    showExitDialog()
                }
            }
        })
    }

    private fun showExitDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.exit_dialog_title)
            .setMessage(R.string.exit_dialog_message)
            .setPositiveButton(R.string.yes) { _, _ -> finish() }
            .setNegativeButton(R.string.no) { dialog, _ -> dialog.dismiss() }
            .setCancelable(true)
            .show()
    }

    // ---------------------------------------------------------------------
    // Connectivity
    // ---------------------------------------------------------------------

    private fun setupRetryButton() {
        binding.btnRetry.setOnClickListener {
            if (isNetworkAvailable()) {
                loadSite()
            } else {
                Toast.makeText(this, R.string.no_internet_title, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun showNoInternet() {
        binding.layoutNoInternet.visibility = View.VISIBLE
        binding.swipeRefresh.visibility = View.GONE
        binding.progressBar.visibility = View.GONE
    }

    private fun hideNoInternet() {
        binding.layoutNoInternet.visibility = View.GONE
        binding.swipeRefresh.visibility = View.VISIBLE
    }
}
