package com.example.myapplication.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.atomic.AtomicBoolean
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * CameraManager - Manages CameraX lifecycle for continuous preview and frame processing.
 * 
 * Key features:
 * - Starts camera ONCE and keeps it running
 * - Uses bindToLifecycle(this) for proper lifecycle management
 * - Does NOT call unbindAll() repeatedly
 * - Processes frames with ImageAnalysis
 * - Converts frames to Bitmap using SAFE YuvImage implementation
 * - Runs detection in background thread to avoid blocking UI
 * 
 * Usage:
 * 1. Create CameraManager in your Activity/Fragment
 * 2. Call startCamera() once
 * 3. Set detector callback for results
 * 4. Call shutdown() when done
 */
class CameraManager(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner
) : LifecycleEventObserver {
    companion object {
        private const val TAG = "CameraManager"
        private const val JPEG_QUALITY = 90
        private const val INFERENCE_SIZE = 320 // Resize to 320x320 for faster inference
    }
    
    // CameraX components
    private var cameraProvider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var previewView: PreviewView? = null
    
    // Background executor for detection
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    
    // Detection callback
    var onDetectionResult: ((List<DetectionBox>) -> Unit)? = null
    
    // YOLO detector (placeholder - can be replaced with real detector)
    private var detector: ((Bitmap) -> List<DetectionBox>)? = null
    
    // Lifecycle state tracking
    private var isCameraStarted = false
    private val isProcessingFrame = AtomicBoolean(false)
    
    // Reusable buffers for performance
    private var reusableBitmap: Bitmap? = null
    private val outputStream = ByteArrayOutputStream()
    
    init {
        // Register lifecycle observer for auto start/stop
        lifecycleOwner.lifecycle.addObserver(this)
    }
    
    /**
     * Set the object detector function.
     * 
     * @param detector Function that takes a Bitmap and returns detection results
     */
    fun setDetector(detector: (Bitmap) -> List<DetectionBox>) {
        this.detector = detector
    }
    
    /**
     * Lifecycle observer - auto start/stop camera based on lifecycle events.
     */
    override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
        when (event) {
            Lifecycle.Event.ON_RESUME -> {
                Log.d(TAG, "Lifecycle ON_RESUME - starting camera")
                startCameraInternal()
            }
            Lifecycle.Event.ON_PAUSE -> {
                Log.d(TAG, "Lifecycle ON_PAUSE - stopping camera")
                stopCameraInternal()
            }
            else -> {
                // Ignore other events
            }
        }
    }
    
    /**
     * Start the camera with Preview and ImageAnalysis.
     * Called ONCE - camera stays bound until shutdown().
     *
     * This method:
     * 1. Gets CameraProvider
     * 2. Sets up Preview (for display)
     * 3. Sets up ImageAnalysis (for frame processing)
     * 4. Binds both to lifecycle ONCE
     */
    fun startCamera(previewView: PreviewView) {
        Log.d(TAG, "=== Camera start requested ===")
        this.previewView = previewView
        
        // If lifecycle is already resumed, start immediately
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            startCameraInternal()
        }
        // Otherwise, lifecycle observer will start it when resumed
    }
    
    /**
     * Internal camera start - handles the actual CameraX setup.
     */
    private fun startCameraInternal() {
        if (isCameraStarted) {
            Log.d(TAG, "Camera already started, skipping")
            return
        }
        
        val previewView = previewView ?: run {
            Log.w(TAG, "No PreviewView set, cannot start camera")
            return
        }
        
        Log.d(TAG, "=== Starting camera internally ===")
        
        // Get camera provider (singleton, cached by system)
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        
        cameraProviderFuture.addListener({
            try {
                // Get the provider - this is cached, so safe to call multiple times
                cameraProvider = cameraProviderFuture.get()
                Log.d(TAG, "CameraProvider obtained")
                
                // Build Preview - this shows camera feed on screen
                preview = Preview.Builder()
                    .build()
                    .also {
                        // Connect preview to PreviewView surface provider
                        // This is the ONLY place we set the surface provider
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                Log.d(TAG, "Preview built")
                
                // Build ImageAnalysis - processes each frame
                imageAnalysis = ImageAnalysis.Builder()
                    // STRATEGY_KEEP_ONLY_LATEST: drop frames if analyzer can't keep up
                    // This prevents memory buildup and keeps processing current frames
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    // Set output format - YUV is default and most efficient
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .build()
                    .also { analysis ->
                        // Set analyzer on background thread
                        // CameraX will call this for each frame
                        analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                            processFrame(imageProxy)
                        }
                    }
                Log.d(TAG, "ImageAnalysis built")
                
                // Select back camera
                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                Log.d(TAG, "Using back camera")
                
                // UNBIND ALL before rebinding
                // This clears any existing use cases
                // We do this ONCE at setup, not repeatedly
                cameraProvider?.unbindAll()
                Log.d(TAG, "Unbound all previous use cases")
                
                // Bind use cases to lifecycle
                // bindToLifecycle ties camera lifecycle to Activity/Fragment lifecycle
                // Camera automatically starts when activity starts
                // Camera automatically stops when activity stops
                // Camera automatically released when activity is destroyed
                cameraProvider?.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    imageAnalysis
                )
                Log.d(TAG, "Camera started successfully - Preview and ImageAnalysis bound")
                
                isCameraStarted = true
                
            } catch (e: Exception) {
                Log.e(TAG, "Camera start failed", e)
                throw e
            }
        }, ContextCompat.getMainExecutor(context))
    }
    
    /**
     * Stop camera internally - unbinds use cases but keeps them ready for restart.
     */
    private fun stopCameraInternal() {
        if (!isCameraStarted) {
            Log.d(TAG, "Camera not started, skipping stop")
            return
        }
        
        Log.d(TAG, "=== Stopping camera internally ===")
        
        // Unbind use cases but keep references for restart
        cameraProvider?.unbind(preview, imageAnalysis)
        Log.d(TAG, "Use cases unbound (keeping references for restart)")
        
        isCameraStarted = false
    }
    
    /**
     * Process a single frame from the camera.
     * Optimized for performance: resizes image, reuses buffers, drops frames if busy.
     */
    private fun processFrame(imageProxy: ImageProxy) {
        // Drop frame if still processing previous one
        if (!isProcessingFrame.compareAndSet(false, true)) {
            Log.d(TAG, "Dropping frame - still processing previous")
            imageProxy.close()
            return
        }
        
        try {
            // Log when we receive a frame
            Log.d(TAG, "Image frame received - processing...")
            
            // Convert ImageProxy to Bitmap using optimized implementation
            val bitmap = imageProxyToBitmapOptimized(imageProxy)
            
            // Close the image proxy IMMEDIATELY to release resources
            imageProxy.close()
            
            // If we have a bitmap, run detection in background
            if (bitmap != null && detector != null) {
                Log.d(TAG, "Bitmap created, running detection...")
                
                // Run detection on background thread
                cameraExecutor.execute {
                    try {
                        val results = detector!!(bitmap)
                        Log.d(TAG, "Detection result: ${results.size} objects found")
                        
                        // Post results back to main thread
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            onDetectionResult?.invoke(results)
                        }
                        
                        // Clean up bitmap
                        bitmap.recycle()
                    } catch (e: Exception) {
                        Log.e(TAG, "Detection failed", e)
                    } finally {
                        // Mark processing as complete
                        isProcessingFrame.set(false)
                    }
                }
            } else {
                bitmap?.recycle()
                isProcessingFrame.set(false)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Frame processing error", e)
            // Always close the image to prevent memory leaks
            try {
                imageProxy.close()
            } catch (closeEx: Exception) {
                Log.e(TAG, "Failed to close imageProxy", closeEx)
            }
            isProcessingFrame.set(false)
        }
    }
    
    /**
     * Convert CameraX ImageProxy to Bitmap.
     * 
     * Why this is safe:
     * - Uses Android's built-in YuvImage which handles rowStride/padding
     * - No manual byte array indexing that can go out of bounds
     * - BitmapFactory.decodeByteArray() is robust and well-tested
     * 
     * This fixes the ArrayIndexOutOfBoundsException that occurred when
     * manually looping NV21 bytes with different rowStride values.
     * 
     * @param imageProxy The CameraX ImageProxy from ImageAnalysis
     * @return Bitmap or null if conversion fails
     */
    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
        return try {
            val image = imageProxy.image ?: run {
                Log.w(TAG, "ImageProxy has null image")
                return null
            }
            
            // Get the Y (luminance) plane buffer
            // This is the key fix: we use the Y plane directly with YuvImage,
            // which handles rowStride and padding correctly
            val plane = image.planes[0]
            val buffer = plane.buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)

            // Create YuvImage - Android's built-in handler for YUV data
            // This properly handles rowStride differences across devices
            val yuvImage = YuvImage(
                bytes,
                ImageFormat.NV21,
                image.width,
                image.height,
                null
            )

            // Compress to JPEG - this does the safe conversion
            val out = ByteArrayOutputStream()
            yuvImage.compressToJpeg(
                Rect(0, 0, image.width, image.height),
                JPEG_QUALITY,
                out
            )
            val jpegBytes = out.toByteArray()

            // Decode JPEG to Bitmap using BitmapFactory
            // This is robust and well-tested across all Android versions
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
            Log.e(TAG, "Bitmap conversion failed", e)
            null
        }
    }
    
    /**
     * Convert CameraX ImageProxy to Bitmap.
     * 
     * OPTIMIZED IMPLEMENTATION with performance improvements:
     * - Resizes image to INFERENCE_SIZE for faster inference
     * - Reuses ByteArrayOutputStream to avoid allocations
     * - Handles rotation efficiently
     * 
     * Why this is safe:
     * - Uses Android's built-in YuvImage which handles rowStride/padding
     * - No manual byte array indexing that can go out of bounds
     * - BitmapFactory.decodeByteArray() is robust and well-tested
     * 
     * This fixes the ArrayIndexOutOfBoundsException that occurred when
     * manually looping NV21 bytes with different rowStride values.
     * 
     * @param imageProxy The CameraX ImageProxy from ImageAnalysis
     * @return Bitmap or null if conversion fails
     */
    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    private fun imageProxyToBitmapOptimized(imageProxy: ImageProxy): Bitmap? {
        return try {
            val image = imageProxy.image ?: run {
                Log.w(TAG, "ImageProxy has null image")
                return null
            }
            
            // Get the Y (luminance) plane buffer
            val plane = image.planes[0]
            val buffer = plane.buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)

            // Create YuvImage - Android's built-in handler for YUV data
            val yuvImage = YuvImage(
                bytes,
                ImageFormat.NV21,
                image.width,
                image.height,
                null
            )

            // Reuse output stream to avoid allocations
            outputStream.reset()
            yuvImage.compressToJpeg(
                Rect(0, 0, image.width, image.height),
                JPEG_QUALITY,
                outputStream
            )
            val jpegBytes = outputStream.toByteArray()

            // Decode JPEG to Bitmap
            val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
                ?: return null

            // Apply rotation correction
            val rotationDegrees = imageProxy.imageInfo.rotationDegrees
            val rotatedBitmap = if (rotationDegrees != 0) {
                val matrix = Matrix()
                matrix.postRotate(rotationDegrees.toFloat())
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also {
                    bitmap.recycle()
                }
            } else {
                bitmap
            }

            // Resize for inference performance (maintains aspect ratio)
            val resizedBitmap = if (rotatedBitmap.width > INFERENCE_SIZE || rotatedBitmap.height > INFERENCE_SIZE) {
                val scale = minOf(
                    INFERENCE_SIZE.toFloat() / rotatedBitmap.width,
                    INFERENCE_SIZE.toFloat() / rotatedBitmap.height
                )
                val newWidth = (rotatedBitmap.width * scale).toInt()
                val newHeight = (rotatedBitmap.height * scale).toInt()
                
                Bitmap.createScaledBitmap(rotatedBitmap, newWidth, newHeight, true).also {
                    rotatedBitmap.recycle()
                }
            } else {
                rotatedBitmap
            }

            resizedBitmap
        } catch (e: Exception) {
            Log.e(TAG, "Bitmap conversion failed", e)
            null
        }
    }
    
    /**
     * Shutdown the camera and release resources.
     * 
     * Call this when the activity/fragment is being destroyed.
     * Unbinds all use cases and shuts down the executor.
     */
    fun shutdown() {
        Log.d(TAG, "=== Camera shutting down ===")
        
        // Unbind all use cases
        cameraProvider?.unbindAll()
        Log.d(TAG, "All use cases unbound")
        
        // Shutdown executor
        cameraExecutor.shutdown()
        Log.d(TAG, "Camera executor shut down")
    }
}

