package com.betterhv.note

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.graphics.Bitmap
import com.betterhv.note.doc.DEFAULT_TEMPLATE_ID
import com.betterhv.note.template.TemplateCatalogSnapshot

@Composable
fun NotebookNameDialog(
    catalog: TemplateCatalogSnapshot,
    pageWidth: Float,
    pageHeight: Float,
    preview: (String, Int, Int) -> Bitmap?,
    onConnectDirectory: () -> Unit,
    onRefresh: () -> Unit,
    templateEnabled: Boolean = true,
    onConfirm: (String, String) -> Unit,
    onDismiss: () -> Unit
) {
    var title by remember { mutableStateOf("") }
    var selectedTemplateId by remember { mutableStateOf(DEFAULT_TEMPLATE_ID) }
    var chooserOpen by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val normalized = title.trim()
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboard?.show()
    }
    EinkModalOverlay(onDismissRequest = onDismiss, position = EinkModalPosition.TOP) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("命名笔记本", fontSize = 22.sp)
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                singleLine = true,
                label = { Text("笔记本名称") },
                shape = RectangleShape
            )
            if (templateEnabled) {
                TemplateSelectionRow(
                    definition = catalog.find(selectedTemplateId),
                    preview = preview,
                    onClick = {
                        keyboard?.hide()
                        chooserOpen = true
                    }
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)
            ) {
                EinkDialogAction("取消", onClick = onDismiss)
                EinkDialogAction(
                    "创建",
                    enabled = normalized.isNotEmpty(),
                    onClick = { onConfirm(normalized, selectedTemplateId) }
                )
            }
        }
    }
    if (chooserOpen && templateEnabled) {
        TemplateChooserOverlay(
            catalog = catalog,
            selectedId = selectedTemplateId,
            pageWidth = pageWidth,
            pageHeight = pageHeight,
            preview = preview,
            onSelect = { selectedTemplateId = it; chooserOpen = false },
            onConnectDirectory = onConnectDirectory,
            onRefresh = onRefresh,
            onDismiss = { chooserOpen = false }
        )
    }
}
