// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0
package com.droidance.agent.device

import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.droidance.core.device.agent.device.INativeInputDriver
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** System input driver only. No model, task queue or application graph runs in this process. */
class RuntimeDriverBinderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.getIntExtra(com.droidance.core.device.agent.device.SidecarBinderReceiver.EXTRA_SIDECAR_PROTOCOL_VERSION, -1) !=
            com.droidance.core.device.agent.device.VirtualSpaceSidecarService.SIDECAR_PROTOCOL_VERSION) return
        @Suppress("DEPRECATION") val container = intent.getParcelableExtra<com.droidance.core.device.agent.device.SidecarBinderContainer>(
            com.droidance.core.device.agent.device.SidecarBinderReceiver.EXTRA_BINDER_CONTAINER) ?: return
        val sidecar = com.droidance.core.device.agent.device.IVirtualSpaceSidecar.Stub.asInterface(container.binder)
        val driver = NativePlatformDriver(context.applicationContext)
        sidecar.setPlatformDriver(driver.binder)
        container.binder.linkToDeath({ driver.close() }, 0)
    }
}

private class NativePlatformDriver(private val context: Context) {
    private val programs = java.util.concurrent.ConcurrentHashMap<String, android.content.ServiceConnection>()
    fun close() {
        programs.values.forEach { runCatching { context.unbindService(it) } }
        programs.clear()
        Handler(Looper.getMainLooper()).post { AgentTextInputMethodService.endSession(context, false) }
    }
    val binder = object : INativeInputDriver.Stub() {
        override fun getWindowSession(): android.os.IBinder {
            check(Binder.getCallingUid() == 2000)
            val identity = Binder.clearCallingIdentity()
            return try {
                val window = Class.forName("android.view.WindowManagerGlobal").getMethod("getWindowSession").invoke(null)
                Class.forName("android.view.IWindowSession").getMethod("asBinder").invoke(window) as android.os.IBinder
            } finally { Binder.restoreCallingIdentity(identity) }
        }
        override fun startProgram(instance: String): android.os.IBinder {
            check(Binder.getCallingUid() == 2000)
            if (android.os.Build.VERSION.SDK_INT < 29) throw IllegalStateException("PROGRAM_REQUIRES_API_29")
            val connected = java.util.concurrent.CompletableFuture<android.os.IBinder>()
            val connection = object : android.content.ServiceConnection {
                override fun onServiceConnected(name: android.content.ComponentName, binder: android.os.IBinder) { connected.complete(binder) }
                override fun onServiceDisconnected(name: android.content.ComponentName) { programs.remove(instance, this) }
                override fun onNullBinding(name: android.content.ComponentName) { connected.completeExceptionally(IllegalStateException("Program worker unavailable")) }
            }
            check(programs.putIfAbsent(instance, connection) == null)
            val identity = Binder.clearCallingIdentity()
            try {
                check(context.bindIsolatedService(Intent(context, com.droidance.core.device.program.ProgramWorkerService::class.java),
                    Context.BIND_AUTO_CREATE, instance, context.mainExecutor, connection))
                return connected.get(5, TimeUnit.SECONDS)
            } catch (error: Exception) { programs.remove(instance); runCatching { context.unbindService(connection) }; throw error }
            finally { Binder.restoreCallingIdentity(identity) }
        }
        override fun closeProgram(instance: String) {
            check(Binder.getCallingUid() == 2000)
            val identity = Binder.clearCallingIdentity()
            try { programs.remove(instance)?.let { context.unbindService(it) } }
            finally { Binder.restoreCallingIdentity(identity) }
        }

        override fun command(operation: String, arguments: Bundle): android.os.ParcelFileDescriptor {
            check(Binder.getCallingUid() == 2000) { "Only the resident shell may control input" }
            if (operation == "content") {
                val identity = Binder.clearCallingIdentity()
                return try { com.droidance.core.device.agent.device.RuntimeWire.write(context.cacheDir, content(arguments)) }
                    finally { Binder.restoreCallingIdentity(identity) }
            }
            val work = FutureTask { dispatch(operation, arguments) }
            Handler(Looper.getMainLooper()).post(work)
            val reply = try { work.get(5, TimeUnit.SECONDS) }
                catch (error: Exception) { work.cancel(false); throw error }
            return com.droidance.core.device.agent.device.RuntimeWire.write(context.cacheDir, reply)
        }
    }

    private fun content(args: Bundle): Bundle = kotlinx.coroutines.runBlocking {
        val broker = com.droidance.core.device.permission.PermissionBroker(context) {
            error("Permissions must be granted before dispatch to the platform driver")
        }
        val service = com.droidance.core.device.tools.AndroidContentQueryService(context,
            broker, { "platform" to "driver" }, { "Platform content operation" }, { 0L }, { }, { _, _ -> }, null)
        val input = args.getString("arguments")!!
        val result = when (args.getString("operation")) {
            "query" -> service.query(input)
            "insert" -> service.insert(input)
            "update" -> service.update(input)
            "delete" -> service.delete(input)
            "batch" -> service.applyBatch(input)
            else -> error("Unknown content operation")
        }
        Bundle().apply { putString("result", kotlinx.serialization.json.Json.encodeToString(
            com.droidance.core.agent.event.ToolExecutionResult.serializer(), result)) }
    }

    private fun dispatch(operation: String, args: Bundle) = Bundle().apply {
        when (operation) {
            "probeReset" -> { VirtualSpaceProbeLanding.displayId = null }
            "probeFinish" -> VirtualSpaceProbeLanding.finishActive()
            "probeState" -> {
                VirtualSpaceProbeLanding.displayId?.let { putInt("display", it) }
                putLong("at", VirtualSpaceProbeLanding.landedAtMs)
            }
            "notifications" -> {
                val watcher = com.droidance.automation.NotificationWatcher.instance
                putBoolean("available", watcher != null)
                putString("items", kotlinx.serialization.json.Json.encodeToString(
                    kotlinx.serialization.builtins.ListSerializer(com.droidance.automation.NotificationInfo.serializer()),
                    watcher?.getAllNotifications().orEmpty()))
            }
            "notificationClick" -> putBoolean("result", com.droidance.automation.NotificationWatcher.instance
                ?.clickNotification(args.getString("key")!!, if (args.containsKey("display")) args.getInt("display") else null) == true)
            "notificationDismiss" -> com.droidance.automation.NotificationWatcher.instance?.dismissNotification(args.getString("key")!!)
            "restore" -> putString("ime", AgentTextInputMethodService.restorationTarget(context))
            "begin" -> AgentTextInputMethodService.beginSession(context, args.getString("ime")!!)
            "end" -> AgentTextInputMethodService.endSession(context, args.getBoolean("restored"))
            "delivered" -> putBoolean("delivered", AgentTextInputMethodService.pendingCommitDelivered())
            "commit" -> {
                val display = args.getInt("display")
                val pkg = args.getString("package")!!
                putString("state", AgentTextInputMethodService.connectionState(display, pkg))
                AgentTextInputMethodService.commit(display, pkg, args.getString("text")!!, args.getBoolean("clear"))?.let {
                    putBoolean("dispatched", true); putBoolean("success", it.success); putString("message", it.message)
                    putString("diagnostic", it.diagnosticCode)
                    it.textInputEvidence?.let { evidence ->
                        putBoolean("focus", evidence.focusConfirmed); putBoolean("payload", evidence.payloadConfirmed)
                        putBoolean("injected", evidence.eventInjected); putBoolean("changed", evidence.screenChanged)
                        putBoolean("verified", evidence.deliveryVerified)
                    }
                }
            }
            else -> error("Unknown input driver operation")
        }
    }
}
