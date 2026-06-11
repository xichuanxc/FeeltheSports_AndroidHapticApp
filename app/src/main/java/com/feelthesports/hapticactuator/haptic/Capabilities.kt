package com.feelthesports.hapticactuator.haptic

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.VibratorManager

enum class HapticTier { COMPOSITION, AMPLITUDE, BASIC }

data class HapticCapabilities(
    val tier: HapticTier,
    val hasAmplitudeControl: Boolean,
    val supportedPrimitives: List<String>,
    val apiLevel: Int,
)

object Capabilities {

    private val PRIMITIVE_IDS = intArrayOf(
        VibrationEffect.Composition.PRIMITIVE_CLICK,
        VibrationEffect.Composition.PRIMITIVE_TICK,
        VibrationEffect.Composition.PRIMITIVE_LOW_TICK,
        VibrationEffect.Composition.PRIMITIVE_THUD,
    )
    private val PRIMITIVE_NAMES = listOf("CLICK", "TICK", "LOW_TICK", "THUD")

    fun detect(context: Context): HapticCapabilities {
        val vibrator = context.getSystemService(VibratorManager::class.java).defaultVibrator

        val hasAmplitude = vibrator.hasAmplitudeControl()
        val supported = vibrator.arePrimitivesSupported(*PRIMITIVE_IDS)
        val supportedPrimitives = PRIMITIVE_NAMES.filterIndexed { i, _ -> supported[i] }

        val tier = when {
            supportedPrimitives.isNotEmpty() -> HapticTier.COMPOSITION
            hasAmplitude -> HapticTier.AMPLITUDE
            else -> HapticTier.BASIC
        }

        return HapticCapabilities(
            tier = tier,
            hasAmplitudeControl = hasAmplitude,
            supportedPrimitives = supportedPrimitives,
            apiLevel = Build.VERSION.SDK_INT,
        )
    }
}
