package com.example.resonant.ui.customviews

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.sin

/**
 * VoiceSpectrumSphereView
 * A custom view that draws a 3D wireframe sphere.
 * The sphere rotates continuously, and its points react to the amplitude
 * of the user's voice, creating a dynamic, music-like spectrum effect.
 */
class VoiceSpectrumSphereView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.parseColor("#B82276")
    }
    
    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#C585FF")
        setShadowLayer(10f, 0f, 0f, Color.parseColor("#7E37C9"))
    }

    private var rotationAngleY = 0f
    private var rotationAngleX = 0f
    private var rotationAnimator: ValueAnimator? = null
    
    // Voice amplitude (0f to 1f)
    private var currentAmplitude = 0f
    private var targetAmplitude = 0f

    // Sphere parameters
    private val numLatitudes = 12
    private val numLongitudes = 24
    
    init {
        startRotation()
    }

    private fun startRotation() {
        rotationAnimator = ValueAnimator.ofFloat(0f, Math.PI.toFloat() * 2).apply {
            duration = 8000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { animation ->
                rotationAngleY = animation.animatedValue as Float
                rotationAngleX = (animation.animatedValue as Float) * 0.5f // Slight X rotation
                
                // Smoothly interpolate amplitude
                currentAmplitude += (targetAmplitude - currentAmplitude) * 0.15f
                invalidate()
            }
            start()
        }
    }

    /**
     * Updates the amplitude based on voice volume (RMS in dB).
     * @param rmsdB value usually ranges from -2f to 10f.
     */
    fun updateAmplitude(rmsdB: Float) {
        // Normalize rmsdB (approximate typical ranges)
        val normalized = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
        targetAmplitude = normalized
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        val cx = width / 2f
        val cy = height / 2f
        
        // Base radius, slightly pulses with amplitude
        val baseRadius = (width.coerceAtMost(height) / 2f) * 0.6f
        val radius = baseRadius + (baseRadius * 0.4f * currentAmplitude)
        
        // Perspective projection constants
        val fov = 400f
        
        // Store 3D points
        val points = Array(numLatitudes + 1) { Array(numLongitudes) { FloatArray(3) } }
        
        // Calculate points
        for (i in 0..numLatitudes) {
            val lat = Math.PI * i / numLatitudes - Math.PI / 2
            val rCosLat = radius * cos(lat).toFloat()
            val rSinLat = radius * sin(lat).toFloat()
            
            for (j in 0 until numLongitudes) {
                val lon = 2 * Math.PI * j / numLongitudes
                
                // Base 3D coordinates
                var x = rCosLat * cos(lon).toFloat()
                var y = rSinLat
                var z = rCosLat * sin(lon).toFloat()
                
                // Add noise/spikes based on amplitude
                // Use sine waves based on coordinates to make it look organic
                if (currentAmplitude > 0.05f) {
                    val noise = (sin(x * 0.05f + rotationAngleY * 5f) * cos(y * 0.05f) * currentAmplitude * baseRadius * 0.3f)
                    val dirLen = kotlin.math.sqrt(x*x + y*y + z*z)
                    if (dirLen > 0) {
                        x += (x / dirLen) * noise
                        y += (y / dirLen) * noise
                        z += (z / dirLen) * noise
                    }
                }
                
                // Rotate around X axis
                val cosX = cos(rotationAngleX.toDouble()).toFloat()
                val sinX = sin(rotationAngleX.toDouble()).toFloat()
                val y1 = y * cosX - z * sinX
                val z1 = y * sinX + z * cosX
                
                // Rotate around Y axis
                val cosY = cos(rotationAngleY.toDouble()).toFloat()
                val sinY = sin(rotationAngleY.toDouble()).toFloat()
                val x2 = x * cosY + z1 * sinY
                val z2 = -x * sinY + z1 * cosY
                
                points[i][j][0] = x2
                points[i][j][1] = y1
                points[i][j][2] = z2
            }
        }
        
        // Draw the wireframe
        for (i in 0..numLatitudes) {
            for (j in 0 until numLongitudes) {
                val p1 = points[i][j]
                val jNext = (j + 1) % numLongitudes
                val p2 = points[i][jNext]
                
                // Draw latitude lines
                drawLine3D(canvas, p1, p2, cx, cy, fov)
                
                // Draw longitude lines
                if (i < numLatitudes) {
                    val p3 = points[i + 1][j]
                    drawLine3D(canvas, p1, p3, cx, cy, fov)
                }
                
                // Draw dots at vertices (only front-facing or mid-facing)
                if (p1[2] > -radius * 0.8f) {
                    val scale = fov / (fov + p1[2])
                    val px = cx + p1[0] * scale
                    val py = cy + p1[1] * scale
                    
                    // Dynamic dot size based on amplitude and z-depth
                    val dotBaseSize = 4f + (currentAmplitude * 8f)
                    canvas.drawCircle(px, py, dotBaseSize * scale, pointPaint)
                }
            }
        }
    }
    
    private fun drawLine3D(canvas: Canvas, p1: FloatArray, p2: FloatArray, cx: Float, cy: Float, fov: Float) {
        // Simple backface culling/fading
        if (p1[2] < -100f && p2[2] < -100f) {
            paint.alpha = 10
        } else {
            // Dynamic alpha based on Z depth
            val zAvg = (p1[2] + p2[2]) / 2f
            val alpha = (40 + (zAvg / 200f) * 60).toInt().coerceIn(10, 80)
            paint.alpha = alpha
        }
        
        val scale1 = fov / (fov + p1[2])
        val scale2 = fov / (fov + p2[2])
        
        val px1 = cx + p1[0] * scale1
        val py1 = cy + p1[1] * scale1
        
        val px2 = cx + p2[0] * scale2
        val py2 = cy + p2[1] * scale2
        
        canvas.drawLine(px1, py1, px2, py2, paint)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        rotationAnimator?.cancel()
    }
}
