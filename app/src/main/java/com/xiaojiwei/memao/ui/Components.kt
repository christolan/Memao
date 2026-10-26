package com.xiaojiwei.memao.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.xiaojiwei.memao.ui.theme.LocalMemaoColors

enum class MmIcon { NOTES, DRAFT, ARCHIVE, SETTINGS, PLUS, BACK, SEARCH, CLOSE, DOWN, EDIT, DELETE, IMAGE, AUDIO, PASTE, TAG, EYE, CHECK, REFRESH, LOCK, COPY, MORE, PLAY, PAUSE }

@Composable
fun LineIcon(icon: MmIcon, modifier: Modifier = Modifier, tint: Color = LocalMemaoColors.current.ink) {
    Canvas(modifier.size(20.dp)) {
        val sx = size.width / 24f
        val sy = size.height / 24f
        val stroke = 1.6.dp.toPx()
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(tint, Offset(x1 * sx, y1 * sy), Offset(x2 * sx, y2 * sy), stroke, StrokeCap.Round)
        fun path(vararg points: Pair<Float, Float>, closed: Boolean = false) {
            val p = Path().apply { points.forEachIndexed { i, v -> if (i == 0) moveTo(v.first*sx,v.second*sy) else lineTo(v.first*sx,v.second*sy) }; if(closed) close() }
            drawPath(p,tint,style = Stroke(stroke,cap = StrokeCap.Round))
        }
        fun rect(l: Float,t: Float,r: Float,b: Float) = path(l to t,r to t,r to b,l to b,closed=true)
        when(icon) {
            MmIcon.PLUS -> { line(5f,12f,19f,12f);line(12f,5f,12f,19f) }
            MmIcon.BACK -> { path(14f to 5f,7f to 12f,14f to 19f);line(7f,12f,21f,12f) }
            MmIcon.CLOSE -> { line(6f,6f,18f,18f);line(18f,6f,6f,18f) }
            MmIcon.DOWN -> path(6f to 9f,12f to 15f,18f to 9f)
            MmIcon.CHECK -> path(4f to 12f,9f to 17f,20f to 6f)
            MmIcon.SEARCH -> { drawCircle(tint,7*sx,Offset(10*sx,10*sy),style=Stroke(stroke));line(15f,15f,21f,21f) }
            MmIcon.NOTES, MmIcon.DRAFT -> { rect(5f,3f,19f,21f);line(9f,8f,16f,8f);line(9f,12f,16f,12f);line(9f,16f,13f,16f);line(3f,7f,6f,7f);line(3f,17f,6f,17f) }
            MmIcon.ARCHIVE -> { rect(3f,3f,21f,8f);rect(5f,8f,19f,21f);line(9f,12f,15f,12f) }
            MmIcon.SETTINGS -> { line(4f,6f,20f,6f);line(4f,12f,20f,12f);line(4f,18f,20f,18f);drawCircle(tint,2*sx,Offset(9*sx,6*sy),style=Stroke(stroke));drawCircle(tint,2*sx,Offset(16*sx,12*sy),style=Stroke(stroke));drawCircle(tint,2*sx,Offset(8*sx,18*sy),style=Stroke(stroke)) }
            MmIcon.EDIT -> { path(4f to 16f,16f to 4f,20f to 8f,8f to 20f,4f to 20f,4f to 16f);line(13f,7f,17f,11f) }
            MmIcon.DELETE -> { line(4f,6f,20f,6f);path(6f to 6f,7f to 21f,17f to 21f,18f to 6f);path(9f to 6f,9f to 3f,15f to 3f,15f to 6f);line(10f,10f,10f,17f);line(14f,10f,14f,17f) }
            MmIcon.IMAGE -> { rect(3f,3f,21f,21f);path(3f to 17f,9f to 11f,15f to 17f,18f to 14f,21f to 17f);drawCircle(tint,1.5f*sx,Offset(16*sx,8*sy),style=Stroke(stroke)) }
            MmIcon.AUDIO -> { path(9f to 18f,9f to 5f,20f to 3f,20f to 16f);line(9f,9f,20f,7f);drawOval(tint,Offset(3*sx,16*sy),androidx.compose.ui.geometry.Size(6*sx,5*sy),style=Stroke(stroke));drawOval(tint,Offset(14*sx,14*sy),androidx.compose.ui.geometry.Size(6*sx,5*sy),style=Stroke(stroke)) }
            MmIcon.PASTE -> { rect(6f,5f,20f,21f);rect(9f,2f,17f,7f);line(3f,9f,3f,18f) }
            MmIcon.TAG -> { path(3f to 3f,12f to 3f,22f to 13f,13f to 22f,3f to 12f,3f to 3f);drawCircle(tint,1.2f*sx,Offset(8*sx,8*sy),style=Stroke(stroke)) }
            MmIcon.EYE -> { path(2f to 12f,7f to 6f,17f to 6f,22f to 12f,17f to 18f,7f to 18f,2f to 12f);drawCircle(tint,3*sx,Offset(12*sx,12*sy),style=Stroke(stroke)) }
            MmIcon.REFRESH -> { path(20f to 8f,20f to 3f,15f to 3f);path(20f to 8f,16f to 4f,9f to 4f,4f to 9f,4f to 16f,9f to 20f,16f to 20f,20f to 16f) }
            MmIcon.LOCK -> { rect(5f,10f,19f,21f);path(8f to 10f,8f to 5f,11f to 3f,14f to 3f,17f to 5f,17f to 10f);line(12f,14f,12f,17f) }
            MmIcon.COPY -> { rect(7f,7f,21f,21f);path(17f to 4f,17f to 2f,2f to 2f,2f to 17f,4f to 17f) }
            MmIcon.MORE -> (5..19 step 7).forEach { drawCircle(tint,1.2f*sx,Offset(it*sx,12*sy)) }
            MmIcon.PLAY -> path(7f to 4f,20f to 12f,7f to 20f,7f to 4f)
            MmIcon.PAUSE -> {line(8f,5f,8f,19f);line(16f,5f,16f,19f)}
        }
    }
}

@Composable
fun MmIconButton(icon: MmIcon, label: String, enabled: Boolean = true, danger: Boolean = false, onClick: () -> Unit) {
    val c = LocalMemaoColors.current
    Box(Modifier.size(48.dp).alpha(if(enabled) 1f else .35f).clip(RoundedCornerShape(8.dp))
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
fun MmField(value: String, onValueChange: (String) -> Unit, label: String, modifier: Modifier = Modifier, placeholder: String = "", error: String? = null, visualTransformation: VisualTransformation = VisualTransformation.None, keyboardOptions: KeyboardOptions = KeyboardOptions.Default, trailing: (@Composable () -> Unit)? = null, singleLine: Boolean = true, inputTag:String?=null, enabled:Boolean=true) {
    val c = LocalMemaoColors.current
    var focused by remember { mutableStateOf(false) }
    Column(modifier) {
        if(label.isNotBlank()){Text(label,color=c.secondary,style=MaterialTheme.typography.bodySmall);Spacer(Modifier.height(8.dp))}
        Row(Modifier.fillMaxWidth().border(1.dp,if(error!=null)c.danger else if(focused)c.ink else c.divider,RoundedCornerShape(8.dp)).padding(start=12.dp,end=4.dp),verticalAlignment=Alignment.CenterVertically) {
            BasicTextField(value,onValueChange,modifier=Modifier.weight(1f).heightIn(min=54.dp).then(if(inputTag!=null)Modifier.testTag(inputTag)else Modifier).onFocusChanged {focused=it.isFocused}.padding(vertical=14.dp),
                textStyle=MaterialTheme.typography.bodyLarge.copy(color=c.ink),singleLine=singleLine,enabled=enabled,visualTransformation=visualTransformation,keyboardOptions=keyboardOptions,cursorBrush=SolidColor(c.ink),
                decorationBox={inner -> Box {if(value.isEmpty())Text(placeholder,color=c.secondary,fontSize=13.sp);inner()}})
            trailing?.invoke()
        }
        if(error!=null){Spacer(Modifier.height(6.dp));Text(error,color=c.danger,style=MaterialTheme.typography.bodySmall)}
    }
}

@Composable
fun MmRule() { Box(Modifier.fillMaxWidth().height(1.dp).background(LocalMemaoColors.current.divider)) }

@Composable
fun MmTopBar(title: String, onBack: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    val c=LocalMemaoColors.current
    Column {
        Row(Modifier.fillMaxWidth().heightIn(min=64.dp).padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically) {
            if(onBack!=null)MmIconButton(MmIcon.BACK,"返回",onClick=onBack) else Spacer(Modifier.width(12.dp))
            Text(title,Modifier.weight(1f),color=c.ink,style=MaterialTheme.typography.titleMedium)
            actions()
        }
        MmRule()
    }
}

@Composable
fun MmEmpty(title: String, message: String, modifier: Modifier = Modifier, icon: MmIcon = MmIcon.NOTES, action: (@Composable () -> Unit)? = null) {
    val c=LocalMemaoColors.current
    Column(modifier.fillMaxSize().padding(28.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center) {
        LineIcon(icon,Modifier.size(32.dp),c.secondary);Spacer(Modifier.height(20.dp))
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
