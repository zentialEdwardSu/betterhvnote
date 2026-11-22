package com.betterhv.note.sender.desktop

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

internal sealed interface WindowsFirewallRuleStatus {
  data object Configured : WindowsFirewallRuleStatus
  data object Missing : WindowsFirewallRuleStatus
  data class Misconfigured(val fields: List<String>) : WindowsFirewallRuleStatus
  data class Blocked(val profiles: List<String>) : WindowsFirewallRuleStatus
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

internal sealed interface FirewallInstallResult {
  data object Installed : FirewallInstallResult
  data object Cancelled : FirewallInstallResult
  data class Failed(val detail: String) : FirewallInstallResult
}

private object Markers {
  const val PREFIX = "NOTELINK|"
  const val CONFIGURED = "CONFIGURED"
  const val MISSING = "MISSING"
  const val MISCONFIGURED = "MISCONFIGURED"
  const val BLOCKED = "BLOCKED"
  const val ERROR = "ERROR"
  const val INSTALLED = "INSTALLED"
  const val CANCELLED = "CANCELLED"
  const val FAILED = "FAILED"
}

/**
 * Reads firewall state from ASCII markers printed by PowerShell itself.
 *
 * The script decides, the Kotlin side only maps markers, so localized Windows messages, warnings and
 * console code pages can never change the verdict or leak mojibake into the UI.
 */
internal object FirewallOutputParser {
  fun parse(output: String): WindowsFirewallRuleStatus? {
    var status: WindowsFirewallRuleStatus? = null
    for (line in output.lineSequence()) {
      val trimmed = clean(line)
      if (!trimmed.startsWith(Markers.PREFIX)) continue
      val parts = trimmed.removePrefix(Markers.PREFIX).split('|', limit = 2)
      status = when (parts[0]) {
        Markers.CONFIGURED -> WindowsFirewallRuleStatus.Configured
        Markers.MISSING -> WindowsFirewallRuleStatus.Missing
        Markers.MISCONFIGURED -> WindowsFirewallRuleStatus.Misconfigured(fields(parts.getOrNull(1)))
        Markers.BLOCKED -> WindowsFirewallRuleStatus.Blocked(fields(parts.getOrNull(1)))
        Markers.ERROR -> WindowsFirewallRuleStatus.CheckFailed(
          parts.getOrNull(1)?.take(MAX_DETAIL) ?: "PowerShell reported a firewall error",
        )

        else -> null
      }
      if (status != null) break
    }
    return status
  }

  fun installDetail(output: String): String? = output.lineSequence()
    .map(::clean)
    .firstOrNull { it.startsWith(Markers.PREFIX + Markers.FAILED) }
    ?.removePrefix(Markers.PREFIX + Markers.FAILED + "|")
    ?.take(MAX_DETAIL)
    ?.takeIf(String::isNotBlank)

  fun isCancelled(output: String): Boolean =
    output.lineSequence().any { clean(it) == Markers.PREFIX + Markers.CANCELLED }

  fun isInstalled(output: String): Boolean =
    output.lineSequence().any { clean(it) == Markers.PREFIX + Markers.INSTALLED }

  private fun clean(line: String): String = line.trim().removePrefix("\uFEFF")

  private fun fields(value: String?): List<String> =
    value?.split(',')?.map(String::trim)?.filter(String::isNotEmpty).orEmpty()
}

internal class WindowsFirewallRuleChecker(
  private val executor: FirewallCommandExecutor = ProcessFirewallCommandExecutor,
) {
  @Suppress("ReturnCount")
  fun check(): WindowsFirewallRuleStatus {
    val result = runCatching {
      executor.execute(powerShellEncodedCommand(CHECK_SCRIPT), CHECK_TIMEOUT_MILLIS)
    }.getOrElse {
      return WindowsFirewallRuleStatus.CheckFailed(it.message ?: it.javaClass.simpleName)
    }
    if (result.timedOut) return WindowsFirewallRuleStatus.CheckFailed("Firewall check timed out")
    val parsed = FirewallOutputParser.parse(result.output)
    if (parsed is WindowsFirewallRuleStatus.CheckFailed) return parsed
    if (result.exitCode != 0) {
      val detail = result.output.lineSequence().map(String::trim).firstOrNull(String::isNotEmpty)
      return WindowsFirewallRuleStatus.CheckFailed(
        detail?.take(MAX_FAILURE_DETAIL) ?: "PowerShell exited with code ${result.exitCode}",
      )
    }
    return parsed ?: WindowsFirewallRuleStatus.CheckFailed(
      result.output.lineSequence().map(String::trim).firstOrNull(String::isNotEmpty)
        ?.take(MAX_FAILURE_DETAIL)
        ?: "PowerShell returned no firewall status",
    )
  }

  private companion object {
    const val CHECK_TIMEOUT_MILLIS = 15_000L
    const val MAX_FAILURE_DETAIL = 300
  }
}

internal object FirewallInstallOutput {
  fun interpret(exitCode: Int?, output: String, log: String?): FirewallInstallResult {
    val combined = listOfNotNull(output, log).joinToString("\n")
    return when {
      FirewallOutputParser.isInstalled(combined) || exitCode == 0 -> FirewallInstallResult.Installed
      exitCode == WindowsFirewallInstaller.ERROR_CANCELLED || FirewallOutputParser.isCancelled(
        combined,
      ) ->
        FirewallInstallResult.Cancelled

      else -> FirewallInstallResult.Failed(
        FirewallOutputParser.installDetail(combined) ?: "PowerShell exited with code $exitCode",
      )
    }
  }
}

internal class WindowsFirewallInstaller(
  private val tempDir: File = File(System.getProperty("java.io.tmpdir")),
  private val executor: FirewallCommandExecutor = ProcessFirewallCommandExecutor,
) {
  @Suppress("ReturnCount")
  fun install(): FirewallInstallResult {
    val logFile = File(tempDir, "notelink-firewall-${UUID.randomUUID()}.log")
    val command = runCatching {
      powerShellEncodedCommand(outerScript(encodePowerShell(installScript(logFile)), logFile))
    }.getOrElse { return FirewallInstallResult.Failed(it.message ?: it.javaClass.simpleName) }
    val result = runCatching { executor.execute(command, INSTALL_TIMEOUT_MILLIS) }
      .getOrElse {
        logFile.delete()
        return FirewallInstallResult.Failed(it.message ?: it.javaClass.simpleName)
      }
    if (result.timedOut) {
      logFile.delete()
      return FirewallInstallResult.Failed("Firewall setup timed out")
    }
    val log = runCatching {
      logFile.takeIf(File::isFile)?.readText()?.removePrefix("\uFEFF")
    }.getOrNull()
    logFile.delete()
    return FirewallInstallOutput.interpret(result.exitCode, result.output, log)
  }

  companion object {
    const val ERROR_CANCELLED = 1223
    private const val INSTALL_TIMEOUT_MILLIS = 120_000L

    private fun escape(value: String): String = value.replace("'", "''")

    private fun installScript(logFile: File): String = """
            ${'$'}log = '${escape(logFile.absolutePath)}'
            [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new(${'$'}false)
            ${'$'}ErrorActionPreference = 'Stop'
            ${'$'}netsh = Join-Path ${'$'}env:SystemRoot 'System32\netsh.exe'
            function Write-Result([string]${'$'}line) {
                [IO.File]::AppendAllText(
                    ${'$'}log,
                    ${'$'}line + [Environment]::NewLine,
                    [Text.UTF8Encoding]::new(${'$'}false)
                )
                ${'$'}line
            }
            function Invoke-Netsh([string[]]${'$'}arguments) {
                ${'$'}info = New-Object System.Diagnostics.ProcessStartInfo
                ${'$'}info.FileName = ${'$'}netsh
                ${'$'}info.Arguments = (${'$'}arguments -join ' ')
                ${'$'}info.UseShellExecute = ${'$'}false
                ${'$'}info.RedirectStandardOutput = ${'$'}true
                ${'$'}info.RedirectStandardError = ${'$'}true
                ${'$'}info.CreateNoWindow = ${'$'}true
                ${'$'}process = [System.Diagnostics.Process]::Start(${'$'}info)
                ${'$'}stdoutTask = ${'$'}process.StandardOutput.ReadToEndAsync()
                ${'$'}stderrTask = ${'$'}process.StandardError.ReadToEndAsync()
                if (-not ${'$'}process.WaitForExit(20000)) {
                    try { ${'$'}process.Kill() } catch { }
                    throw 'netsh timed out after 20 seconds'
                }
                ${'$'}stdout = ${'$'}stdoutTask.Result
                ${'$'}stderr = ${'$'}stderrTask.Result
                ${'$'}code = ${'$'}process.ExitCode
                if (${'$'}code -ne 0) {
                    throw ('netsh exit ' + ${'$'}code + ': ' +
                        (((${'$'}stdout + ' ' + ${'$'}stderr) -replace "`r?`n", ' ').Trim()))
                }
                ${'$'}stdout
            }
            try {
                try {
                    Invoke-Netsh @('advfirewall','firewall','delete','rule','name=NoteLink-TCP-39817') |
                        Out-Null
                } catch { }
                Invoke-Netsh @(
                    'advfirewall','firewall','add','rule',
                    'name=NoteLink-TCP-39817','dir=in','action=allow',
                    'protocol=TCP','localport=39817','profile=any'
                ) | Out-Null
                # LAN discovery: the Note's UDP probe must reach this PC even without Bluetooth.
                try {
                    Invoke-Netsh @('advfirewall','firewall','delete','rule','name=NoteLink-UDP-39818') |
                        Out-Null
                } catch { }
                Invoke-Netsh @(
                    'advfirewall','firewall','add','rule',
                    'name=NoteLink-UDP-39818','dir=in','action=allow',
                    'protocol=UDP','localport=39818','profile=any'
                ) | Out-Null
                Write-Result 'NOTELINK|INSTALLED'
                exit 0
            } catch {
                Write-Result ('NOTELINK|FAILED|' + (${'$'}_.Exception.Message -replace "`r?`n", ' '))
                exit 1
            }
    """.trimIndent()

    private fun outerScript(innerEncoded: String, logFile: File): String = """
            ${'$'}ErrorActionPreference = 'Stop'
            [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new(${'$'}false)
            try {
                ${'$'}process = Start-Process -FilePath '${escape(powerShellPath())}' -Verb RunAs -WindowStyle Hidden `
                    -ArgumentList "-NoProfile -ExecutionPolicy Bypass -EncodedCommand $innerEncoded" -PassThru
                if (-not ${'$'}process.WaitForExit(90000)) {
                    try { ${'$'}process.Kill() } catch { }
                    'NOTELINK|FAILED|elevated firewall setup timed out'
                    exit 1
                }
                ${'$'}log = '${escape(logFile.absolutePath)}'
                if (Test-Path -LiteralPath ${'$'}log) {
                    Get-Content -LiteralPath ${'$'}log -Encoding UTF8 | ForEach-Object { ${'$'}_ }
                }
                exit ${'$'}process.ExitCode
            } catch {
                ${'$'}native = ${'$'}_.Exception.InnerException.NativeErrorCode
                if (${'$'}null -eq ${'$'}native) { ${'$'}native = ${'$'}_.Exception.NativeErrorCode }
                if (${'$'}native -eq 1223 -or ${'$'}_.Exception.HResult -eq -2147023673) {
                    'NOTELINK|CANCELLED'
                    exit 1223
                }
                'NOTELINK|FAILED|' + (${'$'}_.Exception.Message -replace "`r?`n", ' ')
                exit 1
            }
    """.trimIndent()
  }
}

internal fun powerShellPath(): String {
  val systemRoot = System.getenv("SystemRoot")
  val candidate = systemRoot?.let { File("$it\\System32\\WindowsPowerShell\\v1.0\\powershell.exe") }
  return candidate?.takeIf(File::isFile)?.absolutePath ?: "powershell.exe"
}

internal fun powerShellEncodedCommand(script: String): List<String> = listOf(
  powerShellPath(),
  "-NoLogo",
  "-NoProfile",
  "-NonInteractive",
  "-ExecutionPolicy",
  "Bypass",
  "-EncodedCommand",
  encodePowerShell(script),
)

internal fun encodePowerShell(script: String): String =
  Base64.getEncoder().encodeToString(script.toByteArray(StandardCharsets.UTF_16LE))

internal object ProcessFirewallCommandExecutor : FirewallCommandExecutor {
  override fun execute(command: List<String>, timeoutMillis: Long): FirewallCommandResult {
    val process = ProcessBuilder(command).redirectErrorStream(true).start()
    val output = ByteArrayOutputStream()
    val reader = thread(isDaemon = true, name = "notelink-powershell") {
      runCatching { process.inputStream.use { it.copyTo(output) } }
    }
    val finished = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
    if (!finished) {
      process.destroyForcibly()
      reader.join(READER_JOIN_MILLIS)
      return FirewallCommandResult(exitCode = null, output = "", timedOut = true)
    }
    reader.join(READER_JOIN_MILLIS)
    return FirewallCommandResult(
      exitCode = process.exitValue(),
      output = decode(output.toByteArray()),
    )
  }

  private const val READER_JOIN_MILLIS = 1_000L

  private fun decode(bytes: ByteArray): String {
    val utf8 = bytes.toString(StandardCharsets.UTF_8)
    if ('\uFFFD' !in utf8) return utf8
    val native = System.getProperty("native.encoding")
      ?.let { runCatching { Charset.forName(it) }.getOrNull() }
    return native?.let { bytes.toString(it) } ?: utf8
  }
}

private const val MAX_DETAIL = 300

private val CHECK_SCRIPT = """
    [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new(${'$'}false)
    ${'$'}ErrorActionPreference = 'Stop'
    try {
        ${'$'}blocked = @()
        foreach (${'$'}profile in @(Get-NetFirewallProfile -ErrorAction SilentlyContinue)) {
            if (${'$'}null -ne ${'$'}profile.AllowInboundRules -and ${'$'}profile.Enabled -and -not ${'$'}profile.AllowInboundRules) {
                ${'$'}blocked += [string]${'$'}profile.Name
            }
        }
        if (${'$'}blocked.Count -gt 0) {
            'NOTELINK|BLOCKED|' + (${'$'}blocked -join ',')
            exit 0
        }
        ${'$'}queryErrors = @()
        ${'$'}tcpRules = @()
        ${'$'}tcpRules += @(Get-NetFirewallRule -Name 'NoteLink-TCP-39817' -ErrorAction SilentlyContinue `
            -ErrorVariable +queryErrors)
        ${'$'}tcpRules += @(Get-NetFirewallRule -DisplayName 'NoteLink-TCP-39817' -ErrorAction SilentlyContinue `
            -ErrorVariable +queryErrors)
        ${'$'}tcpRules += @(Get-NetFirewallRule -DisplayName 'NoteLink TCP 39817' -ErrorAction SilentlyContinue `
            -ErrorVariable +queryErrors)
        ${'$'}udpRules = @()
        ${'$'}udpRules += @(Get-NetFirewallRule -Name 'NoteLink-UDP-39818' -ErrorAction SilentlyContinue `
            -ErrorVariable +queryErrors)
        ${'$'}udpRules += @(Get-NetFirewallRule -DisplayName 'NoteLink-UDP-39818' -ErrorAction SilentlyContinue `
            -ErrorVariable +queryErrors)
        ${'$'}udpRules += @(Get-NetFirewallRule -DisplayName 'NoteLink UDP 39818' -ErrorAction SilentlyContinue `
            -ErrorVariable +queryErrors)
        if (${'$'}tcpRules.Count -eq 0 -or ${'$'}udpRules.Count -eq 0) {
            ${'$'}denied = @(${'$'}queryErrors | Where-Object {
                ${'$'}_.Exception.HResult -eq -2147024891 -or
                ${'$'}_.Exception.HResult -eq -2147942405 -or
                ${'$'}_.CategoryInfo.Category -eq 'PermissionDenied'
            })
            if (${'$'}denied.Count -gt 0) {
                'NOTELINK|ERROR|Access denied while reading firewall rules (run the app or diagnostics as administrator)'
                exit 1
            }
            'NOTELINK|MISSING'
            exit 0
        }
        function Test-NoteLinkRules(${'$'}rules, [string]${'$'}expectedProtoName, [int]${'$'}expectedProtoNum, [string]${'$'}expectedPort) {
            ${'$'}configured = ${'$'}false
            ${'$'}problems = @()
            foreach (${'$'}rule in ${'$'}rules) {
                ${'$'}issues = @()
                if (${'$'}rule.Enabled -ne 'True') { ${'$'}issues += 'Enabled' }
                if (${'$'}rule.Direction -ne 'Inbound') { ${'$'}issues += 'Direction' }
                if (${'$'}rule.Action -ne 'Allow') { ${'$'}issues += 'Action' }
                ${'$'}ports = @(${'$'}rule | Get-NetFirewallPortFilter)
                if (${'$'}ports.Count -eq 0) { ${'$'}issues += 'LocalPort' }
                foreach (${'$'}port in ${'$'}ports) {
                    if (${'$'}port.Protocol -ne ${'$'}expectedProtoName -and ${'$'}port.Protocol -ne ${'$'}expectedProtoNum) {
                        ${'$'}issues += 'Protocol'
                    }
                    if ([string]${'$'}port.LocalPort -ne ${'$'}expectedPort) { ${'$'}issues += 'LocalPort' }
                }
                if (${'$'}issues.Count -eq 0) { ${'$'}configured = ${'$'}true } else { ${'$'}problems += ${'$'}issues }
            }
            return @{ Configured = ${'$'}configured; Problems = ${'$'}problems }
        }
        ${'$'}tcpCheck = Test-NoteLinkRules ${'$'}tcpRules 'TCP' 6 '39817'
        ${'$'}udpCheck = Test-NoteLinkRules ${'$'}udpRules 'UDP' 17 '39818'
        if (${'$'}tcpCheck.Configured -and ${'$'}udpCheck.Configured) {
            'NOTELINK|CONFIGURED'
        } else {
            ${'$'}allProblems = @()
            if (-not ${'$'}tcpCheck.Configured) { ${'$'}allProblems += ${'$'}tcpCheck.Problems }
            if (-not ${'$'}udpCheck.Configured) { ${'$'}allProblems += ${'$'}udpCheck.Problems }
            'NOTELINK|MISCONFIGURED|' + ((@(${'$'}allProblems) | Sort-Object -Unique) -join ',')
        }
    } catch {
        'NOTELINK|ERROR|' + (${'$'}_.Exception.Message -replace "`r?`n", ' ')
        exit 1
    }
""".trimIndent()
