package com.patmanak.proton.andcalendarbridge.sync

/**
 * A qualified adapter must honor the requested scope, limit and format and provide an ordered,
 * replay-safe traversal. Repeated (event, revision) values must have identical contents.
 * A traversal may observe concurrent remote changes; it is not a snapshot unless the adapter
 * establishes that guarantee. Opaque revisions cannot be ordered here. Read-only polling must
 * use repeated bounded reconciliation and must not claim global snapshot consistency. Writable
 * adapters additionally need a qualified revision/conflict strategy before dispatch is enabled.
 * An expired cursor is an error requiring explicit scoped reconciliation, not an empty page.
 * No missing record implies deletion. Network DTOs, keys and retained unknown fields stay here.
 *
 * dispatch must preserve remote unknown/unsupported fields or reject the operation. An ambiguous
 * failure is Unknown (or an exception), never NotApplied. There is no assumed create idempotency.
 * UPDATE/DELETE must enforce baseRevision at the remote mutation boundary through a verified
 * conditional-write mechanism. Checking the engine's cached revision cannot protect against a
 * concurrent remote edit. If safe conditional mutation is unavailable, do not advertise those
 * capabilities until a separately qualified conflict strategy exists; never blindly overwrite.
 */
interface RemoteCalendarAdapter {
    val format: AdapterFormat
    suspend fun fetch(request: PageRequest): RemotePage
    suspend fun dispatch(mutation: Mutation): DispatchResult
}

/**
 * Production implementations MUST durably commit or roll back each transaction as a whole and
 * isolate concurrent capture/access changes. A thrown callback commits nothing. If commit succeeds
 * but its acknowledgement is lost, replay sees the complete transaction. Returning before durable
 * commit is forbidden. A read-only store may reject all mutation writes until its outgoing schema
 * and capture lifecycle are implemented; it must never pretend such a write was persisted.
 *
 * A scope binds account, calendar and storage generation. Reject stale generations and foreign
 * records, including mutation.scope. Transaction values must be immutable snapshots. Never hold a
 * database transaction across network calls. A page's records, projection intents and checkpoint
 * commit together; mutations and unknown-outcome transitions use the same durable boundary.
 *
 * Local capture must persist intent before scheduling, preserve the base revision, and suppress
 * conflicting pending projection intents atomically. Blocked AND acknowledged intents remain
 * pending until explicit reconciliation completes; scheduler execution must not requeue them.
 * Claiming OutcomeUnknown admits an operation as in-flight. Access changes and claims must be
 * transactionally ordered: a revocation committed before admission blocks it; revocation after
 * admission cannot cancel/undo a potentially sent request. All engine callers share the account
 * executor; integration must use the same owner for account work and respect this access boundary.
 */
interface SyncStore {
    suspend fun <T> transaction(scope: CalendarScope, block: Transaction.() -> T): T

    interface Transaction {
        var checkpoint: PageCheckpoint?
        val access: CalendarAccess
        fun remote(eventId: EventId): RemoteChange?
        fun putRemote(change: RemoteChange)
        /**
         * Matches both the original target and an Acknowledged state's eventId. A create has no
         * target before dispatch; its acknowledged remote identity remains protected until explicit
         * reconciliation consumes the intent and establishes the corresponding provider mapping.
         */
        fun hasPendingMutation(eventId: EventId): Boolean
        fun putProjection(intent: ProjectionIntent)
        fun removeProjection(eventId: EventId)
        fun mutation(id: MutationId): Mutation?
        fun putMutation(mutation: Mutation)
    }
}
