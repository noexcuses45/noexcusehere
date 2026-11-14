package com.noexcusehere.vpn

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

/**
 * A user-facing message that is still a string resource plus arguments, so the layers that
 * produce messages (parsing, the VPN controller) don't need a Context and stay testable.
 */
data class UiText(@param:StringRes val id: Int, val args: List<Any> = emptyList()) {
    fun resolve(context: Context): String = context.getString(id, *args.toTypedArray())

    @Composable
    fun asString(): String = stringResource(id, *args.toTypedArray())
}

fun uiText(@StringRes id: Int, vararg args: Any): UiText = UiText(id, args.toList())
