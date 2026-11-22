package com.betterhv.note.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import com.betterhv.note.ui.EinkActionRow
import com.betterhv.note.ui.EinkColors
import com.betterhv.note.ui.EinkDimens
import com.betterhv.note.ui.EinkRadioMark
import com.betterhv.note.ui.EinkType
import com.betterhv.note.AppCommand
import com.betterhv.note.CommandBinding
import com.betterhv.note.CommandRegistry
import com.betterhv.note.HardwareKeyId
import com.betterhv.note.HardwareShortcutBindings
import com.betterhv.note.ShortcutScene
import com.betterhv.note.noteText

@Composable
fun HardwareBindingsPanel(
  scene: ShortcutScene,
  selectedKey: HardwareKeyId,
  bindings: HardwareShortcutBindings,
  onKey: (HardwareKeyId) -> Unit,
  onBind: (CommandBinding?) -> Unit,
) {
  var query by remember { mutableStateOf("") }
  var editing by remember(scene, selectedKey) { mutableStateOf<CommandBinding?>(null) }
  Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(EinkDimens.SpaceMd)) {
    Column(Modifier.width(172.dp).verticalScroll(rememberScrollState())) {
      HardwareKeyId.entries.forEach { key ->
        val selected = key == selectedKey
        Column(
          Modifier
            .fillMaxWidth()
            .background(if (selected) EinkColors.Ink else EinkColors.Paper)
            .border(EinkDimens.Hairline, EinkColors.Ink)
            .clickable { onKey(key) }
            .padding(EinkDimens.SpaceSm),
        ) {
          Text(
            "K${key.number}",
            color = if (selected) EinkColors.Paper else EinkColors.Ink,
            style = EinkType.Label,
          )
          Text(
            bindings.command(scene, key)?.let(CommandRegistry::title) ?: noteText("未绑定", "Unassigned"),
            color = if (selected) EinkColors.Paper else EinkColors.Muted,
            style = EinkType.Caption,
          )
        }
      }
    }
    Column(Modifier.weight(1f)) {
      OutlinedTextField(
        query,
        { query = it },
        label = { Text(noteText("搜索功能", "Search commands")) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        shape = RectangleShape,
      )
      TextButton(onClick = { editing = null; onBind(null) }) { Text(noteText("取消绑定", "Unassign")) }
      Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
        CommandRegistry.commands.filter {
          query.isBlank() || "${it.id} ${it.label} ${it.category}".contains(query, ignoreCase = true)
        }
          .groupBy(AppCommand::category).forEach { (category, commands) ->
            Text(
              category,
              style = EinkType.SectionTitle,
              modifier = Modifier.padding(vertical = EinkDimens.SpaceSm),
            )
            commands.forEach { command ->
              val applicable = scene in command.scenes
              EinkActionRow(
                label = run {
                  val bound = bindings.command(scene, selectedKey)?.commandId == command.id
                  (if (bound) "✓ " else "") + command.label +
                    if (applicable) "" else "（不适用于此场景）"
                },
                enabled = applicable,
                onClick = {
                  val binding = CommandBinding(command.id, command.defaults())
                  if (command.parameters.isEmpty()) {
                    onBind(binding)
                    editing = null
                  } else {
                    editing = binding
                  }
                },
              )
              editing?.takeIf { it.commandId == command.id }?.let { binding ->
                command.parameters.forEach { parameter ->
                  Text(parameter.label)
                  // Vertical choices remain usable for long color labels on the e-ink panel.
                  parameter.options.forEach { option ->
                    val selected = binding.arguments[parameter.name] == option.value
                    Row(
                      Modifier
                        .fillMaxWidth()
                        .heightIn(min = EinkDimens.Row)
                        .clickable {
                          editing = binding.copy(
                            arguments = binding.arguments + (parameter.name to option.value),
                          )
                        }
                        .padding(vertical = EinkDimens.SpaceXs),
                      verticalAlignment = Alignment.CenterVertically,
                      horizontalArrangement = Arrangement.spacedBy(EinkDimens.SpaceSm),
                    ) {
                      EinkRadioMark(selected = selected, contentDescription = option.label)
                      Text(
                        option.label,
                        style = EinkType.Body,
                        modifier = Modifier.padding(vertical = EinkDimens.SpaceXs),
                      )
                    }
                  }
                }
                Button(
                  onClick = { onBind(binding); editing = null },
                  shape = RectangleShape,
                ) { Text("绑定到 K${selectedKey.number}") }
              }
            }
          }
      }
    }
  }
}
