package app.shackcq.mobile.contest

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

enum class ContestWorkspacePage { SETUP, LOGGING, REVIEW, NETWORK }

enum class ContestPanel(val label: String) {
    QSO_ENTRY("QSO ENTRY"), BAND_MAP("BAND MAP"), CLUSTER("CLUSTER / SPOTS"),
    MULTIPLIERS("MULTIPLIERS"), SCORE_RATE("SCORE & RATE"), RECENT_QSOS("RECENT QSOS"),
    KEYER_HOTKEYS("KEYER / HOTKEY STRIP"), NETWORK_STATUS("NETWORK STATUS")
}

enum class ContestPanelDensity { COMPACT, NORMAL, DENSE }

data class ContestPanelLayout(
    val panels: List<ContestPanel> = ContestPanel.entries,
    val density: ContestPanelDensity = ContestPanelDensity.NORMAL,
)

data class ContestReviewRow(
    val id: String,
    val callsign: String,
    val createdAt: Long,
    val frequencyHz: Long,
    val band: String,
    val mode: String,
    val rstSent: String,
    val rstReceived: String,
    val networkOrigin: Boolean = false,
    val duplicate: Boolean = false,
    val invalid: Boolean = false,
    val reviewRequired: Boolean = false,
    val zeroPoint: Boolean = false,
    val mergeState: String = "STAGED",
    val issue: String = "",
)

data class ContestBandMapRow(
    val id: String,
    val callsign: String,
    val frequencyHz: Long,
    val band: String,
    val status: String,
    val observedEpoch: Long = 0,
    val mode: String = "",
    val country: String = "",
    val cqZone: Int = 0,
    val spotter: String = "",
    val comment: String = "",
    val source: String = "",
)

data class ContestNetworkPeer(
    val station: String,
    val address: String,
    val operatorCall: String,
    val version: String,
    val contestName: String,
    val lastSeen: Long,
    val trusted: Boolean,
)

data class ContestNetworkState(
    val enabled: Boolean = false,
    val armed: Boolean = false,
    val active: Boolean = false,
    val mode: String = "OFF",
    val bindAddress: String = "127.0.0.1",
    val port: Int = 12070,
    val nodeIdentity: String = "ShackCQ",
    val lanOptIn: Boolean = false,
    val peers: List<ContestNetworkPeer> = emptyList(),
    val counters: Map<String, Long> = emptyMap(),
    val lastError: String = "",
)

data class ContestWorkspaceState(
    val session: ContestSession,
    val definition: ContestDefinition,
    val definitions: List<ContestDefinition> = listOf(definition),
    val page: ContestWorkspacePage = ContestWorkspacePage.SETUP,
    val callsign: String = "",
    val rstSent: String = "59",
    val rstReceived: String = "59",
    val receivedExchange: Map<ContestExchangeField, String> = emptyMap(),
    val exchange: String = "",
    val dupe: ContestDupeState = ContestDupeState.UNKNOWN,
    val newMultipliers: Set<ContestMultiplierType> = emptySet(),
    val score: ContestScoreSnapshot = ContestScoreSnapshot(),
    val validation: List<ContestValidationIssue> = emptyList(),
    val wavelogBinding: String = "LOCAL LOG · canonical mutation adapter",
    val operatingBand: String = "UNAVAILABLE",
    val operatingMode: String = "UNAVAILABLE",
    val operatingFrequencyHz: Long = 0,
    val keyerStatus: String = "SAFE",
    val layout: ContestPanelLayout = ContestPanelLayout(),
    val bandMapRows: List<ContestBandMapRow> = emptyList(),
    val clusterRows: List<ContestBandMapRow> = emptyList(),
    val reviewRows: List<ContestReviewRow> = emptyList(),
    val reviewHasMore: Boolean = false,
    val network: ContestNetworkState = ContestNetworkState(),
    val exportPreview: String = "",
    val statusMessage: String = "Contest setup ready; nothing is armed",
    val scpStatus: ScpStatus = ScpStatus(),
    val scpSuggestions: List<ScpSuggestion> = emptyList(),
)

data class ContestWorkspaceCallbacks(
    val onPage: (ContestWorkspacePage) -> Unit = {},
    val onDefinition: (ContestDefinitionId) -> Unit = {},
    val onNewSession: () -> Unit = {},
    val onCloneSession: () -> Unit = {},
    val onSession: (ContestSession) -> Unit = {},
    val onSaveSession: () -> Unit = {},
    val onPauseSession: () -> Unit = {},
    val onCloseSession: () -> Unit = {},
    val onCallsign: (String) -> Unit = {},
    val onRstSent: (String) -> Unit = {},
    val onRstReceived: (String) -> Unit = {},
    val onExchange: (String) -> Unit = {},
    val onExchangeField: (ContestExchangeField, String) -> Unit = { _, _ -> },
    val onLog: () -> Unit = {},
    val onClear: () -> Unit = {},
    val onEditQso: (String, String, String, String) -> Unit = { _, _, _, _ -> },
    val onDeleteQso: (String) -> Unit = {},
    val onMergeToLogbook: () -> Unit = {},
    val onRole: (ContestOperatingRole) -> Unit = {},
    val onStartSession: () -> Unit = {},
    val onKeyerIntent: (ContestKeyerIntent) -> Unit = {},
    val onEnterMessage: () -> Unit = {},
    val onLayout: (ContestPanelLayout) -> Unit = {},
    val onNetworkConfig: (Boolean, String, Boolean, String) -> Unit = { _, _, _, _ -> },
    val onNetworkStart: () -> Unit = {},
    val onNetworkStop: () -> Unit = {},
    val onPeerTrust: (String, Boolean) -> Unit = { _, _ -> },
    val onTrustedModeReview: () -> Unit = {},
    val onExport: (String) -> Unit = {},
    val onOpenLogbook: () -> Unit = {},
    val onOpenSettings: () -> Unit = {},
    val onRefreshScp: () -> Unit = {},
    val onDeleteScp: () -> Unit = {},
)

@Composable fun ContestWorkspace(state: ContestWorkspaceState, callbacks: ContestWorkspaceCallbacks, modifier: Modifier = Modifier) {
    val wide = LocalConfiguration.current.screenWidthDp >= 900
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
    Column(modifier.fillMaxSize().semantics { contentDescription = "Contest workspace" }) {
        ScrollableTabRow(
            selectedTabIndex = ContestWorkspacePage.entries.indexOf(state.page),
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            edgePadding = 8.dp,
        ) {
            ContestWorkspacePage.entries.forEach { page ->
                Tab(
                    selected = state.page == page,
                    onClick = { callbacks.onPage(page) },
                    selectedContentColor = MaterialTheme.colorScheme.primary,
                    unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.semantics { contentDescription = "Contest ${page.name.lowercase()} tab" },
                    text = { Text(page.name, maxLines = 1) },
                )
            }
        }
        when (state.page) {
            ContestWorkspacePage.SETUP -> ContestSetupScreen(state, callbacks)
            ContestWorkspacePage.LOGGING -> ContestLoggingScreen(state, callbacks, Modifier.fillMaxSize(), wide)
            ContestWorkspacePage.REVIEW -> ContestReviewScreen(state, callbacks)
            ContestWorkspacePage.NETWORK -> ContestNetworkScreen(state, callbacks)
        }
    }
    }
}

@Composable internal fun ContestScorePanel(state: ContestWorkspaceState, modifier: Modifier = Modifier) {
    Card(modifier.fillMaxWidth().semantics { contentDescription = "Claimed score and multipliers" }) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("LOCAL CLAIMED SCORE", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(state.score.claimedScore.toString(), style = MaterialTheme.typography.headlineLarge)
            Text("${state.score.scoredQsos} scored · ${state.score.points} points · ${state.score.status.name}")
            state.score.multipliers.forEach { (type, count) -> Text("${type.name}: $count") }
            Text("10 min ${"%.1f".format(state.score.rate.last10MinutesPerHour)} Q/h · 60 min ${"%.1f".format(state.score.rate.last60MinutesPerHour)} Q/h")
            Text("Best hour ${"%.1f".format(state.score.rate.best60MinutesPerHour)} Q/h · ${state.score.reviewQsos} review · ${state.score.duplicates} dupes")
        }
    }
}
