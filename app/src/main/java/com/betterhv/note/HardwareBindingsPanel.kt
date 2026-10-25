package com.betterhv.note

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

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
  Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
    Column(Modifier.width(172.dp).verticalScroll(rememberScrollState())) {
      HardwareKeyId.entries.forEach { key ->
        Column(Modifier.fillMaxWidth().background(if (key == selectedKey) Color.LightGray else Color.White)
          .clickable { onKey(key) }.padding(10.dp)) {
          Text("K${key.number}")
          Text(bindings.command(scene, key)?.let(CommandRegistry::title) ?: noteText("未绑定", "Unassigned"))
        }
      }
    }
    Column(Modifier.weight(1f)) {
      OutlinedTextField(query, { query = it }, label = { Text(noteText("搜索功能", "Search commands")) },
        modifier = Modifier.fillMaxWidth(), singleLine = true)
      TextButton(onClick = { editing = null; onBind(null) }) { Text(noteText("取消绑定", "Unassign")) }
      Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
        CommandRegistry.commands.filter {
          query.isBlank() || "${it.id} ${it.label} ${it.category}".contains(query, ignoreCase = true)
        }
          .groupBy(AppCommand::category).forEach { (category, commands) ->
            Text(category, modifier = Modifier.padding(vertical = 8.dp))
            commands.forEach { command ->
              val applicable = scene in command.scenes
              TextButton(onClick = {
                val binding = CommandBinding(command.id, command.defaults())
                if (command.parameters.isEmpty()) { onBind(binding); editing = null } else editing = binding
              }, enabled = applicable) {
                val bound = bindings.command(scene, selectedKey)?.commandId == command.id
                Text((if (bound) "● " else "") + command.label + if (applicable) "" else "（不适用于此场景）")
              }
              editing?.takeIf { it.commandId == command.id }?.let { binding ->
                command.parameters.forEach { parameter ->
                  Text(parameter.label)
                  // Vertical choices remain usable for long color labels on the e-ink panel.
                  parameter.options.forEach { option ->
                    Row(Modifier.fillMaxWidth().clickable {
                      editing = binding.copy(arguments = binding.arguments + (parameter.name to option.value))
                    }, verticalAlignment = Alignment.CenterVertically) {
                      RadioButton(binding.arguments[parameter.name] == option.value, onClick = null)
                      Text(option.label, Modifier.padding(vertical = 8.dp))
                    }
                  }
                }
                Button(onClick = { onBind(binding); editing = null }) { Text("绑定到 K${selectedKey.number}") }
              }
            }
          }
      }
    }
  }
}
