package com.photosphere.downloader

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Extracts panorama IDs and GPS coordinates from Google Maps / Google Earth URLs.
 *
 * Supported URL formats:
 *  - https://www.google.com/maps/@lat,lng,3a,75y,heading,pitch/data=!3m...!1sPANO_ID!...
 *  - https://maps.app.goo.gl/SHORTCODE  (resolved via redirect)
 *  - https://goo.gl/maps/SHORTCODE
 *  - https://maps.google.com/?q=...
 *  - https://earth.google.com/web/@lat,lng,...
 */
object PanoIdExtractor {

    private const val TAG = "PanoIdExtractor"

    data class PanoInfo(
        val panoId: String,
        val latitude: Double? = null,
        val longitude: Double? = null
    )

    /**
     * Main entry point. Resolves short URLs first, then extracts pano info.
     */
    fun extract(rawUrl: String, client: OkHttpClient): PanoInfo? {
        val url = resolveUrl(rawUrl.trim(), client)
        Log.d(TAG, "Resolved URL: $url")

        return extractFromFullUrl(url)
    }

    /**
     * Follow redirects to resolve short URLs to full Google Maps URLs.
     */
    private fun resolveUrl(url: String, client: OkHttpClient): String {
        if (isShortUrl(url)) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .head()  // HEAD request to follow redirects cheaply
                    .build()
                val response = client.newCall(request).execute()
                val finalUrl = response.request.url.toString()
                response.close()
                Log.d(TAG, "Short URL resolved: $url -> $finalUrl")
                return finalUrl
            } catch (e: Exception) {
                Log.e(TAG, "Failed to resolve short URL: $url", e)
            }
        }
        return url
    }

    private fun isShortUrl(url: String): Boolean {
        return url.contains("maps.app.goo.gl") ||
               url.contains("goo.gl/maps") ||
               url.contains("g.co/") ||
               (url.contains("google.com") && url.length < 80)
    }

    /**
     * Extract pano ID and GPS from a full Google Maps / Earth URL.
     *
     * Google Maps data param structure uses !1s<PANO_ID> for photosphere IDs.
     * Coordinates appear in the @lat,lng portion of the path.
     */
    private fun extractFromFullUrl(url: String): PanoInfo? {
        val panoId = extractPanoId(url) ?: return null
        val (lat, lng) = extractCoordinates(url)
        return PanoInfo(panoId = panoId, latitude = lat, longitude = lng)
    }

    /**
     * Extract the panorama ID from the URL's data parameter.
     *
     * In Google Maps URLs, the panorama ID appears after "!1s" in the data segment.
     * Examples:
     *   !1sAF1QipNl3Kxyz123
     *   !1sPANORAMA_ID_HERE
     *
     * For Google Street View photospheres the ID is typically 22 chars.
     * User-contributed photospheres have longer IDs like "AF1Qip..."
     */
    private fun extractPanoId(url: String): String? {
        // Primary method: !1s<ID> in the data= segment
        val dataPattern = Regex("""!1s([A-Za-z0-9_\-]+)""")
        val match = dataPattern.find(url)
        if (match != null) {
            val id = match.groupValues[1]
            // Validate: pano IDs are at least 10 chars and don't look like numbers only
            if (id.length >= 10 && !id.all { it.isDigit() }) {
                Log.d(TAG, "Extracted pano ID (data param): $id")
                return id
            }
        }

        // Fallback: look for "panoid=" query parameter
        val panoIdParam = Regex("""[?&]panoid=([A-Za-z0-9_\-]+)""").find(url)
        if (panoIdParam != null) {
            val id = panoIdParam.groupValues[1]
            Log.d(TAG, "Extracted pano ID (panoid param): $id")
            return id
        }

        // Fallback: cbp= format (old Street View URLs)
        val cbpPattern = Regex("""cbp=(?:[^,]+,){4}([A-Za-z0-9_\-]{10,})""")
        val cbpMatch = cbpPattern.find(url)
        if (cbpMatch != null) {
            Log.d(TAG, "Extracted pano ID (cbp): ${cbpMatch.groupValues[1]}")
            return cbpMatch.groupValues[1]
        }

        Log.w(TAG, "Could not extract pano ID from URL: $url")
        return null
    }

    /**
     * Extract GPS coordinates from the URL's @lat,lng portion.
     */
    private fun extractCoordinates(url: String): Pair<Double?, Double?> {
        // Match @lat,lng in the path (Google Maps format)
        val coordPattern = Regex("""@(-?\d+\.?\d*),(-?\d+\.?\d*)""")
        val match = coordPattern.find(url)
        if (match != null) {
            val lat = match.groupValues[1].toDoubleOrNull()
            val lng = match.groupValues[2].toDoubleOrNull()
            if (lat != null && lng != null && lat in -90.0..90.0 && lng in -180.0..180.0) {
                Log.d(TAG, "Extracted coordinates: $lat, $lng")
                return Pair(lat, lng)
            }
        }

        // Fallback: ?ll=lat,lng or ?q=lat,lng
        val llPattern = Regex("""[?&](?:ll|q|center)=(-?\d+\.?\d*),(-?\d+\.?\d*)""")
        val llMatch = llPattern.find(url)
        if (llMatch != null) {
            val lat = llMatch.groupValues[1].toDoubleOrNull()
            val lng = llMatch.groupValues[2].toDoubleOrNull()
            if (lat != null && lng != null) {
                return Pair(lat, lng)
            }
        }

        return Pair(null, null)
    }
}
