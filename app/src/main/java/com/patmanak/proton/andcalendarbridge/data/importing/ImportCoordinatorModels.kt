package com.patmanak.proton.andcalendarbridge.data.importing

import com.patmanak.proton.andcalendarbridge.sync.*

data class ImportUiState(val selections: List<ImportStore.Selection> = emptyList(),
    val hasPermission: Boolean = false, val authenticated: Boolean = false, val busy: Boolean = false,
    val selectionsLoaded: Boolean = false)
enum class CreateAssociationResult { INACTIVE, UNRESOLVED, WAITING_IMPORT, WAITING_PROVIDER, ASSOCIATED }
enum class LocalEditCaptureResult { INACTIVE, UNQUALIFIED, RETAINED, CONFLICT, UNAVAILABLE }
sealed interface RetainedLocalChangeListing {
    data class Available(val page: RetainedLocalChangePage) : RetainedLocalChangeListing {
        override fun toString() = "RetainedLocalChangeListing.Available(REDACTED)"
    }
    data object Inactive : RetainedLocalChangeListing
    data object Unqualified : RetainedLocalChangeListing
    data object Unavailable : RetainedLocalChangeListing
}
sealed interface LocalEditInspection {
    data class Available(val review: LocalEditReview) : LocalEditInspection {
        override fun toString() = "LocalEditInspection.Available(REDACTED)"
    }
    data object Inactive : LocalEditInspection
    data object Unqualified : LocalEditInspection
    data object Unavailable : LocalEditInspection
}
enum class LocalTitlePreparationResult { INACTIVE, UNQUALIFIED, RETAINED, HISTORICAL, CONFLICT, UNSUPPORTED, UNAVAILABLE }
sealed interface LocalDeleteInspection {
    data class Available(val review: LocalDeleteReview) : LocalDeleteInspection {
        override fun toString() = "LocalDeleteInspection.Available(REDACTED)"
    }
    data object Inactive : LocalDeleteInspection
    data object Unqualified : LocalDeleteInspection
    data object Unavailable : LocalDeleteInspection
}
enum class LocalCreatePreparationResult { INACTIVE, UNQUALIFIED, RETAINED, UNSUPPORTED, UNAVAILABLE }
enum class LocalCreateSubmissionResult { INACTIVE, UNQUALIFIED, RETAINED, UNRESOLVED, UNSUPPORTED, UNAVAILABLE }
enum class CapturedCreateBindingResult { INACTIVE, UNRESOLVED, RETAINED }
enum class CapturedCreateAttachmentResult { INACTIVE, UNRESOLVED, ATTACHED }
enum class CapturedCreateBaselineResult { INACTIVE, UNRESOLVED, RETAINED }
enum class CapturedCreateReconciliationResult { INACTIVE, UNRESOLVED, UNSUPPORTED, UNCHANGED, RETAINED, CONFLICT }
enum class CapturedCreateAcknowledgementResult { INACTIVE, UNRESOLVED, UNSUPPORTED, ACKNOWLEDGED }
enum class CapturedCreateCleanResult { INACTIVE, UNRESOLVED, CLEAN }
enum class CapturedCreateHandoffResult { INACTIVE, UNRESOLVED, WAITING, HANDED_OFF }
enum class TitleUpdateAcknowledgementResult { INACTIVE, UNRESOLVED, ACKNOWLEDGED }
enum class TitleUpdateCompletionResult { INACTIVE, UNRESOLVED, COMPLETED }
enum class LocalTitleAttemptRetentionResult { INACTIVE, UNQUALIFIED, UNSUPPORTED, UNRESOLVED, RETAINED, HISTORICAL, CONFLICT, UNAVAILABLE }
sealed interface LocalTitleAttemptPreparation {
    data class Prepared(val record: TitleUpdateAttemptRecord) : LocalTitleAttemptPreparation {
        override fun toString() = "LocalTitleAttemptPreparation.Prepared(REDACTED)"
    }
    data object Inactive : LocalTitleAttemptPreparation
    data object Unqualified : LocalTitleAttemptPreparation
    data object Unsupported : LocalTitleAttemptPreparation
    data object Unresolved : LocalTitleAttemptPreparation
    data object Conflict : LocalTitleAttemptPreparation
    data object Unavailable : LocalTitleAttemptPreparation
}
