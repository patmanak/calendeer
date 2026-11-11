// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.data.calendar

import android.accounts.Account
import android.content.ContentResolver
import android.content.SyncResult
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.patmanak.proton.andcalendarbridge.data.importing.SystemSyncOutcome
import com.patmanak.proton.andcalendarbridge.data.importing.SystemSyncWork
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkInfo
import androidx.work.workDataOf
import com.patmanak.proton.andcalendarbridge.data.importing.CalendarImportWorker
import com.patmanak.proton.andcalendarbridge.data.importing.relaySystemSync
import java.util.concurrent.TimeUnit

/** Platform contract only: no session, Provider mutation or Proton request. */
@RunWith(AndroidJUnit4::class)
class CalendarSyncAdapterDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun registeredCalendarAdapterSupportsUploadsWithoutParallelRuns() {
        val adapter = ContentResolver.getSyncAdapterTypes().single {
            it.accountType == AndroidCalendarProjection.ACCOUNT_TYPE && it.authority == SystemSyncWork.AUTHORITY
        }
        assertTrue(adapter.supportsUploading())
        assertTrue(adapter.isUserVisible)
        assertFalse(adapter.allowParallelSyncs())
    }

    @Test fun foreignAccountIsRejectedBeforeAnyEnqueue() {
        val result = SyncResult()
        CalendarSyncAdapter(context).onPerformSync(Account("synthetic", "invalid.fixture"),
            Bundle(), SystemSyncWork.AUTHORITY, null, result)
        assertEquals(1L, result.stats.numAuthExceptions)
    }

    @Test fun androidReceivesRealOutcomeRatherThanEnqueueSuccess() {
        val adapter = CalendarSyncAdapter(context)
        for (outcome in SystemSyncOutcome.entries) {
            val result = SyncResult()
            adapter.report(outcome, result)
            assertEquals(outcome !in setOf(SystemSyncOutcome.SUCCESS, SystemSyncOutcome.DISABLED), result.hasError())
            if (outcome == SystemSyncOutcome.RETRY) assertEquals(1L, result.stats.numIoExceptions)
        }
    }

    @Test fun absentWorkCannotBeReportedAsCompleted() {
        assertEquals(SystemSyncOutcome.RETRY, SystemSyncWork.await(context, UUID.randomUUID(), 20))
    }

    @Test fun waitDeadlineLeavesIndependentWorkEnqueued() {
        val manager = WorkManager.getInstance(context)
        val request = OneTimeWorkRequestBuilder<CalendarImportWorker>()
            .setInitialDelay(1, TimeUnit.DAYS)
            .setInputData(workDataOf(SystemSyncWork.ACCOUNT to "synthetic-pending-${UUID.randomUUID()}"))
            .build()
        try {
            manager.enqueue(request).result.get(10, TimeUnit.SECONDS)
            assertEquals(SystemSyncOutcome.RETRY, SystemSyncWork.await(context, request.id, 20))
            assertEquals("Ending the Android wait must not cancel its independent executor",
                WorkInfo.State.ENQUEUED, manager.getWorkInfoById(request.id).get(10, TimeUnit.SECONDS).state)
        } finally { manager.cancelWorkById(request.id).result.get(10, TimeUnit.SECONDS) }
    }

    @Test fun interruptedRelayLeavesIndependentWorkEnqueued() {
        val manager = WorkManager.getInstance(context)
        val request = OneTimeWorkRequestBuilder<CalendarImportWorker>()
            .setInitialDelay(1, TimeUnit.DAYS)
            .setInputData(workDataOf(SystemSyncWork.ACCOUNT to "synthetic-pending-${UUID.randomUUID()}"))
            .build()
        try {
            manager.enqueue(request).result.get(10, TimeUnit.SECONDS)
            try {
                Thread.currentThread().interrupt()
                assertEquals(SystemSyncOutcome.RETRY, relaySystemSync({}, { SystemSyncWork.await(context, request.id) }))
                assertTrue(Thread.currentThread().isInterrupted)
            } finally { Thread.interrupted() }
            assertEquals(WorkInfo.State.ENQUEUED, manager.getWorkInfoById(request.id).get(10, TimeUnit.SECONDS).state)
        } finally { manager.cancelWorkById(request.id).result.get(10, TimeUnit.SECONDS) }
    }
}
