// SPDX-License-Identifier: GPL-3.0-only
package app.shackcq.mobile

object NativeLocalReceiver {
    const val HEADER_SIZE = 18

    init { System.loadLibrary("shackcq") }

    external fun create(): Long
    external fun destroy(handle: Long)
    external fun configure(
        handle: Long,
        inputRate: Int,
        mode: Int,
        offsetHz: Float,
        filterLowHz: Float,
        filterHighHz: Float,
        cwPitchHz: Float,
        squelchDb: Float,
        deemphasisUs: Int,
    ): Boolean
    external fun process(handle: Long, iq: FloatArray): FloatArray
    external fun debugRdsGroup(handle: Long, a: Int, b: Int, c: Int, d: Int): Boolean
    external fun metadata(handle: Long): String
}
