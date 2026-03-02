package com.photosphere.downloader

import android.util.Log
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.RandomAccessFile

/**
 * Injects photosphere (GPano) XMP metadata and GPS EXIF data into a JPEG file.
 *
 * The XMP is written as a JPEG APP1 segment with the XAP header:
 *   FF E1 <length> "http://ns.adobe.com/xap/1.0/\0" <xmp_xml>
 *
 * The GPano namespace fields tell Google Photos, Android Gallery, etc.
 * that this image is a 360° equirectangular photosphere.
 *
 * References:
 *   https://developers.google.com/streetview/spherical-metadata
 *   https://exiv2.org/tags-xmp-GPano.html
 */
object XmpInjector {

    private const val TAG = "XmpInjector"

    // JPEG markers
    private const val FF: Byte = 0xFF.toByte()
    private const val SOI: Byte = 0xD8.toByte()   // Start of Image
    private const val APP1: Byte = 0xE1.toByte()   // Application segment 1 (used by both EXIF and XMP)
    private const val APP0: Byte = 0xE0.toByte()
    private const val XAP_HEADER = "http://ns.adobe.com/xap/1.0/\u0000"

    /**
     * Builds the XMP XML packet for an equirectangular photosphere.
     *
     * @param imageWidth   Full width of the stitched image in pixels
     * @param imageHeight  Full height of the stitched image in pixels
     */
    private fun buildXmpPacket(imageWidth: Int, imageHeight: Int): String {
        return """<?xpacket begin="﻿" id="W5M0MpCehiHzreSzNTczkc9d"?>
<x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="Adobe XMP Core 5.1.0-jc003">
  <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
    <rdf:Description rdf:about=""
        xmlns:GPano="http://ns.google.com/photos/1.0/panorama/"
        xmlns:xmp="http://ns.adobe.com/xap/1.0/">
      <GPano:UsePanoramaViewer>True</GPano:UsePanoramaViewer>
      <GPano:CaptureSoftware>PhotoSphere Downloader</GPano:CaptureSoftware>
      <GPano:StitchingSoftware>PhotoSphere Downloader</GPano:StitchingSoftware>
      <GPano:ProjectionType>equirectangular</GPano:ProjectionType>
      <GPano:PoseHeadingDegrees>0.0</GPano:PoseHeadingDegrees>
      <GPano:InitialViewHeadingDegrees>0.0</GPano:InitialViewHeadingDegrees>
      <GPano:InitialViewPitchDegrees>0.0</GPano:InitialViewPitchDegrees>
      <GPano:InitialViewRollDegrees>0.0</GPano:InitialViewRollDegrees>
      <GPano:InitialHorizontalFOVDegrees>75.0</GPano:InitialHorizontalFOVDegrees>
      <GPano:CroppedAreaLeftPixels>0</GPano:CroppedAreaLeftPixels>
      <GPano:CroppedAreaTopPixels>0</GPano:CroppedAreaTopPixels>
      <GPano:CroppedAreaImageWidthPixels>$imageWidth</GPano:CroppedAreaImageWidthPixels>
      <GPano:CroppedAreaImageHeightPixels>$imageHeight</GPano:CroppedAreaImageHeightPixels>
      <GPano:FullPanoWidthPixels>$imageWidth</GPano:FullPanoWidthPixels>
      <GPano:FullPanoHeightPixels>$imageHeight</GPano:FullPanoHeightPixels>
      <GPano:FirstPhotoDate>2024-01-01T00:00:00.000Z</GPano:FirstPhotoDate>
      <GPano:LastPhotoDate>2024-01-01T00:00:00.000Z</GPano:LastPhotoDate>
      <GPano:SourcePhotosCount>1</GPano:SourcePhotosCount>
      <GPano:ExposureLockUsed>False</GPano:ExposureLockUsed>
    </rdf:Description>
  </rdf:RDF>
</x:xmpmeta>
<?xpacket end="w"?>"""
    }

    /**
     * Injects GPano XMP into a JPEG file in-place.
     * The XMP segment is inserted right after the SOI marker (FF D8).
     *
     * @param jpegFile    Path to the JPEG file to modify
     * @param imageWidth  Width of the image in pixels
     * @param imageHeight Height of the image in pixels
     * @return true on success
     */
    fun injectXmp(jpegFile: File, imageWidth: Int, imageHeight: Int): Boolean {
        return try {
            val xmpXml = buildXmpPacket(imageWidth, imageHeight)
            val header = XAP_HEADER.toByteArray(Charsets.UTF_8)
            val xmlBytes = xmpXml.toByteArray(Charsets.UTF_8)

            // APP1 segment content = header + xml
            val segmentContent = header + xmlBytes
            // APP1 segment length field = 2 bytes for length itself + content length
            val segmentLength = segmentContent.size + 2

            if (segmentLength > 65535) {
                Log.e(TAG, "XMP packet too large: $segmentLength bytes")
                return false
            }

            // Build APP1 segment bytes: FF E1 <length_hi> <length_lo> <content>
            val app1Segment = byteArrayOf(FF, APP1) +
                byteArrayOf(
                    ((segmentLength shr 8) and 0xFF).toByte(),
                    (segmentLength and 0xFF).toByte()
                ) +
                segmentContent

            // Read original file
            val original = jpegFile.readBytes()

            // Verify it starts with JPEG SOI (FF D8)
            if (original.size < 2 || original[0] != FF || original[1] != SOI) {
                Log.e(TAG, "File is not a valid JPEG")
                return false
            }

            // Find insertion point: right after SOI, before any existing APP segments
            // We insert our XMP before any existing APP0/APP1 markers
            val insertAt = 2 // right after FF D8

            // Write: SOI + XMP APP1 segment + rest of original file
            val newFile = original.copyOfRange(0, insertAt) +
                app1Segment +
                original.copyOfRange(insertAt, original.size)

            jpegFile.writeBytes(newFile)
            Log.d(TAG, "XMP injected: ${app1Segment.size} bytes added to ${jpegFile.name}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "XMP injection failed", e)
            false
        }
    }

    /**
     * Inject GPS EXIF data into the JPEG file using AndroidX ExifInterface.
     *
     * @param jpegFile  Path to the JPEG file
     * @param latitude  GPS latitude in decimal degrees
     * @param longitude GPS longitude in decimal degrees
     */
    fun injectGps(jpegFile: File, latitude: Double, longitude: Double): Boolean {
        return try {
            val exif = ExifInterface(jpegFile.absolutePath)

            // Set GPS location
            exif.setGpsInfo(
                android.location.Location("").apply {
                    this.latitude = latitude
                    this.longitude = longitude
                }
            )

            // Set other helpful EXIF fields
            exif.setAttribute(ExifInterface.TAG_MAKE, "Google")
            exif.setAttribute(ExifInterface.TAG_MODEL, "Street View")
            exif.setAttribute(ExifInterface.TAG_SOFTWARE, "PhotoSphere Downloader")
            exif.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, "Google Street View Photosphere")

            exif.saveAttributes()
            Log.d(TAG, "GPS EXIF injected: lat=$latitude, lng=$longitude")
            true
        } catch (e: Exception) {
            Log.e(TAG, "GPS EXIF injection failed", e)
            false
        }
    }
}
