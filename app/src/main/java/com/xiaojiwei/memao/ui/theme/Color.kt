package com.xiaojiwei.memao.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

@Immutable
data class MemaoColors(
    val background: Color, val ink: Color, val secondary: Color, val divider: Color,
    val soft: Color, val strong: Color, val onStrong: Color, val danger: Color,
    val memoFooter: Color,
)
val LightColors = MemaoColors(
    Color(0xFFFFFFFF), Color(0xFF242629), Color(0xFF6B7076), Color(0xFFE9EBED),
    Color(0xFFF4F5F6), Color(0xFF202327), Color.White, Color(0xFFA12D34),
    memoFooter = Color(0xFF42464A),
)
val DarkColors = MemaoColors(
    Color(0xFF171819), Color(0xFFECEEEF), Color(0xFFA9AFB5), Color(0xFF34373B),
    Color(0xFF222529), Color(0xFFE7EAEE), Color(0xFF1B1E21), Color(0xFFF2A5AB),
    memoFooter = Color(0xFFC6C9CC),
)
val LocalMemaoColors = staticCompositionLocalOf { LightColors }
