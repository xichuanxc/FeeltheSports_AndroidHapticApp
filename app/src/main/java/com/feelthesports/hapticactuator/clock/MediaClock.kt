package com.feelthesports.hapticactuator.clock

class MediaClock {

    @Volatile var clockOffsetNs: Long = 0L

    private data class Anchor(
        val mediaT: Double,
        val serverNs: Long,
        val rate: Double,
    )

    @Volatile private var anchor: Anchor? = null

    /**
     * Update the clock offset without disturbing the running media time.
     * Shifting clockOffsetNs shifts nowServerNs(), which would change mediaTime() unless
     * we compensate by shifting anchor.serverNs by the same delta.
     */
    fun setOffset(offsetNs: Long) {
        val delta = offsetNs - clockOffsetNs
        clockOffsetNs = offsetNs
        anchor?.let { anchor = it.copy(serverNs = it.serverNs + delta) }
    }

    /** Called when play arrives. Maps [mediaT] to the current estimated server time. */
    fun play(mediaT: Double, rate: Double = 1.0) {
        anchor = Anchor(mediaT, nowServerNs(), rate)
    }

    /** Freeze at [mediaT]. */
    fun pause(mediaT: Double) {
        anchor = Anchor(mediaT, nowServerNs(), 0.0)
    }

    /** Jump to [mediaT]; preserve the current rate. */
    fun seek(mediaT: Double) {
        anchor = Anchor(mediaT, nowServerNs(), anchor?.rate ?: 1.0)
    }

    fun setRate(rate: Double) {
        anchor = Anchor(mediaTime(), nowServerNs(), rate)
    }

    /**
     * Re-anchor using a server-supplied pair from a UDP sync pulse or play message.
     * This is more accurate than play() because it uses the server's own timestamp.
     */
    fun syncAnchor(mediaT: Double, tServerNs: Long, rate: Double) {
        anchor = Anchor(mediaT, tServerNs, rate)
    }

    fun mediaTime(): Double {
        val a = anchor ?: return 0.0
        if (a.rate == 0.0) return a.mediaT
        return a.mediaT + (nowServerNs() - a.serverNs) / 1_000_000_000.0 * a.rate
    }

    val rate: Double get() = anchor?.rate ?: 1.0
    val isPlaying: Boolean get() = (anchor?.rate ?: 0.0) != 0.0

    private fun nowServerNs() = System.nanoTime() + clockOffsetNs
}
