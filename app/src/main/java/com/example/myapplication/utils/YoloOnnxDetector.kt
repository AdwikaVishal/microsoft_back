package com.example.myapplication.utils

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.example.myapplication.utils.DetectionBox
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class YoloOnnxDetector(private val context: Context) {

    // private var ortEnvironment: OrtEnvironment? = null
    // private var ortSession: OrtSession? = null
    private val inputWidth = 640
    private val inputHeight = 640
    private val confidenceThreshold = 0.7f  // Increased from 0.5f to reduce false positives
    private val nmsThreshold = 0.4f
    
    // Executor for async detection - runs inference in background
    private val detectionExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    init {
        try {
            // ortEnvironment = OrtEnvironment.getEnvironment()
            // val modelBytes = context.assets.open("exit_detector.onnx").readBytes()
            // ortSession = ortEnvironment?.createSession(modelBytes)
            Log.d("YoloOnnxDetector", "Model loaded successfully (mock)")
        } catch (e: Exception) {
            Log.e("YoloOnnxDetector", "Failed to load model", e)
        }
    }

    /**
     * Synchronous detection - blocks until complete.
     * Use detectAsync for non-blocking detection.
     */
    fun detect(bitmap: Bitmap): List<DetectionBox> {
        Log.d("YoloOnnxDetector", "Running synchronous detection")
        
        // Mock detection for demonstration - simulate realistic behavior
        // In real implementation, replace with actual ONNX inference
        val results = mutableListOf<DetectionBox>()
        
        // Simulate occasional false positives and varying confidence
        val random = java.util.Random()
        val shouldDetectExit = random.nextFloat() < 0.3f  // 30% chance of detecting something
        val confidence = 0.4f + random.nextFloat() * 0.5f  // Random confidence 0.4-0.9
        
        if (shouldDetectExit && confidence >= confidenceThreshold) {
            // Only add detection if confidence meets threshold
            val label = if (random.nextFloat() < 0.7f) "EXIT" else "person"  // 70% chance it's actually an exit
            results.add(DetectionBox(0.2f, 0.3f, 0.5f, 0.6f, label, confidence))
        }
        
        Log.d("YoloOnnxDetector", "Synchronous detection complete: ${results.size} objects (threshold: $confidenceThreshold)")
        return results.filter { it.confidence >= confidenceThreshold }
    }
    
    /**
     * Asynchronous detection - runs in background thread.
     * Results are delivered via callback on main thread.
     * 
     * This is the preferred method for real-time processing
     * as it does NOT block the camera analyzer thread.
     * 
     * @param bitmap The input image for detection
     * @param callback Called on main thread with detection results
     */
    fun detectAsync(bitmap: Bitmap, callback: (List<DetectionBox>) -> Unit) {
        Log.d("YoloOnnxDetector", "Starting async detection")
        
        detectionExecutor.execute {
            try {
                // Run detection (this is the synchronous method)
                val results = detect(bitmap)
                
                Log.d("YoloOnnxDetector", "Async detection complete: ${results.size} objects found")
                
                // Post results back to main thread
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    callback(results)
                }
            } catch (e: Exception) {
                Log.e("YoloOnnxDetector", "Async detection failed", e)
                
                // Post empty results on main thread
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    callback(emptyList())
                }
            }
        }
    }

    // Rest of the code for when ONNX is added
    /*
    private fun preprocessImage(bitmap: Bitmap): OnnxTensor {
        // Resize bitmap
        val resizedBitmap = Bitmap.createScaledBitmap(bitmap, inputWidth, inputHeight, true)

        // Get pixels
        val pixels = IntArray(inputWidth * inputHeight)
        resizedBitmap.getPixels(pixels, 0, inputWidth, 0, 0, inputWidth, inputHeight)

        // Convert to float buffer: RGB, normalized 0-1
        val buffer = FloatBuffer.allocate(3 * inputWidth * inputHeight)
        for (i in pixels.indices) {
            val pixel = pixels[i]
            val r = ((pixel shr 16) and 0xFF) / 255.0f
            val g = ((pixel shr 8) and 0xFF) / 255.0f
            val b = (pixel and 0xFF) / 255.0f

            buffer.put(r)
            buffer.put(g)
            buffer.put(b)
        }
        buffer.rewind()

        return OnnxTensor.createTensor(ortEnvironment, buffer, longArrayOf(1, 3, inputHeight.toLong(), inputWidth.toLong()))
    }

    private fun processOutput(outputTensor: OnnxTensor?): List<Detection> {
        if (outputTensor == null) return emptyList()

        val output = outputTensor.floatBuffer
        val detections = mutableListOf<Detection>()

        // YOLOv8 output shape: [1, 84, 8400] for 80 classes + 4 bbox
        // Assuming 80 COCO classes + exit as custom class
        val numClasses = 80 // Adjust if your model has different number
        val numPredictions = 8400

        for (i in 0 until numPredictions) {
            val offset = i * (4 + numClasses)
            val x = output.get(offset)
            val y = output.get(offset + 1)
            val w = output.get(offset + 2)
            val h = output.get(offset + 3)

            // Find max confidence class
            var maxConf = 0f
            var classId = -1
            for (c in 0 until numClasses) {
                val conf = output.get(offset + 4 + c)
                if (conf > maxConf) {
                    maxConf = conf
                    classId = c
                }
            }

            if (maxConf > confidenceThreshold) {
                // Convert to bounding box
                val left = (x - w / 2) / inputWidth
                val top = (y - h / 2) / inputHeight
                val right = (x + w / 2) / inputWidth
                val bottom = (y + h / 2) / inputHeight

                val rect = RectF(left, top, right, bottom)
                val label = getClassName(classId)
                detections.add(Detection(label, maxConf, rect))
            }
        }

        return detections
    }

    private fun getClassName(classId: Int): String {
        // For EXIT detection, assume class 0 is exit or customize
        return if (classId == 0) "exit" else "unknown"
    }

    private fun nonMaxSuppression(detections: List<Detection>): List<Detection> {
        val sorted = detections.sortedByDescending { it.confidence }
        val result = mutableListOf<Detection>()

        for (detection in sorted) {
            var shouldAdd = true
            for (existing in result) {
                if (iou(detection.boundingBox, existing.boundingBox) > nmsThreshold) {
                    shouldAdd = false
                    break
                }
            }
            if (shouldAdd) result.add(detection)
        }

        return result
    }

    private fun iou(box1: RectF, box2: RectF): Float {
        val x1 = max(box1.left, box2.left)
        val y1 = max(box1.top, box2.top)
        val x2 = min(box1.right, box2.right)
        val y2 = min(box1.bottom, box2.bottom)

        val intersection = max(0f, x2 - x1) * max(0f, y2 - y1)
        val area1 = (box1.right - box1.left) * (box1.bottom - box1.top)
        val area2 = (box2.right - box2.left) * (box2.bottom - box2.top)
        val union = area1 + area2 - intersection

        return if (union > 0) intersection / union else 0f
    }
    */

    fun close() {
        // ortSession?.close()
        // ortEnvironment?.close()
    }
}