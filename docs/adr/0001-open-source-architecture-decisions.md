# ADR 0001: 开源架构核心设计决策

- **状态**: Accepted
- **日期**: 2026-09-11
- **适用范围**: Droidance 开源架构整改（P0 基线）

## 1. 模型依赖方向

### 背景与上下文
在此前的架构重构探讨中，曾提出过将模型接口及所有消息 DTO 搬迁至 `core:agent`，并使 `core:model` 反向依赖 `core:agent`。

### 决策
**保持 `core:agent -> core:model` 的单向依赖。**
- `core:model` 负责模型抽象层、请求/响应适配、流式事件、Provider 传输协议以及 Tokens 统计。
- `core:model` 暴露面向任务协调的最小稳定模型 API（如 `ModelClient` / `ModelSession`），在模块内收紧可见性，隐藏具体的 HTTP Client 与 Provider 内部协议。
- `core:model` 绝不依赖任务、回合、工具结算、Memory 存储或业务状态。
- 在测试中，`core:agent` 可直接基于 `core:model` 的抽象接口构建假实现（FakeModelClient），无需拉起真实网络连接即可完成纯 JVM 任务协调测试。

---

## 2. 任务与工具结算责任

### 背景与上下文
系统中存在任务生命周期管理（`AgentTurnLedger`）、回合协调（`DurableModelRoundCoordinator`）、工具执行入口（`ToolExecutor`）以及学习录制（`OperationRecorder`）。此前探讨曾提出由新的 `ToolRunner` 统一接管所有数据库写入。

### 决策
**不设立笼统的“超级写入者”，按事实类型实行职责唯一的所有权与原子结算：**
1. **Turn 状态事实**：由 `core:agent` 的任务协调逻辑与 `AgentTurnLedger` 唯一负责，记录 Turn 的创建、激活、接管、终态（Success / Failure / Cancelled / Preempted）。UI、通知与前台服务只作为投影消费者，严禁自行写入或判定终态。
2. **Model Round 与 Call 事实**：由 `DurableModelRoundCoordinator` 维护 round/call/attempt 的持久化与幂等事务。
3. **Tool 结果与附件**：工具具体实现返回受控回执（结构化事实与产物引用），由单一的结算入口（沿用 `finishToolWithAttachments` 事实协议）完成持久化提交。
4. **学习会话事实**：`OperationRecorder` 仅作为独立学习事件消费者记录示教与操作数据，不替代工具执行与 Turn 结算，严禁引入双写。

---

## 3. 设备副作用未知处理

### 背景与上下文
移动端物理操作（点击、滑动、按键、Shell 命令等）发生于外部 Android 系统或远端设备，本地数据库事务无法跨越物理设备构成两阶段提交。当网络中断、进程异常、超时或权限撤回发生时，设备的实际状态可能处于“副作用已发生”、“部分发生”或“未发生”的未知状态。

### 决策
1. **严禁无根据假定成功或盲目重发**：设备动作一旦返回未知或执行中断，核心结算必须如实记录为未确认状态（Unconfirmed / Unknown Side Effect），不得自动重放造成破坏性重复点击或支付风险。
2. **状态恢复原则**：进程中断后保留当前“终止中断回合并留持久通知”的产品语义，不在重启后静默续跑物理动作。由 Agent 在新回合通过重新截屏观察（Observation）由模型根据实际画面决策后续路径。
3. **幂等性边界**：本地数据库幂等保证事实只记一次；物理操作必须依赖 Agent 闭环观察修正，不虚假宣称物理操作 exactly-once。
