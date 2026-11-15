package com.xiaojiwei.memao.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xiaojiwei.memao.ui.theme.LocalMemaoColors

@Composable
internal fun EditorTagDialog(tags: List<String>, onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    val colors = LocalMemaoColors.current
    var input by rememberSaveable { mutableStateOf("") }
    val query = input.trim().removePrefix("#")
    val existing = remember(tags) { tags.filter { it.isNotBlank() }.distinct().sorted() }
    val matching = remember(existing, query) { existing.filter { it.contains(query, ignoreCase = true) } }

    MmDialog(
        "添加标签",
        "标签写入正文，和服务端保持一致。也可直接在源码中添加或移除 #标签。",
        onDismiss = onDismiss,
        actions = {
            MmButton("取消", onDismiss)
            MmButton("添加", { onAdd(input) }, primary = true, enabled = query.isNotBlank())
        },
    ) {
        Spacer(Modifier.height(16.dp))
        MmField(input, { input = it }, "标签", placeholder = "搜索或输入新标签", inputTag = "editorTagInput")
        if (matching.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text("已有标签", color = colors.secondary, style = MaterialTheme.typography.labelSmall)
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 192.dp)) {
                items(matching, key = { it }) { tag ->
                    Text(
                        "#$tag",
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .clickable(role = Role.Button) { onAdd(tag) }.padding(horizontal = 8.dp, vertical = 12.dp),
                        color = colors.ink,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
