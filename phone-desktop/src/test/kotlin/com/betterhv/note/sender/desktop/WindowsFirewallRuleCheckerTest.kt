package com.betterhv.note.sender.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class WindowsFirewallRuleCheckerTest {
  @Test
  fun acceptsEnabledInboundTcpRuleOnNoteLinkPort() {
    val status = checker("True|Inbound|Allow|TCP|39817").check()

    assertEquals(WindowsFirewallRuleStatus.Configured, status)
  }

  @Test
  fun acceptsNumericTcpProtocol() {
    val status = checker("True|Inbound|Allow|6|39817").check()

    assertEquals(WindowsFirewallRuleStatus.Configured, status)
  }

  @Test
  fun reportsMissingRuleWhenPowerShellReturnsNoRows() {
    val status = checker("").check()

    assertEquals(WindowsFirewallRuleStatus.Missing, status)
  }

  @Test
  fun rejectsDisabledRule() {
    val status = checker("False|Inbound|Allow|TCP|39817").check()

    assertEquals(WindowsFirewallRuleStatus.Misconfigured, status)
  }

  @Test
  fun rejectsWrongPort() {
    val status = checker("True|Inbound|Allow|TCP|12345").check()

    assertEquals(WindowsFirewallRuleStatus.Misconfigured, status)
  }

  @Test
  fun acceptsOneCorrectRuleAmongMismatches() {
    val status = checker(
      "True|Outbound|Allow|TCP|39817\nTrue|Inbound|Allow|TCP|39817",
    ).check()

    assertEquals(WindowsFirewallRuleStatus.Configured, status)
  }

  @Test
  fun reportsPowerShellFailure() {
    val checker = WindowsFirewallRuleChecker(
      FirewallCommandExecutor { _, _ -> FirewallCommandResult(1, "Access denied") },
    )

    val status = assertIs<WindowsFirewallRuleStatus.CheckFailed>(checker.check())
    assertEquals("Access denied", status.detail)
  }

  @Test
  fun reportsTimeout() {
    val checker = WindowsFirewallRuleChecker(
      FirewallCommandExecutor { _, _ -> FirewallCommandResult(null, "", timedOut = true) },
    )

    assertIs<WindowsFirewallRuleStatus.CheckFailed>(checker.check())
  }

  private fun checker(output: String) = WindowsFirewallRuleChecker(
    FirewallCommandExecutor { command, timeout ->
      require(command.first() == "powershell.exe")
      require(timeout > 0)
      FirewallCommandResult(0, output)
    },
  )
}
