package com.patmanak.proton.andcalendarbridge.sync

/** Local unlocked identity lookup; must not fetch metadata or start authentication. */
interface ReadOnlyCalendarRemote : RemoteCalendarAdapter {
    suspend fun currentAccount(): AccountId?
}

enum class RemoteImportFailure { AUTHENTICATION, NETWORK, UNSUPPORTED, LOCAL_KEYS, CAPACITY, NOT_FOUND, RETRY }

/** Closed diagnostics only; never wrap server messages, event contents or credentials. */
class RemoteImportException(val reason: RemoteImportFailure, val retryAfterMillis: Long? = null) :
    IllegalStateException("Calendar import: ${reason.name}")
