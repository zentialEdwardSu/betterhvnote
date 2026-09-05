package com.betterhv.note.sender.desktop

import java.util.concurrent.TimeUnit

internal sealed interface WindowsFirewallRuleStatus {
    data object Configured : WindowsFirewallRuleStatus
    data object Missing : WindowsFirewallRuleStatus
    data object Misconfigured : WindowsFirewallRuleStatus
    data class CheckFailed(val detail: String) : WindowsFirewallRuleStatus
}

internal data class FirewallCommandResult(
    val exitCode: Int?,
    val output: String,
    val timedOut: Boolean = false,
)

internal fun interface FirewallCommandExecutor {
    fun execute(command: List<String>, timeoutMillis: Long): FirewallCommandResult
}

internal class WindowsFirewallRuleChecker(
    private val executor: FirewallCommandExecutor = ProcessFirewallCommandExecutor,
) {
    @Suppress("ReturnCount")
    fun check(): WindowsFirewallRuleStatus {
        val result = runCatching {
            executor.execute(powerShellCommand(), CHECK_TIMEOUT_MILLIS)
        }.getOrElse {
            return WindowsFirewallRuleStatus.CheckFailed(it.message.orEmpty().ifBlank { it.javaClass.simpleName })
        }
        if (result.timedOut) return WindowsFirewallRuleStatus.CheckFailed("Firewall check timed out")
        if (result.exitCode != 0) {
            return WindowsFirewallRuleStatus.CheckFailed(
                result.output.trim().ifBlank { "PowerShell exited with code ${result.exitCode}" }
            )
        }

        val rules = result.output.lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .mapNotNull(::parseRule)
            .toList()
        if (rules.isEmpty()) return WindowsFirewallRuleStatus.Missing
        return if (rules.any(FirewallRuleSnapshot::isConfigured)) {
            WindowsFirewallRuleStatus.Configured
        } else {
            WindowsFirewallRuleStatus.Misconfigured
        }
    }

    private fun parseRule(value: String): FirewallRuleSnapshot? {
        val fields = value.split('|')
        if (fields.size != RULE_FIELD_COUNT) return null
        return FirewallRuleSnapshot(
            enabled = fields[0],
            direction = fields[1],
            action = fields[2],
            protocol = fields[3],
            localPort = fields[4],
        )
    }

    private data class FirewallRuleSnapshot(
        val enabled: String,
        val direction: String,
        val action: String,
        val protocol: String,
        val localPort: String,
    ) {
        fun isConfigured(): Boolean =
            enabled.equals("True", ignoreCase = true) &&
                direction.equals("Inbound", ignoreCase = true) &&
                action.equals("Allow", ignoreCase = true) &&
                (protocol.equals("TCP", ignoreCase = true) || protocol == TCP_PROTOCOL_NUMBER) &&
                localPort == NOTELINK_PORT
    }

    private fun powerShellCommand(): List<String> = listOf(
        "powershell.exe",
        "-NoLogo",
        "-NoProfile",
        "-NonInteractive",
        "-Command",
        POWER_SHELL_QUERY,
    )

    private companion object {
        const val CHECK_TIMEOUT_MILLIS = 5_000L
        const val RULE_FIELD_COUNT = 5
        const val NOTELINK_PORT = "39817"
        const val TCP_PROTOCOL_NUMBER = "6"
        val POWER_SHELL_QUERY = """
            ${'$'}rules = Get-NetFirewallRule -DisplayName 'NoteLink TCP 39817' -ErrorAction SilentlyContinue
            foreach (${'$'}rule in ${'$'}rules) {
                foreach (${'$'}port in (${'$'}rule | Get-NetFirewallPortFilter)) {
                    Write-Output ("{0}|{1}|{2}|{3}|{4}" -f ${'$'}rule.Enabled, ${'$'}rule.Direction, ${'$'}rule.Action, ${'$'}port.Protocol, ${'$'}port.LocalPort)
                }
            }
        """.trimIndent()
    }
}

private object ProcessFirewallCommandExecutor : FirewallCommandExecutor {
    override fun execute(command: List<String>, timeoutMillis: Long): FirewallCommandResult {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            return FirewallCommandResult(exitCode = null, output = "", timedOut = true)
        }
        return FirewallCommandResult(
            exitCode = process.exitValue(),
            output = process.inputStream.bufferedReader().use { it.readText() },
        )
    }
}
