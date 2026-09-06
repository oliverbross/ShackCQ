package app.shackcq.mobile

enum class RadioCapability { RECEIVE_STATE, RECEIVE_TUNE, FILTER, EQ, MACROS, PANADAPTER, TRANSMIT }

data class ReceiveTuneRequest(val frequencyHz: Long, val mode: String? = null)

interface RadioBackend : AutoCloseable {
    val capabilities: Set<RadioCapability>
    val state: RadioState
    suspend fun connect(): Boolean
    suspend fun disconnect()
    suspend fun tune(request: ReceiveTuneRequest): Boolean
}

