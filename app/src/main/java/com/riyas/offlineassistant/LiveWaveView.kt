package com.riyas.SpideyAssistant

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.os.Build
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.sin

class LiveWaveView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val layerBases = floatArrayOf(0.43f, 0.60f, 0.76f)
    private val layerAmplitudes = floatArrayOf(18f, 26f, 34f)
    private val layerColors = intArrayOf(0x553E080D, 0x884F0A12.toInt(), 0xAA7A101B.toInt())
    private val paths = Array(layerBases.size) { Path() }
    private val gradients = arrayOfNulls<LinearGradient>(layerBases.size)
    private var phase = 0f
    private var animator: ValueAnimator? = null
    private var isActive = false
    private var audioLevel = 0f
    private var targetAudioLevel = 0f
    private val density = resources.displayMetrics.density

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        if (width == 0 || height == 0) return
        for (index in layerBases.indices) {
            gradients[index] = LinearGradient(
                0f,
                height * layerBases[index],
                0f,
                height.toFloat(),
                intArrayOf(layerColors[index], 0xEEB51220.toInt()),
                null,
                Shader.TileMode.CLAMP
            )
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val blurRadius = 6f * density
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            setRenderEffect(
                RenderEffect.createBlurEffect(
                    blurRadius,
                    blurRadius,
                    Shader.TileMode.CLAMP
                )
            )
        } else {
            setLayerType(LAYER_TYPE_SOFTWARE, null)
            paint.setShadowLayer(blurRadius, 0f, 0f, 0x553E080D)
        }
        if (isActive) startAnimation()
    }

    fun start() {
        if (isActive) return
        isActive = true
        startAnimation()
    }

    fun setInputLevel(rmsDb: Float) {
        targetAudioLevel = ((rmsDb + 2f) / 14f).coerceIn(0f, 1f)
    }

    fun stop() {
        isActive = false
        audioLevel = 0f
        targetAudioLevel = 0f
        animator?.cancel()
        animator = null
        invalidate()
    }

    private fun startAnimation() {
        if (!isAttachedToWindow || animator != null) return
        val waveAnimator = ValueAnimator.ofFloat(0f, (Math.PI * 2).toFloat())
        waveAnimator.duration = 4200L
        waveAnimator.repeatCount = ValueAnimator.INFINITE
        waveAnimator.interpolator = LinearInterpolator()
        waveAnimator.addUpdateListener {
            phase = it.animatedValue as Float
            invalidate()
        }
        animator = waveAnimator
        waveAnimator.start()
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return

        audioLevel += (targetAudioLevel - audioLevel) * 0.16f
        val step = 8f * density
        for (index in layerBases.indices) {
            val base = layerBases[index]
            val path = paths[index]
            path.reset()
            path.moveTo(0f, height.toFloat())
            var x = 0f
            while (x <= width) {
                val wave = sin((x / width * Math.PI * 2.2) + phase + index * 0.9).toFloat()
                val responsiveAmplitude = layerAmplitudes[index] + audioLevel * 48f
                val y = height * base + wave * responsiveAmplitude * density
                path.lineTo(x, y)
                x += step
            }
            path.lineTo(width.toFloat(), height.toFloat())
            path.close()
            paint.shader = gradients[index]
            canvas.drawPath(path, paint)
        }
        paint.shader = null
    }
}