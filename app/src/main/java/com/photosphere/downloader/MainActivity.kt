package com.photosphere.downloader

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.photosphere.downloader.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var downloader: PanoDownloader

    // Permission launcher for storage (Android < 10)
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val allGranted = grants.values.all { it }
        if (allGranted) {
            pendingUrl?.let { startDownload(it) }
        } else {
            showError("Storage permission is required to save images.")
        }
        pendingUrl = null
    }

    private var pendingUrl: String? = null
    private var isDownloading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        downloader = PanoDownloader(this)

        setupUI()
        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun setupUI() {
        binding.btnDownload.setOnClickListener {
            val url = binding.etUrl.text.toString().trim()
            if (url.isEmpty()) {
                binding.etUrl.error = "Please enter a Google Maps or Google Earth URL"
                return@setOnClickListener
            }
            initiateDownload(url)
        }

        binding.btnPaste.setOnClickListener {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val text = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
            if (!text.isNullOrBlank()) {
                binding.etUrl.setText(text)
                showToast("Pasted from clipboard")
            } else {
                showToast("Clipboard is empty")
            }
        }

        // Zoom level radio buttons
        binding.rgZoom.setOnCheckedChangeListener { _, checkedId ->
            val hint = when (checkedId) {
                R.id.rbZoom2 -> "2048 × 1024 px — fast download"
                R.id.rbZoom3 -> "4096 × 2048 px — recommended"
                R.id.rbZoom4 -> "8192 × 4096 px — very large, may be slow"
                else -> ""
            }
            binding.tvZoomHint.text = hint
        }
        binding.rbZoom3.isChecked = true
        binding.tvZoomHint.text = "4096 × 2048 px — recommended"
    }

    /**
     * Handle intents from Google Maps / Google Earth share button or VIEW action.
     */
    private fun handleIncomingIntent(intent: Intent?) {
        intent ?: return

        val url = when (intent.action) {
            Intent.ACTION_SEND -> {
                // Shared text from Google Maps (Share → copy link or share directly)
                intent.getStringExtra(Intent.EXTRA_TEXT)
            }
            Intent.ACTION_VIEW -> {
                // Opened directly via URL (browser or app link)
                intent.data?.toString()
            }
            else -> null
        }

        if (!url.isNullOrBlank()) {
            binding.etUrl.setText(url)
            // Small delay to let UI settle before auto-starting download
            binding.root.postDelayed({ initiateDownload(url) }, 300)
        }
    }

    private fun initiateDownload(url: String) {
        if (isDownloading) {
            showToast("Already downloading…")
            return
        }

        // Request storage permission on Android 9 and below
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val writePermission = Manifest.permission.WRITE_EXTERNAL_STORAGE
            if (ContextCompat.checkSelfPermission(this, writePermission)
                != PackageManager.PERMISSION_GRANTED
            ) {
                pendingUrl = url
                permissionLauncher.launch(arrayOf(writePermission))
                return
            }
        }

        startDownload(url)
    }

    private fun startDownload(url: String) {
        val zoom = when (binding.rgZoom.checkedRadioButtonId) {
            R.id.rbZoom2 -> 2
            R.id.rbZoom4 -> 4
            else -> 3
        }

        isDownloading = true
        setUiState(downloading = true)
        binding.progressBar.progress = 0
        binding.tvStatus.text = "Starting…"
        binding.tvResult.visibility = View.GONE

        lifecycleScope.launch {
            val result = downloader.download(
                rawUrl = url,
                zoom = zoom,
                onProgress = { progress ->
                    runOnUiThread { handleProgress(progress) }
                }
            )

            isDownloading = false
            setUiState(downloading = false)

            runOnUiThread {
                when (result) {
                    is PanoDownloader.Result.Success -> onDownloadSuccess(result)
                    is PanoDownloader.Result.Error   -> onDownloadError(result)
                }
            }
        }
    }

    private fun handleProgress(progress: PanoDownloader.Progress) {
        when (progress) {
            is PanoDownloader.Progress.Status -> {
                binding.tvStatus.text = progress.message
            }
            is PanoDownloader.Progress.TileProgress -> {
                val pct = (progress.downloaded * 100) / progress.total.coerceAtLeast(1)
                binding.progressBar.progress = pct
                binding.tvStatus.text = "Downloading tiles: ${progress.downloaded}/${progress.total}"
            }
        }
    }

    private fun onDownloadSuccess(result: PanoDownloader.Result.Success) {
        binding.tvStatus.text = "✅ Download complete!"
        binding.tvResult.visibility = View.VISIBLE
        binding.tvResult.text = "Saved: ${result.fileName}\n\n" +
            "Find it in your Gallery → Albums → PhotoSpheres.\n" +
            "Open with Google Photos for full 360° panorama view."

        // Offer to view the image
        binding.btnViewImage.visibility = View.VISIBLE
        binding.btnViewImage.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(result.uri, "image/jpeg")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Open with…"))
        }
    }

    private fun onDownloadError(result: PanoDownloader.Result.Error) {
        binding.tvStatus.text = "❌ Download failed"
        binding.tvResult.visibility = View.VISIBLE
        binding.tvResult.text = result.message
        binding.btnViewImage.visibility = View.GONE
    }

    private fun setUiState(downloading: Boolean) {
        binding.btnDownload.isEnabled = !downloading
        binding.btnPaste.isEnabled = !downloading
        binding.etUrl.isEnabled = !downloading
        binding.rgZoom.isEnabled = !downloading
        binding.progressBar.visibility = if (downloading) View.VISIBLE else View.GONE
        binding.tvStatus.visibility = if (downloading) View.VISIBLE else View.VISIBLE
    }

    private fun showError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
