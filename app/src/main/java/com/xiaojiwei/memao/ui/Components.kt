package com.xiaojiwei.memao.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.xiaojiwei.memao.R
import com.xiaojiwei.memao.ui.theme.LocalMemaoColors

@Composable
fun MmIconButton(icon: MmIcon, label: String, enabled: Boolean = true, danger: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = LocalMemaoColors.current
    Box(modifier.size(48.dp).alpha(if(enabled) 1f else .35f).clip(RoundedCornerShape(8.dp))
        .clickable(enabled=enabled,role=Role.Button,onClick=onClick).semantics { contentDescription=label }, contentAlignment=Alignment.Center) {
        LineIcon(icon,tint=if(danger)c.danger else c.ink)
    }
}

@Composable
fun MmButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = false, enabled: Boolean = true, danger: Boolean = false, icon: MmIcon? = null) {
    val c = LocalMemaoColors.current
    Row(modifier.heightIn(min=48.dp).alpha(if(enabled)1f else .4f).clip(RoundedCornerShape(8.dp))
        .then(if(primary)Modifier.background(c.strong) else Modifier)
        .clickable(enabled=enabled,role=Role.Button,onClick=onClick).padding(horizontal=14.dp,vertical=10.dp),
        horizontalArrangement=Arrangement.Center,verticalAlignment=Alignment.CenterVertically) {
        val color=if(primary)c.onStrong else if(danger)c.danger else c.ink
        if(icon!=null){LineIcon(icon,tint=color);if(label.isNotBlank())Spacer(Modifier.width(8.dp))}
        if(label.isNotBlank())Text(label,color=color,fontSize=13.sp)
    }
}

@Composable
fun MmField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    error: String? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    trailing: (@Composable () -> Unit)? = null,
    singleLine: Boolean = true,
    inputTag: String? = null,
    enabled: Boolean = true,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    inputModifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
) {
    val c = LocalMemaoColors.current
    var focused by remember { mutableStateOf(false) }
    Column(modifier) {
        if(label.isNotBlank()){Text(label,color=c.secondary,style=MaterialTheme.typography.bodySmall);Spacer(Modifier.height(8.dp))}
        Row(Modifier.fillMaxWidth().border(1.dp,if(error!=null)c.danger else if(focused)c.ink else c.divider,RoundedCornerShape(8.dp)).padding(start=if(leading==null)12.dp else 4.dp,end=4.dp),verticalAlignment=Alignment.CenterVertically) {
            leading?.invoke()
            BasicTextField(value,onValueChange,modifier=Modifier.weight(1f).heightIn(min=54.dp).then(if(inputTag!=null)Modifier.testTag(inputTag)else Modifier).then(inputModifier).onFocusChanged {focused=it.isFocused}.padding(vertical=14.dp),
                textStyle=MaterialTheme.typography.bodyLarge.copy(color=c.ink),singleLine=singleLine,enabled=enabled,visualTransformation=visualTransformation,keyboardOptions=keyboardOptions,keyboardActions=keyboardActions,cursorBrush=SolidColor(c.ink),
                // The caret is shorter than bodyLarge's 30.sp line and would otherwise
                // sit against the top of the field. Center it with the placeholder.
                decorationBox={inner -> Box(contentAlignment=Alignment.CenterStart) {if(value.isEmpty())Text(placeholder,color=c.secondary,fontSize=13.sp);inner()}})
            trailing?.invoke()
        }
        if(error!=null){Spacer(Modifier.height(6.dp));Text(error,color=c.danger,style=MaterialTheme.typography.bodySmall)}
    }
}

@Composable
fun MmRule() { Box(Modifier.fillMaxWidth().height(1.dp).background(LocalMemaoColors.current.divider)) }

@Composable
internal fun MemaoBrandTitle(
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    iconSize: Dp = 24.dp,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MemaoAppIcon(iconSize)
        Text(stringResource(R.string.app_name), color = LocalMemaoColors.current.ink, style = style)
    }
}

@Composable
private fun MemaoAppIcon(size: Dp) {
    Box(
        modifier = Modifier.size(size).background(
            colorResource(R.color.memao_brand_background),
            RoundedCornerShape(percent = 25),
        ),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_memao_brand_mark),
            contentDescription = null,
            modifier = Modifier.size(size * 0.6f),
        )
    }
}

// The active pane owns its fixed page bar; compact navigation joins that same row.
val LocalOpenNavigationDrawer=compositionLocalOf<(() -> Unit)?> {null}

@Composable
fun MmTopBar(title: String, onBack: (() -> Unit)? = null, filterTag: String? = null, filterUntagged: Boolean = false, onClearTag: () -> Unit = {}, actions: @Composable RowScope.() -> Unit = {}) {
    val c=LocalMemaoColors.current
    val openDrawer=LocalOpenNavigationDrawer.current
    val filterLabel=if(filterUntagged)"无标签" else filterTag?.let {"#$it"}
    Column(Modifier.testTag("pageTopBar")) {
        Row(Modifier.fillMaxWidth().heightIn(min=64.dp).padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically) {
            if(openDrawer!=null) {
                Box(Modifier.size(48.dp).testTag("drawerToggle").clip(RoundedCornerShape(8.dp))
                    .clickable(role=Role.Button,onClick=openDrawer).semantics {contentDescription="打开导航菜单"},
                    contentAlignment=Alignment.Center) {
                    MemaoAppIcon(32.dp)
                }
            }
            if(onBack!=null)MmIconButton(MmIcon.BACK,"返回",onClick=onBack)
            else Spacer(Modifier.width(if(openDrawer==null)12.dp else 8.dp))
            Row(Modifier.weight(1f),verticalAlignment=Alignment.CenterVertically) {
                Text(title,(if(filterLabel==null)Modifier.weight(1f)else Modifier).testTag("pageTitle"),color=c.ink,style=MaterialTheme.typography.titleMedium,maxLines=2,overflow=TextOverflow.Ellipsis)
                if(filterLabel!=null) {
                    Spacer(Modifier.width(8.dp))
                    MmFilterChip(filterLabel,onClearTag,Modifier.weight(1f,fill=false).testTag("clearTagFilter"))
                }
            }
            actions()
        }
        MmRule()
    }
}

@Composable
internal fun MmFilterChip(
    label: String,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    clearDescription: String = "清除筛选，展示全部笔记",
) {
    val c=LocalMemaoColors.current
    Box(modifier.heightIn(min=48.dp)
        .clickable(role=Role.Button,onClickLabel=clearDescription,onClick=onClear)
        .semantics {contentDescription="$label，$clearDescription"},contentAlignment=Alignment.CenterStart) {
        Row(Modifier.border(1.dp,c.divider,RoundedCornerShape(50)).padding(horizontal=10.dp,vertical=3.dp),verticalAlignment=Alignment.CenterVertically) {
            Text(label,Modifier.weight(1f,fill=false),color=c.ink,style=MaterialTheme.typography.bodyMedium,maxLines=1,overflow=TextOverflow.Ellipsis)
            Spacer(Modifier.width(6.dp))
            LineIcon(MmIcon.CLOSE,Modifier.size(14.dp),c.secondary)
        }
    }
}

@Composable
fun MmEmpty(title: String, message: String, modifier: Modifier = Modifier, icon: MmIcon = MmIcon.NOTES, action: (@Composable () -> Unit)? = null) {
    val c=LocalMemaoColors.current
    Column(modifier.fillMaxSize().padding(28.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center) {
        LineIcon(icon,Modifier.size(MmIconSize.Empty),c.secondary);Spacer(Modifier.height(20.dp))
        Text(title,color=c.ink,style=MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp));Text(message,color=c.secondary,style=MaterialTheme.typography.bodySmall)
        if(action!=null){Spacer(Modifier.height(24.dp));action()}
    }
}

@Composable
fun MmDialog(title: String, message: String = "", onDismiss: () -> Unit, actions: @Composable RowScope.() -> Unit, body: @Composable ColumnScope.() -> Unit = {}) {
    val c=LocalMemaoColors.current
    Dialog(onDismissRequest=onDismiss) {
        Column(Modifier.widthIn(max=460.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.background).border(1.dp,c.divider,RoundedCornerShape(12.dp)).padding(24.dp).verticalScroll(rememberScrollState())) {
            Text(title,color=c.ink,style=MaterialTheme.typography.headlineSmall)
            if(message.isNotBlank()){Spacer(Modifier.height(12.dp));Text(message,color=c.secondary,style=MaterialTheme.typography.bodySmall)}
            body();Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End,verticalAlignment=Alignment.CenterVertically,content=actions)
        }
    }
}
