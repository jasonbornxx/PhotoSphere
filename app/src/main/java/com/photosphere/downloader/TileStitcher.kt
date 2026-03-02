package com.photosphere.downloader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Downloads Google Street View / photosphere tiles and stitches them into a
 * single equirectangular JPEG image.
 *
 * Google's tile API:
 *   https://streetviewpixels-pa.googleapis.com/v1/tile?cb_client=maps_sv.tactile
 *          &panoid=<ID>&x=<col>&y=<row>&zoom=<zoom>
 *
 * Zoom levels and resulting resolution:
 *   zoom=1 →  2 cols × 1 rows  →  512 ×  512  px (tiny)
 *   zoom=2 →  4 cols × 2 rows  → 2048 × 1024  px
 *   zoom=3 →  8 cols × 4 rows  → 4096 × 2048  px  ← default (good quality)
 *   zoom=4 → 16 cols × 8 rows  → 8192 × 4096  px  (very large ~40 MB)
 */
object TileStitcher {

    private const val TAG = "TileStitcher"
    private const val TILE_SIZE = 512
    private const val DEFAULT_ZOOM = 3

    // Tile grid dimensions per zoom level
    private val ZOOM_COLS = mapOf(1 to 2, 2 to 4, 3 to 8, 4 to 16)
    private val ZOOM_ROWS = mapOf(1 to 1, 2 to 2, 3 to 4, 4 to 8)

    private const val TILE_URL =
        "https://streetviewpixels-pa.googleapis.com/v1/tile?" +
        "cb_client=maps_sv.tactile&panoid=%s&x=%d&y=%d&zoom=%d"

    interface ProgressCallback {
        fun onProgress(downloaded: Int, total: Int)
        fun onStatusUpdate(message: String)
    }

    /**
     * Download all tiles for a given panorama ID and stitch them together.
     *
     * @param panoId  The Google panorama ID
     * @param zoom    Zoom level (1–4). Default 3 gives 4096×2048 which is a
     *                good balance of quality vs. download size / memory.
     * @return        Stitched equirectangular Bitmap, or null on failure
     */
    fun stitch(
        panoId: String,
        client: OkHttpClient,
        zoom: Int = DEFAULT_ZOOM,
        progress: ProgressCallback? = null
    ): Bitmap? {
        val cols = ZOOM_COLS[zoom] ?: 8
        val rows = ZOOM_ROWS[zoom] ?: 4
        val totalTiles = cols * rows
        val outputWidth = cols * TILE_SIZE
        val outputHeight = rows * TILE_SIZE

        Log.d(TAG, "Stitching $cols×$rows tiles → ${outputWidth}×${outputHeight}px")
        progress?.onStatusUpdate("Preparing ${outputWidth}×${outputHeight}px canvas…")

        // Create the output canvas
        val output: Bitmap
        try {
            output = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
        } catch (oom: OutOfMemoryError) {
            Log.e(TAG, "Out of memory creating output bitmap. Try a lower zoom level.")
            progress?.onStatusUpdate("Out of memory — try lowering zoom level")
            return null
        }

        val canvas = Canvas(output)
        var downloadedCount = 0

        for (row in 0 until rows) {
            for (col in 0 until cols) {
                val url = TILE_URL.format(panoId, col, row, zoom)
                val tile = downloadTile(url, client)

                if (tile != null) {
                    val x = (col * TILE_SIZE).toFloat()
                    val y = (row * TILE_SIZE).toFloat()
                    canvas.drawBitmap(tile, x, y, null)
                    tile.recycle()
                } else {
                    Log.w(TAG, "Tile ($col,$row) download failed — leaving black")
                }

                downloadedCount++
                progress?.onProgress(downloadedCount, totalTiles)
            }
        }

        return output
    }

    private fun downloadTile(url: String, client: OkHttpClient): Bitmap? {
        return try {
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                Log.w(TAG, "Tile HTTP ${response.code}: $url")
                response.close()
                return null
            }

            val bytes = response.body?.bytes()
            response.close()

            bytes?.let {
                BitmapFactory.decodeByteArray(it, 0, it.size)
            }
        } catch (e: IOException) {
            Log.e(TAG, "Tile download IOException: $url", e)
            null
        }
    }
}
