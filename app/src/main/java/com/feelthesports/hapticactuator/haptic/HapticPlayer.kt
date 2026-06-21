package com.feelthesports.hapticactuator.haptic

import android.content.Context
import android.os.VibrationEffect
import android.os.VibratorManager

class HapticPlayer(context: Context, private val capabilities: HapticCapabilities) {

    private val vibrator = context.getSystemService(VibratorManager::class.java).defaultVibrator

    data class BatchEvent(val visionType: String?, val intensity: Float, val delayFromFirstMs: Int)

    fun play(visionType: String?, intensity: Float) {
        val scale = intensity.coerceIn(0f, 1f)
        when (capabilities.tier) {
            HapticTier.COMPOSITION -> playComposition(visionType, scale)
            HapticTier.AMPLITUDE   -> playAmplitude(scale)
            HapticTier.BASIC       -> playBasic(scale)
        }
    }

    /** Estimated wall-clock duration of a batch composition in milliseconds. */
    fun estimateBatchDurationMs(events: List<BatchEvent>): Int {
        if (events.isEmpty()) return 0
        val last = events.last()
        val lastId = pickPrimitive(last.visionType)
        return last.delayFromFirstMs + vibrator.getPrimitiveDurations(lastId)[0]
    }

    /**
     * Fire multiple events as a single Composition so the hardware engine handles
     * inter-event timing precisely — no second vibrate() call cancelling the first.
     * Falls back to individual play() calls for Tier 2/3 where Composition isn't available.
     */
    fun playBatch(events: List<BatchEvent>) {
        if (events.isEmpty()) return
        if (events.size == 1 || capabilities.tier != HapticTier.COMPOSITION) {
            events.forEach { play(it.visionType, it.intensity) }
            return
        }
        playCompositionBatch(events)
    }

    private fun playCompositionBatch(events: List<BatchEvent>) {
        val composition = VibrationEffect.startComposition()
        for ((i, event) in events.withIndex()) {
            val id = pickPrimitive(event.visionType)
            // addPrimitive(id, scale, delay): delay is the pause BEFORE this primitive,
            // after the previous one ends. We calculate it from the inter-event gap minus
            // the previous primitive's own duration.
            val delayMs = if (i == 0) {
                0
            } else {
                val prevId = pickPrimitive(events[i - 1].visionType)
                val prevDurationMs = vibrator.getPrimitiveDurations(prevId)[0]
                val gapMs = event.delayFromFirstMs - events[i - 1].delayFromFirstMs
                (gapMs - prevDurationMs).coerceAtLeast(0)
            }
            composition.addPrimitive(id, event.intensity, delayMs)
        }
        vibrator.vibrate(composition.compose())
    }

    private fun playComposition(visionType: String?, scale: Float) {
        val id = pickPrimitive(visionType)
        val effect = VibrationEffect.startComposition()
            .addPrimitive(id, scale)
            .compose()
        vibrator.vibrate(effect)
    }

    // §6.2: switch on vision_type — "strike" → sharp CLICK, "bounce" → heavy THUD, null → generic hit
    private fun pickPrimitive(visionType: String?): Int {
        val prefs = when (visionType) {
            "bounce" -> listOf("THUD", "LOW_TICK", "TICK", "CLICK")
            else     -> listOf("CLICK", "TICK", "LOW_TICK", "THUD")  // "strike", null, unknown
        }
        val name = prefs.firstOrNull { it in capabilities.supportedPrimitives }
            ?: capabilities.supportedPrimitives.first()
        return NAME_TO_ID[name] ?: VibrationEffect.Composition.PRIMITIVE_CLICK
    }

    private fun playAmplitude(scale: Float) {
        val amplitude = (scale * 255).toInt().coerceIn(1, 255)
        vibrator.vibrate(VibrationEffect.createOneShot(30L, amplitude))
    }

    private fun playBasic(scale: Float) {
        val durationMs = (20 + scale * 40).toLong()
        vibrator.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    companion object {
        private val NAME_TO_ID = mapOf(
            "CLICK"    to VibrationEffect.Composition.PRIMITIVE_CLICK,
            "TICK"     to VibrationEffect.Composition.PRIMITIVE_TICK,
            "LOW_TICK" to VibrationEffect.Composition.PRIMITIVE_LOW_TICK,
            "THUD"     to VibrationEffect.Composition.PRIMITIVE_THUD,
        )
    }
}
