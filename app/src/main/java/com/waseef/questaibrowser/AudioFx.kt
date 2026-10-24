package com.waseef.questaibrowser

import android.media.audiofx.LoudnessEnhancer

/**
 * Native booster on the global output mix (session 0). Deprecated by Android and may be refused by the
 * device; the in-page Web Audio booster (assets/audio.js) is the primary path.
 */
class AudioFx {
    private var le: LoudnessEnhancer? = null
    var nativeOk = false; private set

    /** [percent] 100 = no change, up to 300. */
    fun apply(percent: Int) {
        val mB = ((percent - 100).coerceAtLeast(0) * 12) // ~ +12 dB at 200 %, capped below
        try {
            if (mB == 0) { release(); return }
            if (le == null) le = LoudnessEnhancer(0)
            le?.setTargetGain(mB.coerceAtMost(2000)); le?.enabled = true; nativeOk = true
        } catch (_: Throwable) { nativeOk = false; release() }
    }

    fun release() { try { le?.release() } catch (_: Throwable) {}; le = null }
}
