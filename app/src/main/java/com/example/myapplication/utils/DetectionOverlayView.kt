package com.example.myapplication.utils

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * Custom overlay view for drawing detection bounding boxes.
 *
 * Features:
 * - Only redraws when new detection results arrive
 * - Efficient drawing with hardware acceleration
 * - Thread-safe updates from background threads
 * - Automatic scaling to match camera preview dimensions
 */
class DetectionOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val boxPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
    }

    private val labelBgPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val labelPaint = Paint().apply {
        textSize = 32f
        isAntiAlias = true
        color = Color.WHITE
    }

    // Detection results - updated from background thread
    @Volatile
    private var detections: List<DetectionBox> = emptyList()

    // Frame debounce logic for consistent exit detection
    private var exitDetectionCount = 0
    private val requiredExitFrames = 3  // Require 3 consecutive frames with exit detection
    private var lastExitDetectionTime = 0L
    private val exitCooldownMs = 2000L  // 2 second cooldown after exit detection

    // Callback for exit detection
    var onExitDetected: (() -> Unit)? = null

    /**
     * Update detection results and trigger redraw.
     * Thread-safe - can be called from background threads.
     * Implements frame-debounce logic to prevent false positive exit detections.
     */
    fun updateDetections(newDetections: List<DetectionBox>) {
        detections = newDetections

        // Frame-debounce logic for exit detection
        val currentTime = System.currentTimeMillis()
        val hasExit = newDetections.any { it.label.equals("EXIT", ignoreCase = true) }

        if (hasExit) {
            // Increment counter for consecutive exit detections
            exitDetectionCount++
            
            // Only trigger callback after consistent detection and cooldown period
            if (exitDetectionCount >= requiredExitFrames && 
                (currentTime - lastExitDetectionTime) > exitCooldownMs) {
                onExitDetected?.invoke()
                lastExitDetectionTime = currentTime
                exitDetectionCount = 0  // Reset counter after successful detection
            }
        } else {
            // Reset counter if no exit detected in this frame
            exitDetectionCount = 0
        }

        // Trigger redraw on main thread
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()

        for (detection in detections) {
            // Scale normalized coordinates to view dimensions
            val left = detection.x1 * viewWidth
            val top = detection.y1 * viewHeight
            val right = detection.x2 * viewWidth
            val bottom = detection.y2 * viewHeight

            // Choose color based on label
            val isExit = detection.label.equals("EXIT", ignoreCase = true)
            val boxColor = if (isExit) Color.GREEN else Color.RED
            val bgColor = if (isExit) Color.argb(178, 0, 255, 0) else Color.argb(178, 255, 0, 0)

            // Draw bounding box
            boxPaint.color = boxColor
            canvas.drawRect(left, top, right, bottom, boxPaint)

            // Draw label background
            labelBgPaint.color = bgColor
            val labelHeight = 40f
            canvas.drawRect(left, top - labelHeight, right, top, labelBgPaint)

            // Draw label text
            canvas.drawText(
                "${detection.label} ${String.format("%.2f", detection.confidence)}",
                left + 4,
                top - 8,
                labelPaint
            )
        }
    }

    /**
     * Clear all detections and redraw.
     */
    fun clearDetections() {
        detections = emptyList()
        invalidate()
    }
}

/**
 * Simple data class for detection results.
 * Uses normalized coordinates (0.0-1.0) for easy scaling.
 */
data class DetectionBox(
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float,
    val label: String,
    val confidence: Float
)

