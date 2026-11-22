# NoteLink v4 设备矩阵与质量门禁

本文件记录 v4 协议可验证的设备/环境组合、当前结果与明确豁免。日期：2026-10-31。

## 门禁

| 检查 | 位置 | 覆盖 |
| --- | --- | --- |
| `./gradlew test detekt lint` | `.github/workflows/ci.yml` `android-and-quality` | 全模块单测、detekt、Android lint、debug 构建 |
| `buf lint` + `buf breaking` | `.github/workflows/ci.yml` `proto-schema` | `transfer-core/src/main/proto` 的 v4 schema |
| `:transfer-core:test` | 同上（`test`） | 记录层、握手、会话、LAN 票据、队列/推送、故障注入、灰度 |
| `:phone-desktop:test` / `:transfer-windows:test` | `windows-portable` | Windows JNA 契约、桌面队列迁移 |
| `:phone-app:testDebugUnitTest` | 本地/CI `test` | Android 发送端队列与 v4 server 接线 |

合入门槛：上述任务全部通过；新增 protobuf 字段必须通过 `buf breaking`；不允许跳过 detekt 或 lint。

## 设备矩阵

| 设备/环境 | 系统 | 角色 | 状态 | 备注 |
| --- | --- | --- | --- | --- |
| Hanvon N10Pro（`9616725030000713`） | Android 14 / API 34 | 平板接收端、BLE 外设 | 已用于 v3 真机回归 | v4 `BleProtocolClient` 已编译，待 v4 接收端接线后回归 |
| Android 手机（任意 API 28+） | Android 14 | 发送端、BLE 中心 | 模拟器/单测通过 | 需要真实 BLE 外设做端到端 |
| Windows 11 x64 | 23H2+ | 桌面接收端、BLE 外设 | JNA 契约测试通过 | Wi-Fi Direct P2P 明确不支持 |
| Linux CI | ubuntu-24.04 | 纯 JVM 协议层 | 通过 | 无 BLE/LAN 硬件 |

## 豁免与边界

- Windows Wi-Fi Direct P2P：明确不做（需求 R7），桌面只走 LAN。
- R8/混淆验证：仓库当前 `isMinifyEnabled = false`，v4 的 protobuf-lite 反射面不构成发布风险；启用混淆时需重跑 golden frame 与 JNA 测试。
- 冷启动 P2P 编队不计入 1 s 目标；1 s 只适用于 LAN 或已预热 P2P。
- 24 h 弱网长跑与 Doze 压测需要真机时间窗，未纳入每次 CI。

## 回归记录

| 日期 | 范围 | 结果 |
| --- | --- | --- |
| 2026-10-31 | `:transfer-core:test`（记录/握手/会话/LAN/队列/故障注入/灰度） | 通过 |
| 2026-10-31 | `:phone-desktop:test` + `:transfer-windows:test` | 通过 |
| 2026-10-31 | `buf lint` | 通过 |
| 2026-10-31 | MCP `build_project`（全项目） | 通过，零问题 |
