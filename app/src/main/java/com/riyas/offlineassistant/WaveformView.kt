package com.riyas.SpideyAssistant

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.sin
import kotlin.random.Random

/**
 * Animated bar waveform shown while Spidey (TTS) is speaking, or while
 * listening to the user's voice input. Purely decorative — heights are
 * pseudo-random, no real audio amplitude analysis required.
 */
class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val barCount = 28
    private val barHeights = FloatArray(barCount) { 0.15f }
    private val targetHeights = FloatArray(barCount) { 0.15f }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private var animator: ValueAnimator? = null
    var isAnimating: Boolean = false
        private set

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        paint.shader = LinearGradient(
            0f, 0f, w.toFloat(), 0f,
            intArrayOf(
                resources.getColor(R.color.spidey_red, null),
                resources.getColor(R.color.spidey_gold, null),
                resources.getColor(R.color.spidey_blue, null),
            ),
            null,
            Shader.TileMode.CLAMP
        )
    }

    fun start() {
        if (isAnimating) return
        isAnimating = true
        visibility = VISIBLE
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 90
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                for (i in 0 until barCount) {
                    // Randomly drift each bar towards a new target for an organic look.
                    if (Random.nextFloat() < 0.35f) {
                        val phase = i * 0.4f
                        val base = 0.25f + 0.5f * ((sin(System.currentTimeMillis() / 180.0 + phase) + 1) / 2).toFloat()
                        targetHeights[i] = (base + Random.nextFloat() * 0.25f).coerceIn(0.1f, 1f)
                    }
                    barHeights[i] += (targetHeights[i] - barHeights[i]) * 0.5f
                }
                invalidate()
            }
            start()
        }
    }

    fun stop() {
        isAnimating = false
        animator?.cancel()
        animator = null
        for (i in 0 until barCount) {
            barHeights[i] = 0.12f
        }
        invalidate()
        visibility = GONE
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return

        val gap = 4.dp
        val barWidth = (width - gap * (barCount - 1)) / barCount.toFloat()
        val centerY = height / 2f

        for (i in 0 until barCount) {
            val h = (barHeights[i] * height).coerceAtLeast(6f)
            val left = i * (barWidth + gap)
            val top = centerY - h / 2f
            val bottom = centerY + h / 2f
            canvas.drawRoundRect(
                left, top, left + barWidth, bottom,
                barWidth / 2f, barWidth / 2f, paint
            )
        }
    }

    private val Int.dp: Float get() = this * resources.displayMetrics.density
}
