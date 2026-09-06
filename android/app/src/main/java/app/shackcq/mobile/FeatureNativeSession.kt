package app.shackcq.mobile

internal interface FeatureNativeApi {
    fun create(): Long
    fun destroy(handle: Long)
    fun setWatchlist(handle: Long, value: String)
    fun loadCty(handle: Long, text: String): Boolean
    fun ingestClusterLine(handle: Long, value: String, epoch: Long): Boolean
    fun snapshot(handle: Long, epoch: Long): String
    fun beginWorkedSync(handle: Long): Boolean
    fun addWorkedQso(handle: Long, row: WorkedLogQso): Boolean
    fun endWorkedSync(handle: Long): Boolean
    fun setSolar(handle: Long, flux: Float, aIndex: Float, kpIndex: Float, epoch: Long)
}

internal object CoreFeatureNativeApi : FeatureNativeApi {
    override fun create() = NativeCore.featureCreate()
    override fun destroy(handle: Long) = NativeCore.featureDestroy(handle)
    override fun setWatchlist(handle: Long, value: String) = NativeCore.featureWatchlist(handle, value)
    override fun loadCty(handle: Long, text: String) = NativeCore.featureLoadCty(handle, text)
    override fun ingestClusterLine(handle: Long, value: String, epoch: Long) =
        NativeCore.featureClusterLine(handle, value, epoch)
    override fun snapshot(handle: Long, epoch: Long) = NativeCore.featureDxSnapshot(handle, epoch)
    override fun beginWorkedSync(handle: Long) = NativeCore.featureBeginWorkedSync(handle)
    override fun addWorkedQso(handle: Long, row: WorkedLogQso) = NativeCore.featureAddWorkedQso(
        handle, row.callsign, row.entity, row.band, row.mode, row.submode, row.epoch, row.fromWavelog,
    )
    override fun endWorkedSync(handle: Long) = NativeCore.featureEndWorkedSync(handle)
    override fun setSolar(handle: Long, flux: Float, aIndex: Float, kpIndex: Float, epoch: Long) =
        NativeCore.featureSolar(handle, flux, aIndex, kpIndex, epoch)
}

internal class FeatureNativeSession(
    private val api: FeatureNativeApi = CoreFeatureNativeApi,
) : AutoCloseable {
    private val handles = NativeHandleOwner(
        initialHandle = api.create().also { check(it != 0L) { "Native feature context unavailable" } },
        destroyHandle = api::destroy,
    )

    fun setWatchlist(value: String): Boolean = handles.withHandle {
        api.setWatchlist(it, value)
        true
    } ?: false

    fun ingestClusterLine(value: String, epoch: Long): Boolean =
        handles.withHandle { api.ingestClusterLine(it, value, epoch) } ?: false

    fun setSolar(flux: Float, aIndex: Float, kpIndex: Float, epoch: Long): Boolean = handles.withHandle {
        api.setSolar(it, flux, aIndex, kpIndex, epoch)
        true
    } ?: false

    fun synchronizeWorkedLog(ctyText: String?, rows: List<WorkedLogQso>, selectedAuthority: Boolean): Boolean =
        handles.withHandle { handle ->
            if (ctyText != null) require(api.loadCty(handle, ctyText))
            require(api.beginWorkedSync(handle))
            if (selectedAuthority) {
                rows.forEach { require(api.addWorkedQso(handle, it)) }
            }
            require(api.endWorkedSync(handle))
            true
        } ?: false

    fun snapshot(epoch: Long): String? = handles.withHandle { api.snapshot(it, epoch) }

    fun generation(): Long = handles.generation()
    fun isCurrent(generation: Long): Boolean = handles.isCurrent(generation)

    override fun close() = handles.close()
}
