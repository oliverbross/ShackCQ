package app.shackcq.mobile.n1mm

import app.shackcq.mobile.contest.*

enum class N1mmBridgeState { MONITORED, ACCEPTED, REVIEW_REQUIRED, REJECTED, DUPLICATE }
data class N1mmBridgeResult(val state:N1mmBridgeState,val canonicalQsoId:String?=null,val reason:String)
data class N1mmRemoteLink(val sourceStation:String,val remoteId:String,val remoteRevision:String,val canonicalQsoId:String,val originRevision:String)

interface N1mmRemoteLinkStore { fun find(sourceStation:String,remoteId:String,remoteRevision:String):N1mmRemoteLink?;fun put(link:N1mmRemoteLink) }
class InMemoryN1mmRemoteLinkStore:N1mmRemoteLinkStore{private val rows=linkedMapOf<String,N1mmRemoteLink>();private fun key(s:String,id:String,r:String)="$s|$id|$r";override fun find(sourceStation:String,remoteId:String,remoteRevision:String)=synchronized(rows){rows[key(sourceStation,remoteId,remoteRevision)]};override fun put(link:N1mmRemoteLink){synchronized(rows){rows[key(link.sourceStation,link.remoteId,link.remoteRevision)]=link}}}

fun interface N1mmContestStagingPort { fun stage(session:ContestSession,draft:ContestQsoDraft,remoteRevision:String):Boolean }

class N1mmQsoBridge(private val staging:N1mmContestStagingPort,private val links:N1mmRemoteLinkStore=InMemoryN1mmRemoteLinkStore()){
    fun receiveAdd(command:N1mmTypedCommand,sourceStation:String,context:N1mmPolicyContext,session:ContestSession,definition:ContestDefinition,owner:String):N1mmBridgeResult{
        require(command.command in setOf(N1mmCommand.QSO,N1mmCommand.RESYNCQSO))
        val remoteId=command.values["Id"].orEmpty();val revision=command.values["Timestamp"].orEmpty()
        if(remoteId.isBlank())return N1mmBridgeResult(N1mmBridgeState.REVIEW_REQUIRED,reason="Remote contact ID is missing")
        links.find(sourceStation,remoteId,revision)?.let{return N1mmBridgeResult(N1mmBridgeState.DUPLICATE,it.canonicalQsoId,"Exact remote revision replayed")}
        val decision=N1mmCommandPolicy.decide(command.command,context)
        if(decision!=N1mmPolicyDecision.AUTO_ACCEPT_SAFE_ADD)return N1mmBridgeResult(if(decision==N1mmPolicyDecision.TRUSTED_REVIEW)N1mmBridgeState.REVIEW_REQUIRED else N1mmBridgeState.MONITORED,reason="Network QSO was not eligible for safe automatic acceptance")
        val draft=map(command,sourceStation,remoteId)?:return N1mmBridgeResult(N1mmBridgeState.REVIEW_REQUIRED,reason="Remote QSO fields could not be mapped truthfully")
        val staged=runCatching{staging.stage(session,draft,revision)}.getOrDefault(false)
        if(!staged)return N1mmBridgeResult(N1mmBridgeState.REJECTED,reason="Network QSO could not be staged")
        links.put(N1mmRemoteLink(sourceStation,remoteId,revision,draft.qsoId,"$sourceStation:$remoteId:$revision"))
        return N1mmBridgeResult(N1mmBridgeState.ACCEPTED,draft.qsoId,"Accepted into the temporary Contest session log; explicit merge is still required")
    }
    fun receiveEditOrDelete(command:N1mmTypedCommand)=N1mmBridgeResult(N1mmBridgeState.REVIEW_REQUIRED,reason="Edits, deletes and checksum repair require explicit review in v1")
    private fun map(command:N1mmTypedCommand,source:String,id:String):ContestQsoDraft?{
        val timestamp=command.values["Timestamp"].orEmpty();val epoch=runCatching{java.time.LocalDateTime.parse(timestamp.replace(' ','T')).toEpochSecond(java.time.ZoneOffset.UTC)}.getOrNull()?:return null
        val freqX100=command.values["Freq"]?.toLongOrNull()?:return null;val mode=when(command.values["Mode"].orEmpty().uppercase()){ "CW"->ContestMode.CW;"SSB","USB","LSB","PH"->ContestMode.SSB;else->ContestMode.DIGITAL }
        val band=band(freqX100*10)?:return null
        val canonicalId=java.util.UUID.nameUUIDFromBytes("$source:$id".toByteArray()).toString()
        return ContestQsoDraft(canonicalId,command.values["CallSign"].orEmpty(),epoch,freqX100*10,band,mode,command.values["SNT"].orEmpty(),command.values["RCV"].orEmpty(),
            sent=buildMap{command.values["SentNR"].orEmpty().takeIf(String::isNotBlank)?.let{put(ContestExchangeField.SERIAL,it)};command.values["ZN"].orEmpty().takeIf(String::isNotBlank)?.let{put(ContestExchangeField.CQ_ZONE,it)}},
            received=buildMap{command.values["NR"].orEmpty().takeIf(String::isNotBlank)?.let{put(ContestExchangeField.SERIAL,it)};command.values["ZN"].orEmpty().takeIf(String::isNotBlank)?.let{put(ContestExchangeField.CQ_ZONE,it)};command.values["Sect"].orEmpty().takeIf(String::isNotBlank)?.let{put(ContestExchangeField.STATE_PROVINCE,it)};command.values["Power"].orEmpty().takeIf(String::isNotBlank)?.let{put(ContestExchangeField.POWER,it)}},
            worked=ContestEntityInfo(continent=command.values["Continent"].orEmpty(),wpxPrefix=command.values["WPXPrefix"].orEmpty(),stateProvince=command.values["Sect"].orEmpty()),networkOriginId="$source:$id")
    }
    private fun band(hz:Long)=when(hz){in 1_800_000L..2_000_000L->ContestBand.B160;in 3_500_000L..4_000_000L->ContestBand.B80;in 7_000_000L..7_300_000L->ContestBand.B40;in 14_000_000L..14_350_000L->ContestBand.B20;in 21_000_000L..21_450_000L->ContestBand.B15;in 28_000_000L..29_700_000L->ContestBand.B10;else->null}
}
