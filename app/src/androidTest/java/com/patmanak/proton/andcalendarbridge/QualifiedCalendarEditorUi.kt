// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale
import org.junit.Assert.assertTrue

internal object QualifiedCalendarEditorUi {
    /** Own and release every node returned by the framework; no content escapes the test. */
    @Suppress("DEPRECATION")
    fun qualifiedRoot(root: AccessibilityNodeInfo?, editor: String, title: String, deletionMessage: String,
        confirmation: Boolean, click: Boolean): Boolean {
        if (root == null) return false
        val nodes = ArrayList<AccessibilityNodeInfo>()
        try {
            fun walk(node: AccessibilityNodeInfo, depth: Int) {
                nodes.add(node)
                assertTrue("Bound the editor accessibility tree", nodes.size <= 512 && depth <= 32)
                for (index in 0 until node.childCount) node.getChild(index)?.let { walk(it, depth + 1) }
            }
            walk(root, 0)
            if (root.packageName?.toString() != editor) return false
            val action = if (confirmation) {
                val positive = nodes.filter { it.viewIdResourceName == "android:id/button1" }
                val negative = nodes.filter { it.viewIdResourceName == "android:id/button2" }
                val messages = nodes.filter { it.viewIdResourceName == "android:id/message" }
                if (messages.size != 1 || messages.single().text?.toString() != deletionMessage ||
                    !messages.single().isVisibleToUser || positive.size != 1 || negative.size != 1 || positive.single().text?.toString() != "OK" ||
                    negative.single().text?.toString()?.lowercase(Locale.ROOT) !in setOf("cancel", "annuler")) return false
                positive.single()
            } else {
                if (nodes.count { it.text?.toString() == title } != 1) return false
                val delete = nodes.filter { it.viewIdResourceName == "$editor:id/info_action_delete" }
                if (delete.size != 1) return false
                delete.single()
            }
            if (!action.isEnabled || !action.isClickable || !action.isVisibleToUser || action.packageName?.toString() != editor) return false
            return !click || action.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } finally {
            if (nodes.isEmpty()) root.recycle() else nodes.forEach { it.recycle() }
        }
    }

}
