// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.daemon

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskNotificationVisibilitySourceContractTest {
    @Test
    fun notificationOutcomesAreLoggedInsteadOfSilentlySwallowed() {
        val service = projectFile("app/src/main/java/com/droidance/daemon/TaskExecutionService.kt").readText()
        val keepAlive = projectFile("app/src/main/java/com/droidance/daemon/KeepAliveJobService.kt").readText()

        // 真因：SecurityException 被 catch 后直接吞掉，真机上任务完成/需要接管的通知到底有没有
        // 发出去完全不可见，只能靠猜。加 logNotifyResult：既记结果，也记 areNotificationsEnabled()
        // ——这个比异常更常见的沉默失败原因（用户/系统关闭了通知总开关）之前完全没被诊断过。
        assertTrue(service.contains("private fun logNotifyResult("))
        assertTrue(service.contains("NotificationManagerCompat.from(context).areNotificationsEnabled()"))
        assertTrue(service.contains("logNotifyResult(context, \"task_complete\", result)"))
        assertTrue(service.contains("logNotifyResult(context, \"manual_takeover\", result)"))
        assertTrue(service.contains("logNotifyResult(context, \"task_started\", result)"))
        assertTrue(service.contains("logNotifyResult(context, \"task_interrupted\", result)"))
        assertTrue(keepAlive.contains("fun debugLogManager(): DebugLogManager"))
    }

    @Test
    fun onDemandForegroundLifecycleIsEnforced() {
        val service = projectFile("app/src/main/java/com/droidance/daemon/TaskExecutionService.kt").readText()
        val app = projectFile("app/src/main/java/com/droidance/DroidanceApp.kt").readText()
        val coordinator = projectFile("app/src/main/java/com/droidance/automation/AutomationCoordinator.kt").readText()
        val runtime = projectFile("app/src/main/java/com/droidance/agent/AgentRuntime.kt").readText()

        // 待命常驻服务不再由 DroidanceApp 默认无条件启动
        assertFalse(app.contains("TaskExecutionService.startStandby(this)"))
        assertFalse(app.contains("TaskExecutionService.startStandby(this@DroidanceApp)"))

        // 前台服务支持按需开启与退出
        assertTrue(service.contains("ACTION_START_EXECUTION"))
        assertTrue(service.contains("ACTION_STOP_EXECUTION"))
        assertTrue(service.contains("fun startExecution("))
        assertTrue(service.contains("fun stopExecution("))
        assertTrue(service.contains("stopForeground(STOP_FOREGROUND_REMOVE)"))

        // AutomationCoordinator 在执行任务时进入前台并在结束时退出前台
        assertTrue(coordinator.contains("TaskExecutionService.notifyAutomationStarted("))
        assertTrue(coordinator.contains("TaskExecutionService.startExecution("))
        assertTrue(coordinator.contains("TaskExecutionService.stopExecution("))

        // AgentRuntime 在结束任务时退出前台
        assertTrue(runtime.contains("foregroundHost.stopExecution()"))
    }

    @Test
    fun taskLifecycleNotificationMethodsAreExposed() {
        val service = projectFile("app/src/main/java/com/droidance/daemon/TaskExecutionService.kt").readText()

        assertTrue(service.contains("fun notifyAutomationStarted("))
        assertTrue(service.contains("fun notifyTaskCompleted("))
        assertTrue(service.contains("fun notifyTaskInterrupted("))
        assertTrue(service.contains("fun notifyManualTakeoverRequired("))
        assertTrue(service.contains("fun cancelManualTakeover("))
        assertTrue(service.contains("fun notifyTaskDone("))
    }

    @Test
    fun everyTaskNotificationIsSuppressedInForegroundWithoutAForceBypass() {
        val service = projectFile("app/src/main/java/com/droidance/daemon/TaskExecutionService.kt").readText()
        val runtime = projectFile("app/src/main/java/com/droidance/agent/AgentRuntime.kt").readText()
        val keepAlive = projectFile("app/src/main/java/com/droidance/daemon/KeepAliveJobService.kt").readText()

        assertTrue(service.contains("isAppForeground(context)"))
        listOf(
            "notifyAutomationStarted" to "task_started",
            "notifyTaskCompleted" to "task_complete",
            "notifyTaskInterrupted" to "task_interrupted",
            "notifyTaskDone" to "task_complete",
            "notifyManualTakeoverRequired" to "manual_takeover",
            "notifyRuntimePermissionRequired" to "runtime_permission"
        ).forEach { (method, kind) ->
            val body = service.substringAfter("fun $method(").substringBefore("val l10n =")
            assertTrue("$method must suppress notifications before posting", body.contains(
                "if (suppressForegroundNotification(context, \"$kind\")) return"
            ))
            assertFalse("$method must have no foreground bypass", body.contains("force:"))
        }
        assertTrue(service.contains("if (!isAppForeground(context)) return false"))
        assertTrue(service.contains("\"\${kind}_suppressed_foreground\""))
        assertTrue(service.contains("entryPoint.runtimeSurfaceController().isViewerExpanded"))
        assertTrue(runtime.contains("!foregroundState.isInForeground"))
        assertTrue(keepAlive.contains("fun foregroundState(): com.droidance.agent.AppForegroundState"))
    }

    private fun projectFile(path: String): File {
        val userDir = System.getProperty("user.dir") ?: "."
        var current = File(userDir).absoluteFile
        while (true) {
            val candidate = File(current, path)
            if (candidate.exists()) return candidate
            current = current.parentFile ?: break
        }
        error("Cannot locate $path from $userDir")
    }
}
