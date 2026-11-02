package com.nuvio.app.features.settings

import com.nuvio.app.core.storage.DesktopStorage

internal actual object DesktopAudioSettings {
    private const val DELAY_KEY = "audio_delay_ms"
    private val store by lazy { DesktopStorage.store("nuvio_desktop_audio") }

    // The native audio-delay hook is only implemented in the Windows player bridge.
    actual val isSupported: Boolean =
        System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

    actual val delayMs: Int
        get() = if (isSupported) {
            (store.getInt(DELAY_KEY) ?: 0).coerceIn(DESKTOP_AUDIO_DELAY_MIN_MS, DESKTOP_AUDIO_DELAY_MAX_MS)
        } else {
            0
        }

    actual fun setDelayMs(delayMs: Int) {
        store.putInt(DELAY_KEY, delayMs.coerceIn(DESKTOP_AUDIO_DELAY_MIN_MS, DESKTOP_AUDIO_DELAY_MAX_MS))
    }
}
