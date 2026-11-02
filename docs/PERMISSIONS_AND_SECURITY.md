# Permissions, Capabilities & Security Boundaries (权限、能力与安全边界)

本文档系统梳理 Droidance 的 Android 权限清单、导出组件 (Exported Components)、输入与执行特权、网络策略及数据流边界。

---

## 1. 权限—能力—数据流对照表

| 权限 / 特权 | 对应能力模块 | 数据流向与生命周期 | 安全边界与管控机制 |
| --- | --- | --- | --- |
| `INTERNET` / `ACCESS_NETWORK_STATE` | `core:model` | 发送任务提示词与上下文至用户配置的 LLM 服务 | 仅与用户显式配置的端点通信；无第三方统计遥测 |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_SPECIAL_USE` | `app` (`TaskExecutionService`) | 保持 Agent 执行循环在后台稳定运行 | 通知栏常驻提示任务运行状态，用户可随时从通知栏直接终止 |
| `QUERY_ALL_PACKAGES` | `core:device` (`AppManagementTool`) | 获取应用安装列表以启动目标 App | 仅用于通过包名启动目标应用或匹配前台包名，不上传非必要应用数据 |
| `POST_NOTIFICATIONS` | `app` | 发送任务状态、确认请求与报警通知 | 用户本地通知通道 |
| `READ/WRITE_CONTACTS`, `READ/SEND_SMS`, `READ/WRITE_CALENDAR`, `READ_CALL_LOG` | `core:device` (`UnifiedDeviceToolRegistrar`) | 本地系统工具执行用户指令（如查日程、读验证码、发短信） | 模型发起工具调用前受系统权限弹窗约束；敏感写入操作推荐受控执行 |
| `WRITE_SECURE_SETTINGS` | `app` (`AirSetup`) | AIR 模式通过 ADB shell 授予，用于自动维护无线调试连接 | 仅通过无线调试配对握手获取，不借此修改其他安全设置 |
| `SYSTEM_ALERT_WINDOW` | `app` (悬浮控制/Island 交互) | 悬浮窗显示当前 Agent 思考状态与快速中断控制 | 用户手动授予悬浮窗权限，可由系统设置随时收回 |
| Paired ADB shell (UID 2000) | `core:device` (`SidecarInputController`, `AdbShellExecutor`) | Standard virtual-display input, observation and device commands | Wireless debugging pairing is required; no privileged-UID fallback is provided. A shell-UID supervisor restarts the sidecar after abnormal exits without ADB or Wi-Fi. It stops on pairing reset, intentional sidecar exit, phone restart or APK removal, and does not restart the sidecar once developer options are off |

---

## 2. 导出组件 (Exported Components) 审计

| 组件名称 | 导出状态 (`exported`) | 权限/保护机制 | 用途与安全评估 |
| --- | --- | --- | --- |
| `MainActivity` | `true` | `MAIN` / `LAUNCHER` | 应用桌面启动入口，正常公开 |
| `VirtualSpaceViewerActivity` | `true` | `singleTop`, 专用 taskAffinity | 虚拟屏本地预览视图，不处理外部非受信 Intent Extra |
| `VirtualSpaceProbeActivity` | `true` | 仅内部尺寸/DPI探测 | 瞬态 Activity，不含业务逻辑 |
| `NotificationWatcher` | `false` | `BIND_NOTIFICATION_LISTENER_SERVICE` | 非导出；仅系统服务可绑定，用于自动化监听 |
| `TaskExecutionService` | `false` | 内部启动 | 仅内部组件启动，防止外部伪造任务 |
| `KeepAliveJobService` | `false` | `BIND_JOB_SERVICE` | 仅 Android JobScheduler 可调度 |
| `BootReceiver` | `true` | `BOOT_COMPLETED` 等系统广播 | 开机自动恢复持久定时任务 |
| `AirModePairingReceiver` | `false` | 内部广播 | 仅应用内部处理配对验证码 |
| `SidecarBinderReceiver` | `true` | `android.permission.DUMP` (系统签名/shell 特权) | 仅由本地 shell 身份的 sidecar 进程回传 Binder 凭据，外部无特权应用无法发送 |
| `FileProvider` | `false` | `grantUriPermissions="true"` | 仅受控授权分享临时图片/导出文件 |

---

## 3. 网络安全与明文传输策略 (`network_security_config`)

- **明文传输策略**：Android 网络安全配置默认禁止明文，仅对 `localhost`、`127.0.0.1` 和 `::1` 的环回地址放行；局域网和公网 HTTP 均被禁止。
- **Provider 连接**：模型服务端点要求使用 HTTPS。自建服务可配置 TLS 或使用经过身份验证的本地隧道。

---

## 4. 人机协作与接管边界 (`ask_human`)

涉及以下高危动作时，Agent 策略要求主动暂停并触发接管：
1. **金融支付与转账确认**；
2. **银行/重要服务一次性验证码 (OTP)**；
3. **账号注销与高危系统重置**。

## Provider transport

Provider endpoints require HTTPS. Android's network security policy permits cleartext only for `localhost`, `127.0.0.1` and `::1`; LAN and public HTTP endpoints are blocked. Use TLS or an authenticated local tunnel for self-hosted providers. No provider credentials are bundled or seeded on installation.
