package com.patmanak.proton.andcalendarbridge.data.calendar

import android.accounts.AbstractAccountAuthenticator
import android.accounts.Account
import android.accounts.AccountAuthenticatorResponse
import android.accounts.AccountManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.IBinder

/** Owns the Calendar Provider's account type. Proton sessions stay in the protected session store. */
class CalendarAccountAuthenticatorService : Service() {
    private lateinit var authenticator: CalendarAuthenticator
    override fun onCreate() { super.onCreate(); authenticator = CalendarAuthenticator(this) }
    override fun onBind(intent: Intent?): IBinder? =
        if (intent?.action == AccountManager.ACTION_AUTHENTICATOR_INTENT) authenticator.iBinder else null

    private class CalendarAuthenticator(context: Context) : AbstractAccountAuthenticator(context) {
        override fun addAccount(response: AccountAuthenticatorResponse?, accountType: String?, authTokenType: String?,
            requiredFeatures: Array<out String>?, options: Bundle?): Bundle = unsupported()
        override fun editProperties(response: AccountAuthenticatorResponse?, accountType: String?): Bundle = unsupported()
        override fun confirmCredentials(response: AccountAuthenticatorResponse?, account: Account?, options: Bundle?): Bundle =
            Bundle().apply { putBoolean(AccountManager.KEY_BOOLEAN_RESULT, false) }
        override fun getAuthToken(response: AccountAuthenticatorResponse?, account: Account?, authTokenType: String?,
            options: Bundle?): Bundle = unsupported()
        override fun getAuthTokenLabel(authTokenType: String?): String = ""
        override fun updateCredentials(response: AccountAuthenticatorResponse?, account: Account?, authTokenType: String?,
            options: Bundle?): Bundle = unsupported()
        override fun hasFeatures(response: AccountAuthenticatorResponse?, account: Account?, features: Array<out String>?): Bundle =
            Bundle().apply { putBoolean(AccountManager.KEY_BOOLEAN_RESULT, false) }
        private fun unsupported() = Bundle().apply {
            putInt(AccountManager.KEY_ERROR_CODE, AccountManager.ERROR_CODE_UNSUPPORTED_OPERATION)
            putString(AccountManager.KEY_ERROR_MESSAGE, "Use Calendeer to manage the connection.")
        }
    }
}
