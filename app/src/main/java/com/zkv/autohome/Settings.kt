package com.zkv.autohome

import android.content.Context

object Settings {
    private const val PREFS = "autohome"
    private fun sp(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun url(ctx: Context): String = (sp(ctx).getString("url", "") ?: "").trimEnd('/')
    fun token(ctx: Context): String = sp(ctx).getString("token", "") ?: ""
    fun hasConnection(ctx: Context): Boolean = url(ctx).isNotBlank() && token(ctx).isNotBlank()

    fun setConnection(ctx: Context, url: String, token: String) {
        sp(ctx).edit()
            .putString("url", url.trim().trimEnd('/'))
            .putString("token", token.trim())
            .apply()
    }
}
