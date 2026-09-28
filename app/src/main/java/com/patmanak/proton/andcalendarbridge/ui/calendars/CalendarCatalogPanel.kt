package com.patmanak.proton.andcalendarbridge.ui.calendars

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.patmanak.proton.andcalendarbridge.R
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarCatalogFailure
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarCatalogState
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarCatalogStatus

/** Calendar metadata only; a discovered calendar does not establish event or sync readiness. */
@Composable
fun CalendarCatalogPanel(
    state: CalendarCatalogState,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when (state.status) {
            CalendarCatalogStatus.SIGNED_OUT -> Text(stringResource(R.string.catalog_signed_out),
                modifier = Modifier.testTag(CalendarCatalogTags.SIGNED_OUT))
            CalendarCatalogStatus.LOADING -> Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(Modifier.size(24.dp))
                Text(stringResource(if (state.calendars.isEmpty()) R.string.catalog_loading else R.string.catalog_refreshing),
                    modifier = Modifier.testTag(CalendarCatalogTags.LOADING).semantics { liveRegion = LiveRegionMode.Polite })
            }
            CalendarCatalogStatus.READY -> if (state.calendars.isEmpty()) {
                Text(stringResource(R.string.catalog_empty),
                    modifier = Modifier.testTag(CalendarCatalogTags.EMPTY))
            }
            CalendarCatalogStatus.ERROR -> {
                Text(stringResource(failureResource(state.failure)), color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag(CalendarCatalogTags.ERROR).semantics { liveRegion = LiveRegionMode.Polite })
                if (state.calendars.isNotEmpty()) Text(stringResource(R.string.catalog_stale),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag(CalendarCatalogTags.STALE))
            }
        }
        if (state.status != CalendarCatalogStatus.SIGNED_OUT) {
            state.calendars.forEach { calendar ->
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(CalendarCatalogTags.ROW),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val color = calendar.colorRgb?.let { Color(0xFF000000 or it.toLong()) }
                        ?: MaterialTheme.colorScheme.outlineVariant
                    Box(Modifier.size(20.dp).background(color, CircleShape))
                    Text(calendar.title.ifBlank { stringResource(R.string.catalog_untitled) },
                        style = MaterialTheme.typography.bodyLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
            if (state.isRetryBlocked) Text(stringResource(R.string.catalog_retry_wait),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            TextButton(onClick = onRefresh,
                enabled = state.canRefresh && !state.isRetryBlocked && state.status != CalendarCatalogStatus.LOADING,
                modifier = Modifier.heightIn(min = 48.dp).testTag(CalendarCatalogTags.REFRESH)) {
                Text(stringResource(R.string.catalog_refresh))
            }
        }
        Text(stringResource(R.string.catalog_sync_unavailable), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun failureResource(failure: CalendarCatalogFailure?): Int = when (failure) {
    CalendarCatalogFailure.AUTHENTICATION_REQUIRED -> R.string.catalog_auth_required
    CalendarCatalogFailure.UNREACHABLE -> R.string.catalog_unreachable
    CalendarCatalogFailure.TIMED_OUT -> R.string.catalog_timeout
    CalendarCatalogFailure.RATE_LIMITED -> R.string.catalog_rate_limited
    CalendarCatalogFailure.FORBIDDEN -> R.string.catalog_forbidden
    CalendarCatalogFailure.UNSUPPORTED_RESPONSE -> R.string.catalog_unsupported
    CalendarCatalogFailure.TRY_AGAIN, null -> R.string.catalog_try_again
}

internal object CalendarCatalogTags {
    const val SIGNED_OUT = "catalog_signed_out"
    const val LOADING = "catalog_loading"
    const val EMPTY = "catalog_empty"
    const val ERROR = "catalog_error"
    const val STALE = "catalog_stale"
    const val ROW = "catalog_row"
    const val REFRESH = "catalog_refresh"
}
