NoteLink for Windows (portable)
================================

English
-------
1. Extract the entire ZIP archive before starting NoteLink.exe.
2. Right-click Allow-NoteLink-Firewall.ps1, choose "Run with PowerShell", and approve the administrator prompt.
3. Start NoteLink.exe. The app checks that the inbound TCP 39817 rule is enabled and warns when it cannot confirm the rule.
4. To remove the firewall rule later, run Remove-NoteLink-Firewall.ps1 in the same way.

This build is not code-signed. Windows SmartScreen may display an "Unknown publisher" warning on first launch.

中文
----
1. 请先完整解压 ZIP，再启动 NoteLink.exe。
2. 右键点击 Allow-NoteLink-Firewall.ps1，选择“使用 PowerShell 运行”，并确认管理员权限提示。
3. 启动 NoteLink.exe。应用会检查 TCP 39817 入站规则，无法确认规则时会弹出提示。
4. 如果以后需要移除规则，请以同样方式运行 Remove-NoteLink-Firewall.ps1。

该版本未进行 Windows 代码签名，首次运行时 SmartScreen 可能显示“未知发布者”警告。
