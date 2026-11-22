# NoteLink 真机验收清单

对应 spec：`.dsh/specs/notelink-transfer-fixes`（任务 1–20）。
前置：安装 `phone-app` debug APK（`build/phone-app/outputs/apk/debug/`）与 `phone-desktop` 便携包（`build/phone-desktop/distributions/`）；平板与 NoteLink 已配对；Windows 为中文系统更佳。

## A. Android 分享入口（Requirement 1）

- [ ] A1 系统分享面板的 NoteLink 直连卡片分享 PNG：队列出现该图片，提示“已加入 1 项”。
- [ ] A2 系统截图工具的“分享/发送”选择 NoteLink：同上。
- [ ] A3 微信长按图片 → 其他应用打开 → NoteLink：图片被接受，不出现“不支持的类型”。
- [ ] A4 一次分享“图片 + zip”：图片入队，提示跳过 1 项并给出文件名与类型。
- [ ] A5 长按图标执行“发送到 …”快捷方式（无内容）：只打开应用，不显示“已加入发送队列”。
- [ ] A6 分享后旋转屏幕、退到后台再返回：队列不新增重复条目。
- [ ] A7 连续分享 3 次：最近任务中只有一个 NoteLink；按一次返回即退出。

## B. 发送历史（Requirement 2）

- [ ] B1 向平板发送一张图片并成功：手机端历史出现“已发送”，含目标设备、大小与时间。
- [ ] B2 点击历史条目“重新发送”：队列新增一项，历史条目保留。
- [ ] B3 删除单条历史：`files/history/` 下对应文件消失，队列与收件箱不受影响。
- [ ] B4 清空历史：列表为空，收件箱仍可用。
- [ ] B5 断开平板后发送失败，删除该失败队列项：历史出现“发送失败”并带原因。
- [ ] B6 重启应用：历史与保留载荷仍在；载荷被清理的条目“重新发送”置灰并给出原因。

## C. 发送顺序（Requirement 3）

- [ ] C1 顺序为“队列”，先加入 A 再加入 B：平板先收到 A。
- [ ] C2 切换为“栈”：从下一次发送起先收到 B。
- [ ] C3 队列视图中的“下一个发送”标记与实际发送顺序一致。
- [ ] C4 重启手机端/电脑端后顺序设置保持。

## D. Windows 单实例（Requirement 1.9）

- [ ] D1 启动 NoteLink 后关闭窗口（仍驻留托盘），再次双击 NoteLink.exe：已运行窗口被带到前台，任务管理器只有一个进程，托盘只有一个图标。

## E. Windows 防火墙（Requirement 4）

- [ ] E1 运行 `Remove-NoteLink-Firewall.ps1` 删除规则后启动 NoteLink：提示“未找到 NoteLink TCP 39817 入站规则”。
- [ ] E2 点击“启用规则”并允许 UAC：自动复检并关闭提示；管理员 PowerShell 中 `netsh advfirewall firewall show rule name=NoteLink-TCP-39817` 可见。
- [ ] E3 在 UAC 中取消：提示“已取消管理员授权”，应用不崩溃，可重试。
- [ ] E4 禁用该规则或把端口改为其他值后重新检查：提示“规则存在但不正确：Enabled/LocalPort”等具体字段。
- [ ] E5 解压到中文用户名或含空格路径，右键运行 `Allow-NoteLink-Firewall.ps1`：成功且控制台无乱码；失败时窗口停留并显示英文错误。
- [ ] E6 防火墙提示选择“稍后”后：同一会话不再自动弹出，只有“重新检查”会再次显示。

## F. 回归

- [ ] F1 平板向手机/电脑发送 PDF、PNG 导出仍正常，文件名与校验结果无变化。
- [ ] F2 电脑端拖拽文件、从剪贴板粘贴仍正常；收件箱打开/另存为/删除正常。
- [ ] F3 （可选）通过 NoteLink 更新平板应用仍可用。

## 日志与文件位置

- Android：`adb logcat -s NoteLink NoteLinkShare`（只记录类型、数量、顺序、清理数量，不含文件内容）。
- Windows：控制台 stderr（`NoteLink` 前缀）；历史数据库 `%LOCALAPPDATA%\BetterHv\NoteLink\send_history.db`；历史载荷 `%LOCALAPPDATA%\BetterHv\NoteLink\history\`。

## 结果记录

| 编号 | 结果（通过/失败） | 备注 |
|---|---|---|
| A1–A7 |  |  |
| B1–B6 |  |  |
| C1–C4 |  |  |
| D1 |  |  |
| E1–E6 |  |  |
| F1–F3 |  |  |
