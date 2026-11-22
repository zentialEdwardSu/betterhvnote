# BetterHvNote

BetterHvNote 希望成为比汉王N10 Pro 2自带笔记软件更加顺手的笔记软件，其主要目标是：通过更高效的用户交互，更简洁的页面设计以及配套手机与电脑应用，更提供更加接近纸的笔记体验。

目前只支持汉王N10 Pro 2，Notelink只支持带有BLE的Windows和Android设备
## Features of Betterhvnote

- 通过对原装笔记软件`hvnote`的学习，Betterhvnote同样可以使用电子纸固件提供的实时笔画功能，而不是像其他笔记软件一样需要刷新或者延迟才能看到笔画。同时，与原版实现不同，Betterhvnote使用笔压和路径重绘而不是直接把笔画当作位图处理。
- 向笔记添加图片/文字或者导出笔记在原版软件一直是非常麻烦的事情，对于导入：你需要先使用官方的途径（扫描软件，wifi传输，微信打开或第三方软件）将图片导入到本地，而后在笔记中选择插入，再寻找并选中图片。对于导出，虽然图片能够扫码分享，导出的pdf需要导出到本地之后再用某种方式发送到电脑或者手机。Betterhvnote通过引入客户端解决了这个问题，客户端与Betterhvnote共用一套AI写的数据协议，通过低功耗蓝牙通信，局域网或者WIFI P2P(仅手机应用支持)收发数据，做到便捷与较快地在Betterhvnote与手机/Windows notelink之间传输(图片，文字以及导出的pdf)，整个过程中不需要离开Betterhvnote，切换到别的应用。
- 通过引入`mupdf`，Betterhvnote还支持pdf批注功能。受Kindle的Margin Paper启发，Betterhvnote通过在当前页之后新增空白页(`夹纸`)满足了额外批注区域的需要，还提供框选摘抄pdf到原文并从夹纸的摘抄项跳转回原文对应位置的便捷导航功能
- 在`mupdf`的帮助下，Betterhvnote的所有手写批注都可以以annotations的形式导出，保留后期编辑能力


### 编辑、按键与数据备份

- 笔尾落在选区内可一次删除全部所选内容，一次撤销恢复；橡皮按钮直接切换整笔／局部模式。
- 多夹页笔记本提供独立夹页缩略图列（上下停靠时为两排），摘抄可插入本笔记任意夹页并保留原文回链。无 PDF 原页时隐藏 PDF 导航。
- 应用设置中的按键绑定提供左侧 K1–K8、右侧可搜索功能目录，支持按场景绑定及指定笔槽、笔类型、粗细和颜色参数。
- 插入图片／文字可选择剪贴板，也可为剪贴板插入直接绑定快捷键。
- 应用设置 → 备份与恢复支持完整 ZIP 导出、整体恢复及恢复前副本回滚，包含笔记、图片、PDF、离线模板与设置。NoteLink 配对保留在本机，不随备份迁移。

以下为AI生成内容
## Repository layout

```text
BetterHvNote/
|-- app/                 Tablet note application (`com.betterhv.note`, minSdk 29 / targetSdk 37)
|-- framework-stubs/     Compile-only stubs for Hanvon ROM APIs
|-- phone-app/           Android NoteLink application (`com.betterhv.note.sender`)
|-- phone-desktop/       Compose Desktop NoteLink application (`phone-desktop/src/main`)
|-- transfer-core/       Platform-neutral NoteLink v4 protocol, crypto, i18n, and transfer logic (`transfer-core/src/main`)
|-- transfer-android/    Android BLE, LAN, and Wi-Fi Direct integration
|-- transfer-windows/    Windows transport and native integration
|-- update-core/         Note App / NoteLink update resolution, SHA-256 verification, and download coordinator
|-- docs/                Architecture ADRs and acceptance checklists
|-- tools/               Release build and ELF symbol inspection scripts
|-- icon-assets/         Versioned icon sources and generation script
|-- .github/             CI, release workflows, and release tag checks
|-- third_party/         Git submodules used by native ink rendering
|-- gradle/              Gradle wrapper and centralized version catalog (`libs.versions.toml`)
|-- CLAUDE.md            Architecture and contributor guidance
`-- settings.gradle.kts  Authoritative Gradle module list
```

Desktop UI (`NoteLinkDashboard`) and SQLDelight persistence schemas live under
`phone-desktop/src/main`; Android NoteLink sources live under
`phone-app/src/main` (split into `data/`, `share/`, `service/`, and root entry
components). Platform-neutral NoteLink v4 protocol, `NoteLinkI18n`, and transfer
logic live under `transfer-core/src/main`. Tablet note-taking sources under
`app/src/main/java/com/betterhv/note` are organized by duty (`backup/`, `canvas/`,
`doc/`, `export/`, `hardware/`, `ink/`, `jni/`, `notebook/`, `notelink/`, `pdf/`,
`settings/`, `storage/`, `template/`, `tool/`, `toolbar/`, `ui/`).

The local `/hvNote/` decompiled reference, build outputs, IDE state, and scratch
files are ignored via `.gitignore`; `tools/` and `docs/**/*.md` are tracked in the
repository.

## Build and test

The project requires JDK 17 and the Android SDK configured by `local.properties`.
All Gradle build outputs are centralized under root `build/<module>/` (e.g.,
`build/app/outputs/apk/`, `build/phone-app/outputs/apk/`,
`build/phone-desktop/distributions/`).

```powershell
.\gradlew.bat test
.\gradlew.bat :app:assembleDebug :phone-app:assembleDebug
.\gradlew.bat :phone-desktop:classes
.\gradlew.bat :phone-desktop:packagePortableZip
.\gradlew.bat detekt lint
```

On a connected Hanvon device, Android instrumentation tests can be run with:

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest
```

See `CLAUDE.md` for the pen pipeline, storage invariants, native integration,
and module-specific development constraints.

## 通过 NoteLink 更新 BetterHvNote

安装了支持此功能的 BetterHvNote 与 NoteLink 后，可以在平板的“设置 → 常规 → 应用更新”中选择
“通过 NoteLink 更新”。平板只负责发起请求；Android 或 Windows NoteLink 会查询最新正式版、下载并校验
官方 `SHA256SUMS.txt`，再通过已经配对的加密通道把 ARM64 APK 传到平板。平板还会核对 APK 的包名、
版本号和签名证书，随后交给 Android 系统安装器，由用户确认升级。

如果不希望 NoteLink 下载完整 APK，可在 NoteLink 设置中选择“导入 Note APK”。导入文件可以改名，
但其 SHA-256 必须与当前最新正式版的官方资产一致；校验清单无法访问或哈希不匹配时不会缓存或发送。
首次使用系统安装器时，需要在平板上允许 BetterHvNote“安装未知应用”。取消安装后，可回到同一设置页
点击“继续安装”。

这条链路依赖平板上已有支持该协议的 BetterHvNote，并不用于首次安装。首个包含该能力的版本仍需通过
原有方式安装；GitHub Release 页面入口也继续保留作为备用途径。预发布版本不会通过此链路分发。

## NoteLink 队列、发送历史与 Windows 部署

- 手机端分享入口（系统分享面板直连卡片、截图工具、微信“其他应用打开”）以文件头为准识别图片与 PDF：
  provider 返回 `null`、`application/octet-stream` 或错误类型时仍能正确入队；同批中不支持的条目只跳过自身，
  提示会给出文件名与原因。分享只会复用同一个 NoteLink 任务，无内容的快捷方式只打开应用。
- 发送完成后进入“发送历史”，记录结果、目标设备、大小与时间；默认保留最近 100 条，历史载荷保留 30 天且
  总量不超过 512 MiB，载荷被清理后“重新发送”会置灰并说明原因。
- 发送顺序可在“队列（最早优先）/ 栈（最新优先）”间切换，下一次租约立即生效；设置仅影响发送端，NoteLink v4 协议保持不变。
- Windows 端重复启动会复用已运行实例并把窗口带到前台，不再出现第二个接收服务或托盘图标。
- Windows 防火墙提示支持一键“启用规则”（UAC 提权，无需手动找脚本）。规则名为 `NoteLink-TCP-39817`（旧版
  `NoteLink TCP 39817` 仍会被识别）。规则通过 `netsh` 创建，避免 PowerShell 防火墙模块在部分机器上长时间阻塞；
  手动等价命令：`netsh advfirewall firewall add rule name=NoteLink-TCP-39817 dir=in action=allow protocol=TCP localport=39817 profile=any`。
  检查与安装输出均为 ASCII/UTF-8，中文用户名或含空格路径同样可用；安装失败时提示会显示提权进程的真实原因，
  而不是只报一个退出码。若配置文件启用了“阻止所有传入连接”，应用会明确提示，因为该设置会忽略所有允许规则。
  仍无法解决时可运行便携包内的 `Diagnose-NoteLink-Firewall.ps1`，把生成的
  `NoteLink-firewall-diagnostics.txt` 发回排查。

## PDF notebooks

The tablet app can import a local PDF as a separate notebook. MuPDF renders the
unchanged source pages and supplies fixed-layout page geometry and structured
text. Direct pen strokes remain BetterHvNote objects; a PDF region can also be
linked to a full-screen note page as either a screenshot or extracted text.
Study navigation inserts those linked pages after their source page, while read
navigation skips them. Export grafts the original PDF pages and annotations,
then adds BetterHvNote strokes as editable PDF Ink annotations.

Encrypted PDFs, reflow, and editing annotations already embedded in the source
PDF are intentionally outside the first implementation phase.

## License and source availability

BetterHvNote is licensed under `AGPL-3.0-or-later`; its corresponding source is
published at https://github.com/zentialEdwardSu/betterhvnote. PDF Ink annotation
export uses the fixed `com.artifex.mupdf:fitz:1.28.0` artifact under MuPDF's AGPL
terms. See `LICENSE` and `NOTICE` before distributing an APK.

The Hanvon declarations in `framework-stubs` are compile-only descriptions of
external interfaces supplied by the device ROM. They are not packaged in the
application. Third-party code remains covered by its own accompanying license.
