// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.data.calendar

import android.content.Context
import android.annotation.SuppressLint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Extends appearance preferences; not a synchronization consent or event journal. */
class EventColorPreferences(context: Context, private val changed: () -> Unit) {
    private val preferences = context.applicationContext.getSharedPreferences("appearance", Context.MODE_PRIVATE)
    private val mutableMode = MutableStateFlow(read(context))
    val mode: StateFlow<EventColorMode> = mutableMode

    @SuppressLint("UseKtx") // Preserve commit's Boolean result before scheduling durable work.
    fun setMode(mode: EventColorMode): Boolean {
        // Persist before scheduling, including rapid consecutive choices.
        if (!preferences.edit().putString(KEY, mode.name).commit()) return false
        mutableMode.value = mode
        return try { changed(); true } catch (_: Exception) { false }
    }

    companion object {
        private const val KEY = "event_color_mode"
        fun read(context: Context): EventColorMode = context.applicationContext
            .getSharedPreferences("appearance", Context.MODE_PRIVATE).getString(KEY, null)
            ?.let { stored -> EventColorMode.entries.firstOrNull { it.name == stored } } ?: EventColorMode.EVENT
    }
}
