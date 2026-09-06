package app.shackcq.mobile.contest

import java.util.Locale

class ContestDupeEngine {
    fun key(definition: ContestDefinition, draft: ContestQsoDraft): String? {
        val call = normalizeCall(draft.callsign).takeIf(String::isNotBlank) ?: return null
        val scope = when (definition.dupeScope) {
            ContestDupeScope.ONCE_PER_CONTEST -> "ALL"
            ContestDupeScope.ONCE_PER_BAND -> draft.band.name
            ContestDupeScope.ONCE_PER_MODE -> draft.mode.name
            ContestDupeScope.ONCE_PER_BAND_MODE -> "${draft.band.name}/${draft.mode.name}"
            ContestDupeScope.CUSTOM_PERIOD -> "${draft.band.name}/${draft.createdAt / (24 * 3600)}"
        }
        return "$call|$scope"
    }
    fun evaluate(definition: ContestDefinition, draft: ContestQsoDraft, prior: Iterable<ContestQsoDraft>): ContestDupeState {
        val call = normalizeCall(draft.callsign)
        if (call.isBlank()) return ContestDupeState.UNKNOWN
        val duplicate = prior.asSequence().filter { it.qsoId != draft.qsoId }.any {
            normalizeCall(it.callsign) == call && sameScope(definition.dupeScope, draft, it)
        }
        return when {
            !duplicate -> ContestDupeState.NEW
            draft.explicitDupeOverride -> ContestDupeState.OVERRIDDEN
            else -> ContestDupeState.DUPLICATE
        }
    }

    private fun sameScope(scope: ContestDupeScope, a: ContestQsoDraft, b: ContestQsoDraft): Boolean = when (scope) {
        ContestDupeScope.ONCE_PER_CONTEST -> true
        ContestDupeScope.ONCE_PER_BAND -> a.band == b.band
        ContestDupeScope.ONCE_PER_MODE -> a.mode == b.mode
        ContestDupeScope.ONCE_PER_BAND_MODE -> a.band == b.band && a.mode == b.mode
        ContestDupeScope.CUSTOM_PERIOD -> a.band == b.band && kotlin.math.abs(a.createdAt - b.createdAt) < 24 * 3600
    }

    private fun normalizeCall(call: String): String {
        val parts = call.trim().uppercase(Locale.US).split('/').filter(String::isNotBlank)
        return parts.firstOrNull { part -> part.any(Char::isDigit) && part.any(Char::isLetter) } ?: parts.firstOrNull().orEmpty()
    }
}
