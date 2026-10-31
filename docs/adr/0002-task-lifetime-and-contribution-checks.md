# ADR 0002: 任务生命周期、会话恢复与贡献验证

- 状态：Accepted
- 日期：2026-09-12
- 基线：bfd481a / 20260912-V2

## 问题

生产主循环已在纯 JVM 模块，但请求的开始、零工具激活、异常结算和资源清理由 Android Runtime 手工串联。聊天界面将显示消息反向恢复为模型历史。测试和原生构建尚缺统一贡献入口。

## 决策

1. 保留 app 与五个 core 模块以及单 Agent 串行执行。`TaskLifecycle` 负责模型租约、Turn 创建、真实 Phase A 协议与持久化、工具激活、终态和清理顺序。`TaskCoordinator` 继续执行唯一生产循环。Android Runtime 通过宿主回调提供设备资源清理及本地化中断说明，并保留 Service、通知和界面状态。
2. 正常返回要求 Turn 已结算；异常通过同一生命周期入口补写中断事实。收尾失败不替换原始异常，资源清理继续执行。设备资源先释放，然后释放任务上下文，最后关闭模型租约。未知设备副作用仍禁止自动重放。
3. `ConversationHistoryReader` 从 EventStore 的类型化投影生成模型历史，UIMessage 不再参与模型上下文恢复。`ConversationTaskController` 在应用作用域创建任务，先注册 Job 再启动，通过宿主接口提交并恢复会话；ViewModel 只处理呈现回调。真实任务执行仍受 Runtime 的串行锁保护。
4. 本版本移除 IM 适配器、SDK、消息收发、远程接管和相关设置。任务入口保留本地对话与调度，结果通过 App 和本地通知呈现。
5. Gradle `verifyArchitecture` 以实际项目依赖核对六模块允许图，同时保护纯 JVM 插件和 Android 依赖/导入边界。这是有限静态门禁，不宣称替代行为测试或全面 API 审计。
6. 原生输入驱动由固定 NDK 从源码生成，通过 AGP generated-assets API 接入 Debug/Release。删除源码资产目录中的预编译输入驱动。libadb 继续作为已记录的预编译输入，其精确上游 revision 缺口单独保留，不能据打包校验声称已源码复现。
7. `scripts/verify.sh` 作为本地与 CI 的共同入口。默认执行六模块单测、脚本测试、架构门禁、Lint 和 Debug 构建；`platform` 使用隔离 Runner 运行 Room 与设置测试；`release` 编译 R8 变体并核验原生打包。每个 APK 记录 commit、工作树状态、版本与哈希，不自动发布或改变签名策略。

## 验收边界

行为回归覆盖同一生产生命周期、真实协调器和宿主替身。平台检查使用独立包名 `com.droidance.repositorytest`、数据库和偏好文件；指定仓库测试 Runner 时，Debug manifest 关闭后台服务与接收器，阻止包更新广播或系统驻留恢复启动 Hilt 服务。普通 Debug 与 Release 的后台入口保持原行为。平台检查不启动真实 Provider。Release 编译/打包检查与安装运行验证分别记录；以上均不代替真实模型驱动的设备任务语义验收。

本决策没有增加业务规则、第二个 Agent、兼容转发层或更多编译模块。后续改动应先看变更影响和状态所有权，再决定是否增加抽象。
