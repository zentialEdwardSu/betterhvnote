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

data class NotebookNameDialogState(
    val catalog: TemplateCatalogSnapshot,
    val pageWidth: Float,
    val pageHeight: Float,
    val templateEnabled: Boolean = true,
)

data class NotebookNameDialogActions(
    val preview: (String, Int, Int) -> Bitmap?,
    val onConnectDirectory: () -> Unit,
    val onRefresh: () -> Unit,
    val onConfirm: (String, String) -> Unit,
    val onDismiss: () -> Unit,
)

@Composable
fun NotebookNameDialog(state: NotebookNameDialogState, actions: NotebookNameDialogActions) {
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
    EinkModalOverlay(onDismissRequest = actions.onDismiss, position = EinkModalPosition.TOP) {
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
            if (state.templateEnabled) {
                TemplateSelectionRow(
                    definition = state.catalog.find(selectedTemplateId),
                    preview = actions.preview,
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
                EinkDialogAction("取消", onClick = actions.onDismiss)
                EinkDialogAction(
                    "创建",
                    enabled = normalized.isNotEmpty(),
                    onClick = { actions.onConfirm(normalized, selectedTemplateId) }
                )
            }
        }
    }
    if (chooserOpen && state.templateEnabled) {
        TemplateChooserOverlay(
            state = TemplateChooserState(
                catalog = state.catalog,
                selectedId = selectedTemplateId,
                pageWidth = state.pageWidth,
                pageHeight = state.pageHeight,
                preview = actions.preview,
            ),
            actions = TemplateChooserActions(
                onSelect = { selectedTemplateId = it; chooserOpen = false },
                onConnectDirectory = actions.onConnectDirectory,
                onRefresh = actions.onRefresh,
                onDismiss = { chooserOpen = false },
            ),
        )
    }
}
