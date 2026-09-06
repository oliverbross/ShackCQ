package app.shackcq.mobile

/** Batched JNI boundary for one dedicated real-time panadapter context. */
object NativePanadapter {
    init { System.loadLibrary("shackcq") }

    external fun create(): Long
    external fun destroy(handle: Long)
    external fun configure(
        handle: Long,
        sampleRate: Int,
        fftSize: Int,
        overlapPercent: Int,
        window: Int,
        displayFloorDb: Float,
        displayTopDb: Float,
        attack: Float,
        release: Float,
        averageFrames: Int,
        peakHold: Boolean,
        peakDecayDbPerSecond: Float,
        genericKx3Flatness: Boolean,
        swapIq: Boolean,
        invertI: Boolean,
        invertQ: Boolean,
        conjugate: Boolean,
        iTrim: Float,
        qTrim: Float,
        zoomDecimation: Int,
        zoomOffsetHz: Float,
    ): Boolean

    external fun push(handle: Long, samples: ShortArray, sampleCount: Int, discontinuity: Boolean): Boolean
    external fun pushFloat(handle: Long, samples: FloatArray, sampleCount: Int, discontinuity: Boolean): Boolean

    /**
     * Fills every array while the native snapshot lock is held. Meta requires 9 longs,
     * metrics requires 14 floats, and spectrum arrays must be at least the configured FFT size.
     */
    external fun snapshot(
        handle: Long,
        meta: LongArray,
        metrics: FloatArray,
        trace: FloatArray,
        waterfall: FloatArray,
        peakHold: FloatArray,
    ): Int

    external fun setIqCorrection(
        handle: Long,
        aReal: Float,
        aImag: Float,
        bReal: Float,
        bImag: Float,
        enabled: Boolean,
    ): Boolean

    external fun resetPeakHold(handle: Long)
}
