package com.photosphere.downloader

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Orchestrates the full photosphere download pipeline:
 *   URL → Pano ID extraction → Tile download + stitching → XMP/EXIF injection → Save
 */
class PanoDownloader(private val context: Context) {

    companion object {
        private const val TAG = "PanoDownloader"
        private const val JPEG_QUALITY = 95
    }

    sealed class Result {
        data class Success(val uri: Uri, val fileName: String) : Result()
        data class Error(val message: String) : Result()
    }

    sealed class Progress {
        data class Status(val message: String) : Progress()
        data class TileProgress(val downloaded: Int, val total: Int) : Progress()
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    suspend fun download(
        rawUrl: String,
        zoom: Int = 3,
        onProgress: (Progress) -> Unit = {}
    ): Result = withContext(Dispatchers.IO) {

        // Step 1: Extract panorama info from URL
        onProgress(Progress.Status("Parsing URL…"))
        val panoInfo = PanoIdExtractor.extract(rawUrl, httpClient)
            ?: return@withContext Result.Error(
                "Could not extract a panorama ID from this URL.\n\n" +
                "Please share a Google Maps or Google Earth photosphere link.\n" +
                "The URL should contain a street view or photo sphere."
            )

        Log.d(TAG, "PanoInfo: id=${panoInfo.panoId} lat=${panoInfo.latitude} lng=${panoInfo.longitude}")

        // Step 2: Download and stitch tiles
        onProgress(Progress.Status("Downloading tiles (zoom=$zoom)…"))
        val bitmap = TileStitcher.stitch(
            panoId = panoInfo.panoId,
            client = httpClient,
            zoom = zoom,
            progress = object : TileStitcher.ProgressCallback {
                override fun onProgress(downloaded: Int, total: Int) {
                    onProgress(Progress.TileProgress(downloaded, total))
                }
                override fun onStatusUpdate(message: String) {
                    onProgress(Progress.Status(message))
                }
            }
        ) ?: return@withContext Result.Error(
            "Failed to download panorama tiles.\n" +
            "Pano ID: ${panoInfo.panoId}\n\n" +
            "The panorama may be unavailable or the ID may be incorrect."
        )

        // Step 3: Save the stitched bitmap as a JPEG
        onProgress(Progress.Status("Saving image…"))
        val fileName = buildFileName(panoInfo.panoId)
        val tempFile = File(context.cacheDir, fileName)

        try {
            FileOutputStream(tempFile).use { fos ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, fos)
            }
            bitmap.recycle()
        } catch (e: Exception) {
            bitmap.recycle()
            return@withContext Result.Error("Failed to save image: ${e.message}")
        }

        // Step 4: Inject XMP photosphere metadata
        onProgress(Progress.Status("Injecting photosphere XMP metadata…"))
        val imageWidth = TileStitcher.let {
            val cols = mapOf(1 to 2, 2 to 4, 3 to 8, 4 to 16)[zoom] ?: 8
            cols * 512
        }
        val imageHeight = imageWidth / 2

        val xmpOk = XmpInjector.injectXmp(tempFile, imageWidth, imageHeight)
        if (!xmpOk) {
            Log.w(TAG, "XMP injection failed — image saved without photosphere metadata")
        }

        // Step 5: Inject GPS EXIF if coordinates are available
        if (panoInfo.latitude != null && panoInfo.longitude != null) {
            onProgress(Progress.Status("Injecting GPS location…"))
            XmpInjector.injectGps(tempFile, panoInfo.latitude, panoInfo.longitude)
        }

        // Step 6: Copy to MediaStore (Pictures/PhotoSpheres)
        onProgress(Progress.Status("Saving to gallery…"))
        val savedUri = saveToMediaStore(tempFile, fileName)
            ?: return@withContext Result.Error("Failed to save to gallery.")

        // Clean up temp file
        tempFile.delete()

        Log.d(TAG, "Download complete: $savedUri")
        Result.Success(uri = savedUri, fileName = fileName)
    }

    /**
     * Save the processed JPEG from the cache dir to the device's Pictures folder
     * using MediaStore so it appears in the gallery.
     */
    private fun saveToMediaStore(sourceFile: File, fileName: String): Uri? {
        return try {
            val relPath = "${Environment.DIRECTORY_PICTURES}/PhotoSpheres"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, relPath)
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }

                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: return null

                resolver.openOutputStream(uri)?.use { out ->
                    sourceFile.inputStream().use { it.copyTo(out) }
                }

                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)

                uri
            } else {
                // Android 9 and below: save directly to filesystem
                val picturesDir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    "PhotoSpheres"
                )
                picturesDir.mkdirs()

                val destFile = File(picturesDir, fileName)
                sourceFile.copyTo(destFile, overwrite = true)

                // Notify MediaStore
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DATA, destFile.absolutePath)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                }
                context.contentResolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "MediaStore save failed", e)
            null
        }
    }

    private fun buildFileName(panoId: String): String {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val shortId = panoId.take(8)
        return "photosphere_${shortId}_${ts}.jpg"
    }
}
