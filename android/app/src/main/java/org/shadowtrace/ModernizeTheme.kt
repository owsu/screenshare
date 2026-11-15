package org.shadowtrace

import android.graphics.Color

/**
 * Native adaptation of AdminMart's Modernize React Lite, listed on 21st.dev.
 * Theme reference: 4de5f2a10f6b64fdf8090bbd5a25ad52fcd67226,
 * package/src/theme/DefaultColors.js and Typography.js (MIT).
 * Copyright (c) 2025 AdminMart. See assets/licenses/modernize.txt.
 * Android spacing and primary text/action contrast are adapted for a phone.
 */
object ModernizeTheme {
    val primary = Color.parseColor("#5D87FF")
    val action = Color.parseColor("#3561D9")
    val primaryLight = Color.parseColor("#ECF2FF")
    val secondaryLight = Color.parseColor("#E8F7FF")
    val ink = Color.parseColor("#2A3547")
    val muted = Color.parseColor("#5A6A85")
    val canvas = Color.parseColor("#F2F6FA")
    val border = Color.parseColor("#E5EAEF")
}
