// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Patmanak
// Adapted from Contako; see proton-auth/PROVENANCE.md.
package com.patmanak.proton.andcalendarbridge.protonauth

import me.proton.core.network.domain.ApiClient
import me.proton.core.network.domain.client.ClientVersionValidator

/**
 * Honest, non-official identity for the Proton Core compile surface.
 *
 * Uses a third-party client identity; server acceptance must be qualified separately.
 */
class CalendarBridgeApiClient private constructor(
    releaseVersion: String,
    private val onForceUpdate: () -> Unit,
) : ApiClient {
    override val appVersionHeader: String = identityForReleaseVersion(releaseVersion)
    override val userAgent: String = "CalendarBridge/$releaseVersion (Android)"
    override val enableDebugLogging: Boolean = false

    override suspend fun shouldUseDoh(): Boolean = false

    override fun forceUpdate(errorMessage: String) {
        // The remote message is deliberately neither retained nor forwarded.
        onForceUpdate()
    }

    companion object {
        const val THIRD_PARTY_PRODUCT_PREFIX: String = "Other_"
        const val CALENDAR_BRIDGE_METADATA: String = "+andcalendarbridge"
        private val RELEASE_VERSION = Regex("^[0-9]+\\.[0-9]+\\.[0-9]+$")

        internal fun identityForReleaseVersion(releaseVersion: String): String {
            require(RELEASE_VERSION.matches(releaseVersion))
            return "$THIRD_PARTY_PRODUCT_PREFIX$releaseVersion$CALENDAR_BRIDGE_METADATA"
        }

        fun forReleaseVersion(
            releaseVersion: String,
            onForceUpdate: () -> Unit = {},
        ): CalendarBridgeApiClient {
            identityForReleaseVersion(releaseVersion)
            return CalendarBridgeApiClient(releaseVersion, onForceUpdate)
        }
    }
}

/**
 * Proton Core's stock validator accepts only Proton's `product@version` grammar. This adapter instead
 * uses the generic third-party form and keeps this exception deliberately exact.
 */
class CalendarBridgeClientVersionValidator private constructor(
    private val expectedIdentity: String,
) : ClientVersionValidator {
    override fun validate(versionName: String?): Boolean = versionName == expectedIdentity

    companion object {
        fun forReleaseVersion(releaseVersion: String): CalendarBridgeClientVersionValidator =
            CalendarBridgeClientVersionValidator(
                CalendarBridgeApiClient.identityForReleaseVersion(releaseVersion),
            )
    }
}
