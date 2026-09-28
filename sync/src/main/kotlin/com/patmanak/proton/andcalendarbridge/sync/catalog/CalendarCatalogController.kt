package com.patmanak.proton.andcalendarbridge.sync.catalog

import com.patmanak.proton.andcalendarbridge.sync.AccountId

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class CalendarCatalogStatus { SIGNED_OUT, LOADING, READY, ERROR }

data class CalendarCatalogState(
    val status: CalendarCatalogStatus = CalendarCatalogStatus.SIGNED_OUT,
    val calendars: List<CalendarSummary> = emptyList(),
    val failure: CalendarCatalogFailure? = null,
    val canRefresh: Boolean = false,
    val isRetryBlocked: Boolean = false,
    val account: AccountId? = null,
) {
    override fun toString() = "CalendarCatalogState(status=$status, count=${calendars.size}, failure=$failure)"
}

/** Main/serialized-dispatcher owner. In-memory metadata survives view recreation, not process death. */
class CalendarCatalogController(
    private val port: CalendarCatalogPort,
    private val scope: CoroutineScope,
    private val monotonicMillis: () -> Long,
) {
    private val mutableState = MutableStateFlow(CalendarCatalogState())
    val state: StateFlow<CalendarCatalogState> = mutableState.asStateFlow()
    private var authenticated = false
    private var generation = 0L
    private var loading: Job? = null
    private var cooldown: Job? = null

    fun setAuthenticated(value: Boolean) {
        if (authenticated == value) return
        authenticated = value
        generation++
        loading?.cancel()
        cooldown?.cancel()
        loading = null
        cooldown = null
        mutableState.value = CalendarCatalogState()
        if (value) refresh()
    }

    fun refresh() {
        if (!authenticated || loading?.isActive == true || state.value.isRetryBlocked) return
        val revision = ++generation
        mutableState.value = state.value.copy(
            status = CalendarCatalogStatus.LOADING, failure = null, canRefresh = false,
        )
        loading = scope.launch {
            val result = try {
                port.load()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                CalendarCatalogResult.Failure(CalendarCatalogFailure.TRY_AGAIN)
            }
            if (!authenticated || revision != generation) return@launch
            when (result) {
                is CalendarCatalogResult.Success -> mutableState.value = CalendarCatalogState(
                    status = CalendarCatalogStatus.READY,
                    calendars = result.calendars.toList(),
                    canRefresh = true,
                    account = result.account,
                )
                is CalendarCatalogResult.Failure -> {
                    val denied = result.category == CalendarCatalogFailure.AUTHENTICATION_REQUIRED ||
                        result.category == CalendarCatalogFailure.FORBIDDEN
                    val wait = result.retryAfterMillis ?: if (result.category == CalendarCatalogFailure.RATE_LIMITED)
                        30_000L else null
                    mutableState.value = CalendarCatalogState(
                        status = CalendarCatalogStatus.ERROR,
                        calendars = if (denied) emptyList() else state.value.calendars,
                        failure = result.category,
                        canRefresh = wait == null,
                        isRetryBlocked = wait != null,
                        account = if (denied) null else state.value.account,
                    )
                    if (wait != null) releaseRetryAfter(wait.coerceAtLeast(1_000), revision)
                }
            }
        }
    }

    private fun releaseRetryAfter(wait: Long, revision: Long) {
        val now = monotonicMillis()
        val deadline = if (Long.MAX_VALUE - now < wait) Long.MAX_VALUE else now + wait
        cooldown = scope.launch {
            var remaining = (deadline - monotonicMillis()).coerceAtLeast(0)
            while (remaining > 0) {
                delay(minOf(remaining, 60_000))
                remaining = (deadline - monotonicMillis()).coerceAtLeast(0)
            }
            if (authenticated && revision == generation) mutableState.value = state.value.copy(
                isRetryBlocked = false, canRefresh = true,
            )
        }
    }
}
