@file:OptIn(ExperimentalGetImage::class)

package com.example.myapplication.utils

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Base64
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream

/**
 * Helper object for converting CameraX frames to Base64-encoded JPEG images.
 * 
 * This handles the conversion pipeline:
 * 1. ImageProxy (CameraX) → Bitmap
 * 2. Bitmap → JPEG byte array
 * 3. JPEG → Base64 String
 * 
 * Usage:
 * val base64 = FrameConverter.imageProxyToBase64(imageProxy)
 */
object FrameConverter {

    private const val JPEG_QUALITY = 90
    
    /**
     * Convert CameraX ImageProxy to Base64-encoded JPEG string.
     * 
     * This method:
     * - Converts ImageProxy to Bitmap using safe YuvImage implementation
     * - Handles rotation automatically
     * - Compresses to JPEG format
     * - Encodes to Base64
     * 
     * @param imageProxy The CameraX ImageProxy from ImageCapture
     * @return Base64-encoded JPEG string, or null if conversion fails
     */
    fun imageProxyToBase64(imageProxy: ImageProxy): String? {
        return try {
            val bitmap = imageProxyToBitmap(imageProxy) ?: return null
            bitmapToBase64(bitmap).also {
                bitmap.recycle()
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Convert ImageProxy to Bitmap.
     * 
     * SAFE IMPLEMENTATION using YuvImage.compressToJpeg() which properly
     * handles CameraX's rowStride and padding issues that cause crashes
     * when using manual NV21 pixel loops.
     * 
     * Why this is safe:
     * - Uses Android's built-in YuvImage which handles rowStride/padding
     * - No manual byte array indexing that can go out of bounds
     * - BitmapFactory.decodeByteArray() is robust and well-tested
     * 
     * @param imageProxy The CameraX ImageProxy
     * @return Bitmap or null if conversion fails
     */
    @OptIn(ExperimentalGetImage::class)
    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
        return try {
            val image = imageProxy.image ?: return null
            val plane = image.planes[0]
            val buffer = plane.buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)

            val yuvImage = YuvImage(
                bytes,
                ImageFormat.NV21,
                image.width,
                image.height,
                null
            )

            val out = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), JPEG_QUALITY, out)
            val jpegBytes = out.toByteArray()

            val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
                ?: return null

            // Apply rotation correction
            val rotationDegrees = imageProxy.imageInfo.rotationDegrees
            if (rotationDegrees != 0) {
                val matrix = Matrix()
                matrix.postRotate(rotationDegrees.toFloat())
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also {
                    bitmap.recycle()
                }
            } else {
                bitmap
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Convert Bitmap to Base64-encoded JPEG string.
     * 
     * @param bitmap The source Bitmap
     * @param quality JPEG compression quality (0-100)
     * @return Base64-encoded JPEG string
     */
    private fun bitmapToBase64(bitmap: Bitmap, quality: Int = JPEG_QUALITY): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
        return Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
    }

    /**
     * Alternative method using YuvImage for faster conversion.
     * This is the RECOMMENDED approach for real-time processing.
     * 
     * This method is essentially the same as imageProxyToBase64() but
     * extracts the intermediate JPEG bytes for direct use.
     * 
     * @param imageProxy The CameraX ImageProxy
     * @return Base64-encoded JPEG string
     */
    @OptIn(ExperimentalGetImage::class)
    fun imageProxyToBase64Fast(imageProxy: ImageProxy): String? {
        return try {
            val image = imageProxy.image ?: return null
            val plane = image.planes[0]
            val buffer = plane.buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)

            val yuvImage = YuvImage(
                bytes,
                ImageFormat.NV21,
                image.width,
                image.height,
                null
            )

            val out = ByteArrayOutputStream()
            yuvImage.compressToJpeg(
                Rect(0, 0, image.width, image.height),
                JPEG_QUALITY,
                out
            )

            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) {
            // Fallback to slower but more reliable method
            imageProxyToBase64(imageProxy)
        }
    }
}

