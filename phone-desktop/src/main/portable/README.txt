NoteLink for Windows (portable)
================================

English
-------
1. Extract the entire ZIP archive before starting NoteLink.exe.
2. Enable the inbound firewall rule in either way:
   - Start NoteLink.exe and choose "Enable rule" in the firewall prompt (Windows asks for administrator approval), or
   - Right-click Allow-NoteLink-Firewall.ps1, choose "Run with PowerShell", and approve the administrator prompt.
3. NoteLink checks the inbound TCP 39817 rule and reports "missing", the exact mismatching fields, or a check failure.
   The check, the one-click setup, and the bundled scripts print ASCII/UTF-8 text, so they also work from folders
   whose path contains spaces or non-ASCII characters.
4. Start NoteLink.exe only once. A second launch brings the running window to the front and exits, so there is never
   a second receiver service or tray icon. Closing the window keeps NoteLink in the tray; use "Exit" there to stop it.
5. To remove the rule later, run Remove-NoteLink-Firewall.ps1 the same way.
6. If the check still fails after enabling the rule, run Diagnose-NoteLink-Firewall.ps1 (also with administrator
   approval). It writes NoteLink-firewall-diagnostics.txt next to the script; send that file back.
   A profile with "Block all incoming connections" enabled ignores every allow rule, including this one.

This build is not code-signed. Windows SmartScreen may display an "Unknown publisher" warning on first launch.

中文
----
1. 请先完整解压 ZIP，再启动 NoteLink.exe。
2. 用以下任一方式启用入站防火墙规则：
   - 启动 NoteLink.exe，在防火墙提示中选择“启用规则”，并在系统提示中允许管理员权限；或
   - 右键点击 Allow-NoteLink-Firewall.ps1，选择“使用 PowerShell 运行”，并确认管理员权限提示。
3. NoteLink 会检查 TCP 39817 入站规则，并区分“规则缺失”“字段不正确”（会列出具体字段）与“检查失败”。
   检查、一键启用与随附脚本均输出 ASCII/UTF-8 文本，因此安装在含空格或中文用户名的目录下也能正常工作。
4. NoteLink.exe 只需启动一次。再次启动会把已运行窗口带到前台后退出，不会产生第二个接收服务或托盘图标。
   关闭窗口后程序仍在托盘运行，需要停止时请在托盘菜单选择“退出”。
5. 如果以后需要移除规则，请以同样方式运行 Remove-NoteLink-Firewall.ps1。
6. 启用规则后仍提示失败时，运行 Diagnose-NoteLink-Firewall.ps1（同样允许管理员权限），它会在脚本旁生成
   NoteLink-firewall-diagnostics.txt，请把该文件发回。若防火墙配置文件启用了“阻止所有传入连接”，
   任何允许规则（包括本规则）都不会生效。

该版本未进行 Windows 代码签名，首次运行时 SmartScreen 可能显示“未知发布者”警告。
