# BetterHvNote

BetterHvNote 希望成为比汉王N10 Pro 2自带笔记软件更加顺手的笔记软件，其主要目标是：通过更高效的用户交互，更简洁的页面设计以及配套手机与电脑应用，更提供更加接近纸的笔记体验。

目前只支持汉王N10 Pro 2，Notelink只支持带有BLE的Windows和Android设备
## Features of Betterhvnote

- 通过对原装笔记软件`hvnote`的学习，Betterhvnote同样可以使用电子纸固件提供的实时笔画功能，而不是像其他笔记软件一样需要刷新或者延迟才能看到笔画。同时，与原版实现不同，Betterhvnote使用笔压和路径重绘而不是直接把笔画当作位图处理。
- 向笔记添加图片/文字或者导出笔记在原版软件一直是非常麻烦的事情，对于导入：你需要先使用官方的途径（扫描软件，wifi传输，微信打开或第三方软件）将图片导入到本地，而后在笔记中选择插入，再寻找并选中图片。对于导出，虽然图片能够扫码分享，导出的pdf需要导出到本地之后再用某种方式发送到电脑或者手机。Betterhvnote通过引入客户端解决了这个问题，客户端与Betterhvnote共用一套AI写的数据协议，通过低功耗蓝牙通信，局域网或者WIFI P2P(仅手机应用支持)收发数据，做到便捷与较快地在Betterhvnote与手机/Windows notelink之间传输(图片，文字以及导出的pdf)，整个过程中不需要离开Betterhvnote，切换到别的应用。
- 通过引入`mupdf`，Betterhvnote还支持pdf批注功能。受Kindle的Margin Paper启发，Betterhvnote通过在当前页之后新增空白页(`夹纸`)满足了额外批注区域的需要，还提供框选摘抄pdf到原文并从夹纸的摘抄项跳转回原文对应位置的便捷导航功能
- 在`mupdf`的帮助下，Betterhvnote的所有手写批注都可以以annotations的形式导出，保留后期编辑能力


以下为AI生成内容
## Repository layout

```text
BetterHvNote/
|-- app/                 Tablet note application
|-- framework-stubs/     Compile-only stubs for Hanvon ROM APIs
|-- phone-app/           Android NoteLink app and shared NoteLink sources
|-- phone-desktop/       Compose Desktop NoteLink application
|-- transfer-core/       Platform-neutral protocol, crypto, and transfer logic
|-- transfer-android/    Android BLE, LAN, and Wi-Fi Direct integration
|-- transfer-windows/    Windows transport and native integration
|-- icon-assets/         Versioned icon sources and generation script
|-- .github/             CI, release workflows, and release tag checks
|-- third_party/         Git submodules used by native ink rendering
|-- gradle/              Gradle wrapper files
|-- CLAUDE.md            Architecture and contributor guidance
`-- settings.gradle.kts  Authoritative Gradle module list
```

Shared NoteLink UI and persistence sources live under
`phone-app/src/commonMain`. Desktop-specific implementation and resources live
under `phone-desktop/src/main`; the `phone-desktop` module wires these source
sets into the desktop distribution. Platform-neutral transfer sources are split
between `transfer-core/src/commonMain` and `transfer-core/src/main` as declared
in `transfer-core/build.gradle.kts`.

The local `hvNote/`, `tools/`, build outputs, IDE state, and architecture plans
are intentionally ignored. They are development inputs or generated artifacts,
not repository modules.

## Build and test

The project requires JDK 17 and the Android SDK configured by `local.properties`.

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
