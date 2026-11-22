package com.betterhv.note.sender.desktop

import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WindowsFirewallRuleCheckerTest {
  @Test
  fun acceptsConfiguredMarkerAmongWarnings() {
    val status = checker("WARNING: localized 防火墙 message\nNOTELINK|CONFIGURED").check()

    assertEquals(WindowsFirewallRuleStatus.Configured, status)
  }

  @Test
  fun reportsMissingRuleFromMarker() {
    assertEquals(WindowsFirewallRuleStatus.Missing, checker("NOTELINK|MISSING").check())
  }

  @Test
  fun reportsMisconfiguredFields() {
    val status = assertIs<WindowsFirewallRuleStatus.Misconfigured>(
      checker("NOTELINK|MISCONFIGURED|Enabled,LocalPort").check(),
    )

    assertEquals(listOf("Enabled", "LocalPort"), status.fields)
  }

  @Test
  fun reportsScriptErrorFromMarker() {
    val status = assertIs<WindowsFirewallRuleStatus.CheckFailed>(
      checker("NOTELINK|ERROR|Access denied").check(),
    )

    assertTrue(status.detail.contains("Access denied"))
  }

  @Test
  fun treatsMissingMarkerAsFailureRatherThanMissingRule() {
    val status =
      assertIs<WindowsFirewallRuleStatus.CheckFailed>(checker("Windows PowerShell banner").check())

    assertTrue(status.detail.contains("banner"))
  }

  @Test
  fun reportsNonZeroExitCodeAsFailure() {
    val checker = WindowsFirewallRuleChecker { _, _ -> FirewallCommandResult(1, "boom") }

    val status = assertIs<WindowsFirewallRuleStatus.CheckFailed>(checker.check())

    assertEquals("boom", status.detail)
  }

  @Test
  fun reportsTimeoutAsFailure() {
    val checker =
      WindowsFirewallRuleChecker { _, _ -> FirewallCommandResult(null, "", timedOut = true) }

    assertIs<WindowsFirewallRuleStatus.CheckFailed>(checker.check())
  }

  @Test
  fun usesEncodedCommandSoPowerShellQuotingCannotBreakTheScript() {
    var captured: List<String>? = null
    val checker = WindowsFirewallRuleChecker { command, timeoutMillis ->
      captured = command
      assertTrue(timeoutMillis >= 15_000L)
      FirewallCommandResult(0, "NOTELINK|MISSING")
    }

    checker.check()

    val command = requireNotNull(captured)
    assertTrue(command.first().endsWith("powershell.exe", ignoreCase = true))
    assertEquals("-EncodedCommand", command[command.size - 2])
    val script = String(Base64.getDecoder().decode(command.last()), StandardCharsets.UTF_16LE)
    assertTrue(script.contains("Get-NetFirewallRule"))
    assertTrue(script.contains("NoteLink-TCP-39817"))
    assertTrue(script.contains("NoteLink-UDP-39818"))
    assertTrue(script.contains("'39817'"))
    assertTrue(script.contains("'39818'"))
    assertTrue(script.contains("'UDP'"))
    assertTrue(script.contains("NOTELINK|MISSING"))
  }

  @Test
  fun installerUsesElevatedEncodedCommandWithoutPathsOrQuotes() {
    var captured: List<String>? = null
    val installer = WindowsFirewallInstaller { command, _ ->
      captured = command
      FirewallCommandResult(0, "")
    }

    assertEquals(FirewallInstallResult.Installed, installer.install())

    val command = requireNotNull(captured)
    assertEquals("-EncodedCommand", command[command.size - 2])
    val encoded = command.last()
    assertTrue(encoded.none { it == '\\' || it == '"' || it == ' ' || it == '\'' })
    val script = String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_16LE)
    assertTrue(script.contains("Start-Process"))
    assertTrue(script.contains("-Verb RunAs"))
    assertTrue(script.contains("-EncodedCommand"))
  }

  @Test
  fun installerReportsUacCancellation() {
    val byExit = WindowsFirewallInstaller { _, _ -> FirewallCommandResult(1223, "") }
    val byMarker =
      WindowsFirewallInstaller { _, _ -> FirewallCommandResult(1, "NOTELINK|CANCELLED") }

    assertEquals(FirewallInstallResult.Cancelled, byExit.install())
    assertEquals(FirewallInstallResult.Cancelled, byMarker.install())
  }

  @Test
  fun installerReportsFailureDetail() {
    val installer = WindowsFirewallInstaller { _, _ ->
      FirewallCommandResult(1, "NOTELINK|FAILED|Access is denied")
    }

    assertEquals(
      "Access is denied",
      assertIs<FirewallInstallResult.Failed>(installer.install()).detail,
    )
  }

  @Test
  fun installerReportsTimeout() {
    val installer =
      WindowsFirewallInstaller { _, _ -> FirewallCommandResult(null, "", timedOut = true) }

    assertIs<FirewallInstallResult.Failed>(installer.install())
  }

  @Test
  fun reportsBlockedInboundProfiles() {
    val status =
      assertIs<WindowsFirewallRuleStatus.Blocked>(checker("NOTELINK|BLOCKED|Public,Private").check())

    assertEquals(listOf("Public", "Private"), status.profiles)
  }

  @Test
  fun installerUsesNetshInsteadOfThePowerShellFirewallModule() {
    var captured: List<String>? = null
    val installer = WindowsFirewallInstaller { command, _ ->
      captured = command
      FirewallCommandResult(0, "")
    }

    installer.install()

    val outer = decode(requireNotNull(captured).last())
    val innerEncoded = Regex("-EncodedCommand ([A-Za-z0-9+/=]+)").find(outer)?.groupValues?.get(1)
    val inner = decode(requireNotNull(innerEncoded))
    assertTrue(inner.contains("netsh.exe"))
    assertTrue(inner.contains("advfirewall"))
    assertTrue(!inner.contains("New-NetFirewallRule"))
    assertTrue(inner.contains("ProcessStartInfo"))
    assertTrue(inner.contains("ExitCode"))
    val stdoutAsyncIndex = inner.indexOf("StandardOutput.ReadToEndAsync()")
    val stderrAsyncIndex = inner.indexOf("StandardError.ReadToEndAsync()")
    val waitForExitIndex = inner.indexOf("WaitForExit(20000)")
    assertTrue(stdoutAsyncIndex in 0 until waitForExitIndex)
    assertTrue(stderrAsyncIndex in 0 until waitForExitIndex)
  }

  @Test
  fun installOutputUsesTheElevatedLog() {
    assertEquals(
      FirewallInstallResult.Installed,
      FirewallInstallOutput.interpret(0, "", "\uFEFFNOTELINK|INSTALLED"),
    )
    assertEquals(
      "Access is denied",
      assertIs<FirewallInstallResult.Failed>(
        FirewallInstallOutput.interpret(1, "", "NOTELINK|FAILED|Access is denied"),
      ).detail,
    )
    assertEquals(FirewallInstallResult.Cancelled, FirewallInstallOutput.interpret(1223, "", null))
  }

  private fun decode(encoded: String): String =
    String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_16LE)

  private fun checker(output: String) = WindowsFirewallRuleChecker { _, _ ->
    FirewallCommandResult(0, output)
  }
}
