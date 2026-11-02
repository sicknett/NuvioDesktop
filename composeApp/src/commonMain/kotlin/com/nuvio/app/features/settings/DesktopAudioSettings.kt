package com.nuvio.app.features.settings

/**
 * Desktop audio sync offset. Bluetooth headphones add a delay the system does not report, so audio can run
 * behind the picture; a positive value delays the video by that many milliseconds to match.
 */
internal expect object DesktopAudioSettings {
    val isSupported: Boolean
    val delayMs: Int

    fun setDelayMs(delayMs: Int)
}

internal const val DESKTOP_AUDIO_DELAY_MIN_MS = -500
internal const val DESKTOP_AUDIO_DELAY_MAX_MS = 1500
internal const val DESKTOP_AUDIO_DELAY_STEP_MS = 50
