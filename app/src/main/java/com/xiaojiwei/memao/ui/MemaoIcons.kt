package com.xiaojiwei.memao.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.xiaojiwei.memao.R
import com.xiaojiwei.memao.ui.theme.LocalMemaoColors

/** Semantic names isolate screens from the pinned Lucide resource filenames. */
enum class MmIcon(@param:DrawableRes val drawable: Int, val mirrorInRtl: Boolean = false) {
    NOTES(R.drawable.ic_lucide_notebook_text),
    MENU(R.drawable.ic_lucide_menu),
    DRAFT(R.drawable.ic_lucide_file_pen_line),
    ARCHIVE(R.drawable.ic_lucide_archive),
    SETTINGS(R.drawable.ic_lucide_settings),
    PLUS(R.drawable.ic_lucide_plus),
    BACK(R.drawable.ic_lucide_arrow_left, mirrorInRtl = true),
    SEARCH(R.drawable.ic_lucide_search),
    CLOSE(R.drawable.ic_lucide_x),
    DOWN(R.drawable.ic_lucide_chevron_down),
    EDIT(R.drawable.ic_lucide_square_pen),
    DELETE(R.drawable.ic_lucide_trash),
    IMAGE(R.drawable.ic_lucide_image),
    AUDIO(R.drawable.ic_lucide_audio_lines),
    PASTE(R.drawable.ic_lucide_clipboard_paste),
    TAG(R.drawable.ic_lucide_tag),
    EYE(R.drawable.ic_lucide_eye),
    CHECK(R.drawable.ic_lucide_check),
    REFRESH(R.drawable.ic_lucide_refresh_cw),
    LOCK(R.drawable.ic_lucide_lock_keyhole),
    COPY(R.drawable.ic_lucide_copy),
    MORE(R.drawable.ic_lucide_ellipsis),
    PLAY(R.drawable.ic_lucide_play),
    PAUSE(R.drawable.ic_lucide_pause),
    UNARCHIVE(R.drawable.ic_lucide_archive_restore),
    NEXT(R.drawable.ic_lucide_chevron_right, mirrorInRtl = true),
    EYE_OFF(R.drawable.ic_lucide_eye_off),
    FILE(R.drawable.ic_lucide_file),
    HEADING(R.drawable.ic_lucide_heading_1),
    BOLD(R.drawable.ic_lucide_bold),
    ITALIC(R.drawable.ic_lucide_italic),
    LIST(R.drawable.ic_lucide_list),
    ORDERED_LIST(R.drawable.ic_lucide_list_ordered),
    QUOTE(R.drawable.ic_lucide_quote),
    LINK(R.drawable.ic_lucide_link),
    TASK_LIST(R.drawable.ic_lucide_list_todo),
    UNCHECKED(R.drawable.ic_lucide_square),
    CHECKED(R.drawable.ic_lucide_square_check),
    PUBLIC(R.drawable.ic_lucide_globe),
    PROTECTED(R.drawable.ic_lucide_shield)
}

object MmIconSize {
    val Toolbar = 20.dp
    val Navigation = 24.dp
    val Empty = 32.dp
}

/** Labels belong to the enclosing control; this image is decorative. */
@Composable
fun LineIcon(
    icon: MmIcon,
    modifier: Modifier = Modifier,
    tint: Color = LocalMemaoColors.current.ink,
) {
    val mirror = icon.mirrorInRtl && LocalLayoutDirection.current == LayoutDirection.Rtl
    Image(
        painter = painterResource(icon.drawable),
        contentDescription = null,
        // An explicit caller size takes precedence over this toolbar default.
        modifier = modifier.size(MmIconSize.Toolbar).graphicsLayer {
            scaleX = if (mirror) -1f else 1f
        },
        colorFilter = ColorFilter.tint(tint),
    )
}
