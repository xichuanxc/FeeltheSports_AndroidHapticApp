package com.feelthesports.hapticactuator.haptic

import android.content.Context
import android.os.VibrationEffect
import android.os.VibratorManager

class HapticPlayer(context: Context, private val capabilities: HapticCapabilities) {

    private val vibrator = context.getSystemService(VibratorManager::class.java).defaultVibrator

    fun play(type: String, intensity: Float) {
        val scale = intensity.coerceIn(0f, 1f)
        when (capabilities.tier) {
            HapticTier.COMPOSITION -> playComposition(type, scale)
            HapticTier.AMPLITUDE   -> playAmplitude(scale)
            HapticTier.BASIC       -> playBasic(scale)
        }
    }

    private fun playComposition(type: String, scale: Float) {
        val id = pickPrimitive(type)
        val effect = VibrationEffect.startComposition()
            .addPrimitive(id, scale)
            .compose()
        vibrator.vibrate(effect)
    }

    private fun pickPrimitive(type: String): Int {
        val prefs = when (type) {
            "bounce" -> listOf("THUD", "LOW_TICK", "TICK", "CLICK")
            else     -> listOf("CLICK", "TICK", "LOW_TICK", "THUD")
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
