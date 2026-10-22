// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

import okhttp3.OkHttpClient

/** No qualification listener or interruption capability in release. */
internal fun OkHttpClient.Builder.withDeleteNetworkQualification(): OkHttpClient.Builder = this
