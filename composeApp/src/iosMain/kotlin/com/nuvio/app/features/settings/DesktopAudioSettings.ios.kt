package com.nuvio.app.features.settings

internal actual object DesktopAudioSettings {
    actual val isSupported: Boolean = false
    actual val delayMs: Int = 0

    actual fun setDelayMs(delayMs: Int) = Unit
}
