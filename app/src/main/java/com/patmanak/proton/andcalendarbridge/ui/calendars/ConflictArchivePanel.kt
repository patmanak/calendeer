// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.ui.calendars

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.patmanak.proton.andcalendarbridge.R
import com.patmanak.proton.andcalendarbridge.data.importing.*
import com.patmanak.proton.andcalendarbridge.sync.*
import java.time.ZoneId
import java.time.DateTimeException
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Supply a bounded-height host (for example a Dialog). Nothing is stored in saved state. */
@Composable
fun ConflictArchivePanel(scope: CalendarScope, reader: ConflictArchiveReader, onClose: () -> Unit,
    modifier: Modifier = Modifier) {
    val jobs = rememberCoroutineScope()
    val controller = remember(scope, reader) { ConflictArchiveController(scope, reader, jobs) }
    DisposableEffect(controller) {
        controller.list()
        onDispose { controller.dispose() }
    }
    val state by controller.state.collectAsState()
    fun close() { controller.dispose(); onClose() }
    BackHandler { if (state.selected != null) controller.list() else close() }
    // Key scrolling by the view owner too: no contents or position cross account/generation changes.
    key(controller) {
        Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp).testTag(ConflictArchiveTags.PANEL),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.conflict_archive_title), style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f).semantics { heading() })
                TextButton(onClick = ::close, modifier = Modifier.heightIn(min = 48.dp).testTag(ConflictArchiveTags.CLOSE)) {
                    Text(stringResource(R.string.conflict_archive_close))
                }
            }
            Text(stringResource(R.string.conflict_archive_notice), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.selected != null) TextButton(onClick = { controller.list() },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(ConflictArchiveTags.BACK)) {
                Text(stringResource(R.string.conflict_archive_back))
            }
            TextButton(onClick = controller::refresh,
                enabled = state.status != ConflictArchiveStatus.LOADING &&
                    (state.detail == null || state.observationStatus != ConflictArchiveStatus.LOADING),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(ConflictArchiveTags.REFRESH)) {
                Text(stringResource(R.string.conflict_archive_refresh))
            }
            ArchiveStatus(state.status)
            if (state.selected == null && state.status == ConflictArchiveStatus.READY) {
                state.page?.entries?.forEachIndexed { index, ref ->
                    OutlinedButton(onClick = { controller.open(ref) }, shape = RoundedCornerShape(16.dp),
                        contentPadding = PaddingValues(16.dp),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(ConflictArchiveTags.ENTRY)) {
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stringResource(R.string.conflict_archive_record, index + 1), style = MaterialTheme.typography.labelLarge)
                            Text(stringResource(if (ref.kind == MutationKind.DELETE) R.string.conflict_archive_local_delete
                                else R.string.conflict_archive_local_edit), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
                state.page?.next?.let { next ->
                    NextPage { controller.list(next) }
                }
            }
            state.detail?.let { detail ->
                Text(stringResource(R.string.conflict_archive_partial_comparison), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                EventSnapshot(stringResource(R.string.conflict_archive_base), detail.base.content)
                EventSnapshot(stringResource(if (detail.ref.kind == MutationKind.DELETE) R.string.conflict_archive_local_delete
                    else R.string.conflict_archive_local_edit), detail.local)
                if (detail.ref.kind == MutationKind.DELETE) Text(stringResource(R.string.conflict_archive_delete_help),
                    style = MaterialTheme.typography.bodyMedium)
                HorizontalDivider()
                Text(stringResource(R.string.conflict_archive_observations), style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { heading() })
                Text(stringResource(R.string.conflict_archive_observations_help), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                ArchiveStatus(state.observationStatus, observations = true)
                state.observations?.entries?.forEach { observation -> Observation(observation) }
                state.observations?.next?.let { next -> NextPage { controller.observations(next) } }
            }
        }
    }
}

@Composable
private fun ArchiveStatus(status: ConflictArchiveStatus, observations: Boolean = false) {
    if (status == ConflictArchiveStatus.READY) return
    val resource = when (status) {
        ConflictArchiveStatus.LOADING -> R.string.conflict_archive_loading
        ConflictArchiveStatus.EMPTY -> if (observations) R.string.conflict_archive_no_observations else R.string.conflict_archive_empty
        ConflictArchiveStatus.INACTIVE -> R.string.conflict_archive_inactive
        ConflictArchiveStatus.MISSING -> R.string.conflict_archive_missing
        ConflictArchiveStatus.UNQUALIFIED -> R.string.conflict_archive_unknown_calendar
        else -> R.string.conflict_archive_unavailable
    }
    val tag = when (status) {
        ConflictArchiveStatus.LOADING -> ConflictArchiveTags.LOADING
        ConflictArchiveStatus.EMPTY -> ConflictArchiveTags.EMPTY
        ConflictArchiveStatus.INACTIVE -> ConflictArchiveTags.INACTIVE
        ConflictArchiveStatus.MISSING -> ConflictArchiveTags.MISSING
        else -> ConflictArchiveTags.ERROR
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (status == ConflictArchiveStatus.LOADING) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Text(stringResource(resource), style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag(tag).semantics { liveRegion = LiveRegionMode.Polite })
    }
}

@Composable
private fun NextPage(onClick: () -> Unit) {
    OutlinedButton(onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(ConflictArchiveTags.NEXT)) {
        Text(stringResource(R.string.conflict_archive_next))
    }
}

@Composable
private fun EventSnapshot(label: String, content: EventContent) {
    val locale = LocalConfiguration.current.locales[0]
    val formatted = remember(content.time, locale) { formatEventTime(content.time, locale) }
    val time = when {
        formatted == null -> stringResource(R.string.conflict_archive_event_date_unavailable)
        content.time is EventTime.AllDay -> stringResource(R.string.conflict_archive_all_day, formatted[0], formatted[1])
        else -> stringResource(R.string.conflict_archive_timed, formatted[0], formatted[1], formatted[2])
    }
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().testTag(ConflictArchiveTags.SNAPSHOT)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.semantics { heading() })
            Text(content.title.ifBlank { stringResource(R.string.conflict_archive_untitled) }, style = MaterialTheme.typography.titleMedium)
            Text(time, style = MaterialTheme.typography.bodyMedium)
            if (content.location.isNotBlank()) {
                Text(stringResource(R.string.conflict_archive_location), style = MaterialTheme.typography.labelLarge)
                Text(content.location, style = MaterialTheme.typography.bodyMedium)
            }
            if (content.description.isNotBlank()) {
                Text(stringResource(R.string.conflict_archive_description), style = MaterialTheme.typography.labelLarge)
                Text(content.description, style = MaterialTheme.typography.bodyMedium)
            }
            if (content.colorRgb != null || content.recurrence != null ||
                content.details?.let { it != EventDetails() } == true) {
                Text(stringResource(R.string.conflict_archive_hidden_properties), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (content.details == null) Text(stringResource(R.string.conflict_archive_unknown_details),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Observation(value: ConflictObservation) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()
        .testTag(ConflictArchiveTags.OBSERVATION)) {
        Text(stringResource(if (value.origin == ConflictObservationOrigin.CACHED) R.string.conflict_archive_cached
            else R.string.conflict_archive_received), style = MaterialTheme.typography.labelLarge)
        value.observedAt?.let { timestamp ->
            val locale = LocalConfiguration.current.locales[0]
            val formatted = remember(timestamp, locale) { formatCollectedAt(timestamp, locale) }
            Text(if (formatted == null) stringResource(R.string.conflict_archive_collection_date_unavailable)
                else stringResource(R.string.conflict_archive_collected, formatted), style = MaterialTheme.typography.bodySmall)
        }
        when (value.kind) {
            ConflictObservationKind.VERIFIED -> when (val change = value.change) {
                is RemoteChange.Upsert -> EventSnapshot(stringResource(R.string.conflict_archive_recorded_remote), change.content)
                is RemoteChange.Deleted -> Text(stringResource(R.string.conflict_archive_remote_deleted))
                null -> Text(stringResource(R.string.conflict_archive_unavailable))
            }
            ConflictObservationKind.DEFERRED -> Text(stringResource(if (value.deferredReason == DeferredEventReason.UNVERIFIED_CONTENT)
                R.string.conflict_archive_unverified else R.string.conflict_archive_unsupported))
            ConflictObservationKind.UNOBSERVED -> Text(stringResource(R.string.conflict_archive_unobserved))
            ConflictObservationKind.UNREADABLE -> Text(stringResource(R.string.conflict_archive_unreadable))
        }
    }
}

/** A valid retained Instant may exceed a display calendar's range. Preserve the record. */
private fun formatEventTime(time: EventTime, locale: Locale): List<String>? = try {
    when (time) {
        is EventTime.AllDay -> {
            val formatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
            listOf(formatter.format(time.start), formatter.format(time.endExclusive.minusDays(1)))
        }
        is EventTime.Timed -> {
            val formatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(locale).withZone(time.zone)
            listOf(formatter.format(time.start), formatter.format(time.end), time.zone.id)
        }
    }
} catch (_: DateTimeException) { null }

private fun formatCollectedAt(timestamp: Instant, locale: Locale): String? = try {
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(locale)
        .withZone(ZoneId.systemDefault()).format(timestamp)
} catch (_: DateTimeException) { null }

internal object ConflictArchiveTags {
    const val PANEL = "conflict_archive_panel"
    const val OPEN = "conflict_archive_open"
    const val CLOSE = "conflict_archive_close"
    const val BACK = "conflict_archive_back"
    const val REFRESH = "conflict_archive_refresh"
    const val ENTRY = "conflict_archive_entry"
    const val NEXT = "conflict_archive_next"
    const val LOADING = "conflict_archive_loading"
    const val EMPTY = "conflict_archive_empty"
    const val INACTIVE = "conflict_archive_inactive"
    const val MISSING = "conflict_archive_missing"
    const val ERROR = "conflict_archive_error"
    const val SNAPSHOT = "conflict_archive_snapshot"
    const val OBSERVATION = "conflict_archive_observation"
}
