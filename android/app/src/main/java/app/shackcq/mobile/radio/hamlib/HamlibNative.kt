package app.shackcq.mobile.radio.hamlib

internal interface HamlibNativeApi {
    fun libraryInfo(): String
    fun models(): String
    fun create(modelId: Int): Long
    fun destroy(handle: Long)
    fun readOnly(handle: Long, enabled: Boolean): Int
    fun configureSerial(handle: Long, profile: HamlibSerialProfile): Int
    fun configureNetwork(handle: Long, profile: HamlibNetworkProfile): Int
    fun open(handle: Long): Int
    fun close(handle: Long): Int
    fun bridgeRead(handle: Long, maximum: Int, timeoutMs: Int): ByteArray
    fun bridgeWrite(handle: Long, data: ByteArray): Int
    fun snapshot(handle: Long): String
    fun apply(handle: Long, action: HamlibAction): Int
    fun error(status: Int): String
}

internal object NativeHamlib : HamlibNativeApi {
    init { System.loadLibrary("shackcq") }

    external fun libraryInfoNative(): String
    external fun modelsNative(): String
    external fun sessionCreateNative(modelId: Int): Long
    external fun sessionDestroyNative(handle: Long)
    external fun sessionSetReadOnlyNative(handle: Long, enabled: Boolean): Int
    external fun sessionConfigureSerialNative(
        handle: Long, baud: Int, dataBits: Int, stopBits: Int, parity: Int,
        handshake: Int, timeoutMs: Int, rts: Int, dtr: Int,
    ): Int
    external fun sessionConfigureNetworkNative(handle: Long, host: String, port: Int, timeoutMs: Int): Int
    external fun sessionOpenNative(handle: Long): Int
    external fun sessionCloseNative(handle: Long): Int
    external fun bridgeReadNative(handle: Long, maximum: Int, timeoutMs: Int): ByteArray
    external fun bridgeWriteNative(handle: Long, data: ByteArray): Int
    external fun sessionSnapshotNative(handle: Long): String
    external fun setFrequencyNative(handle: Long, vfo: String, frequency: Long): Int
    external fun setVfoNative(handle: Long, vfo: String): Int
    external fun setModeNative(handle: Long, vfo: String, mode: String, passbandHz: Int): Int
    external fun setSplitNative(handle: Long, enabled: Boolean, txVfo: String): Int
    external fun setRitNative(handle: Long, offsetHz: Int): Int
    external fun setXitNative(handle: Long, offsetHz: Int): Int
    external fun setLevelNative(handle: Long, level: String, value: Double): Int
    external fun setFunctionNative(handle: Long, function: String, enabled: Boolean): Int
    external fun setParameterNative(handle: Long, parameter: String, value: Double): Int
    external fun setPttNative(handle: Long, enabled: Boolean): Int
    external fun tuneNative(handle: Long): Int
    external fun errorNative(status: Int): String
    external fun rotatorModelsNative(): String
    external fun rotatorSessionCreateNative(modelId: Int): Long
    external fun rotatorSessionDestroyNative(handle: Long)
    external fun rotatorSessionSetReadOnlyNative(handle: Long, enabled: Boolean): Int
    external fun rotatorSessionConfigureSerialNative(
        handle: Long, baud: Int, dataBits: Int, stopBits: Int, parity: Int,
        handshake: Int, timeoutMs: Int, rts: Int, dtr: Int,
    ): Int
    external fun rotatorSessionConfigureNetworkNative(handle: Long, host: String, port: Int, timeoutMs: Int): Int
    external fun rotatorSessionOpenNative(handle: Long): Int
    external fun rotatorSessionCloseNative(handle: Long): Int
    external fun rotatorBridgeReadNative(handle: Long, maximum: Int, timeoutMs: Int): ByteArray
    external fun rotatorBridgeWriteNative(handle: Long, data: ByteArray): Int
    external fun rotatorPollNative(handle: Long): String
    external fun rotatorSetPositionNative(handle: Long, azimuth: Double, elevation: Double): Int
    external fun rotatorStopNative(handle: Long): Int
    external fun rotatorParkNative(handle: Long): Int

    override fun libraryInfo() = libraryInfoNative()
    override fun models() = modelsNative()
    override fun create(modelId: Int) = sessionCreateNative(modelId)
    override fun destroy(handle: Long) = sessionDestroyNative(handle)
    override fun readOnly(handle: Long, enabled: Boolean) = sessionSetReadOnlyNative(handle, enabled)
    override fun configureSerial(handle: Long, profile: HamlibSerialProfile) = sessionConfigureSerialNative(
        handle, profile.baud, profile.dataBits, profile.stopBits, profile.parity,
        profile.handshake, profile.timeoutMs, profile.rts, profile.dtr,
    )
    override fun configureNetwork(handle: Long, profile: HamlibNetworkProfile) =
        sessionConfigureNetworkNative(handle, profile.host, profile.port, profile.timeoutMs)
    override fun open(handle: Long) = sessionOpenNative(handle)
    override fun close(handle: Long) = sessionCloseNative(handle)
    override fun bridgeRead(handle: Long, maximum: Int, timeoutMs: Int) = bridgeReadNative(handle, maximum, timeoutMs)
    override fun bridgeWrite(handle: Long, data: ByteArray) = bridgeWriteNative(handle, data)
    override fun snapshot(handle: Long) = sessionSnapshotNative(handle)
    override fun error(status: Int) = errorNative(status)
    override fun apply(handle: Long, action: HamlibAction): Int = when (action) {
        is HamlibAction.SetFrequency -> setFrequencyNative(handle, action.vfo, action.hz)
        is HamlibAction.SetVfo -> setVfoNative(handle, action.vfo)
        is HamlibAction.SetMode -> setModeNative(handle, action.vfo, action.mode, action.passbandHz)
        is HamlibAction.SetSplit -> setSplitNative(handle, action.enabled, action.txVfo)
        is HamlibAction.SetRit -> setRitNative(handle, action.hz)
        is HamlibAction.SetXit -> setXitNative(handle, action.hz)
        is HamlibAction.SetLevel -> setLevelNative(handle, action.name, action.value)
        is HamlibAction.SetFunction -> setFunctionNative(handle, action.name, action.enabled)
        is HamlibAction.SetParameter -> setParameterNative(handle, action.name, action.value)
        is HamlibAction.SetPtt -> setPttNative(handle, action.enabled)
        HamlibAction.Tune -> tuneNative(handle)
    }

    fun rotatorModels(): String = rotatorModelsNative()
    fun rotatorCreate(modelId: Int): Long = rotatorSessionCreateNative(modelId)
    fun rotatorDestroy(handle: Long) = rotatorSessionDestroyNative(handle)
    fun rotatorReadOnly(handle: Long, enabled: Boolean) = rotatorSessionSetReadOnlyNative(handle, enabled)
    fun rotatorConfigure(handle: Long, profile: HamlibSerialProfile) = rotatorSessionConfigureSerialNative(
        handle, profile.baud, profile.dataBits, profile.stopBits, profile.parity,
        profile.handshake, profile.timeoutMs, profile.rts, profile.dtr,
    )
    fun rotatorConfigure(handle: Long, profile: HamlibNetworkProfile) =
        rotatorSessionConfigureNetworkNative(handle, profile.host, profile.port, profile.timeoutMs)
    fun rotatorOpen(handle: Long) = rotatorSessionOpenNative(handle)
    fun rotatorClose(handle: Long) = rotatorSessionCloseNative(handle)
    fun rotatorBridgeRead(handle: Long, maximum: Int, timeoutMs: Int) = rotatorBridgeReadNative(handle, maximum, timeoutMs)
    fun rotatorBridgeWrite(handle: Long, data: ByteArray) = rotatorBridgeWriteNative(handle, data)
    fun rotatorPoll(handle: Long) = rotatorPollNative(handle)
    fun rotatorSetPosition(handle: Long, azimuth: Double, elevation: Double) = rotatorSetPositionNative(handle, azimuth, elevation)
    fun rotatorStop(handle: Long) = rotatorStopNative(handle)
    fun rotatorPark(handle: Long) = rotatorParkNative(handle)
}
