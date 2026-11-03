// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0
package com.droidance.runtime

import android.app.Application
import android.content.Context
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import com.droidance.agent.AgentRuntime
import com.droidance.agent.AppForegroundState
import com.droidance.agent.device.VirtualSpaceManager
import com.droidance.core.agent.*
import com.droidance.core.device.agent.device.*
import com.droidance.ui.overlay.RuntimeSurfaceController
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface RuntimeOwnerEntryPoint {
    fun automations(): com.droidance.automation.AutomationManager
    fun automationStore(): com.droidance.automation.AutomationStore
    fun database(): com.droidance.core.persistence.agent.event.AgentEventDatabase
    fun readiness(): com.droidance.agent.device.VirtualSpaceReadiness
    fun usage(): com.droidance.config.TokenUsageRepository
    fun takeovers(): com.droidance.automation.AppTakeoverStore
    fun tasks(): com.droidance.agent.ConversationTaskController
    fun runtime(): AgentRuntime
    fun events(): com.droidance.core.agent.event.AgentEventStore
    fun files(): com.droidance.memory.MarkdownFileManager
    fun providers(): com.droidance.settings.ProviderConfigManager
    fun rules(): com.droidance.config.RuleConfigManager
    fun historyDeletion(): com.droidance.agent.ConversationHistoryDeletion
    fun diagnostics(): com.droidance.core.device.debug.DiagnosticsCollector
    fun permissions(): com.droidance.core.device.permission.PermissionBroker
    fun foreground(): AppForegroundState
    fun surfaces(): RuntimeSurfaceController
    fun virtualSpace(): VirtualSpaceManager
    fun preparation(): com.droidance.daemon.DeviceExecutionGuard
    fun blobs(): com.droidance.core.agent.blob.BlobStore
}

/** Instantiates the existing single Agent graph in the resident shell process. */
object RuntimeProcessHost {
    private var instance: Owner? = null

    @JvmStatic
    @Synchronized
    fun start(shell: Context, sidecar: IBinder, packageName: String, clientUid: Int, installationId: String,
        storage: ParcelFileDescriptor): IBinder {
        instance?.let { storage.close(); return it }
        check(RuntimeProcessRole.isOwner)
        val identity = java.util.UUID.fromString(installationId).toString()
        val root = File("/data/local/tmp/droidance-runtime-$clientUid-$identity")
        // The original application is transferred once. Subsequent clients attach to this owner.
        if (!File(root, "initialized").isFile) {
            RuntimeStorageSnapshot.restore(root, storage)
            File(root, "initialized").writeText("1")
        } else storage.close()
        SidecarBinderRegistry.set(2000, sidecar)
        isolatePlatformHelperFailures()
        val initialize = java.util.concurrent.FutureTask {
            val application = RuntimeApplicationContext.create(shell, packageName, root)
            application.onCreate()
            // Acquire the platform-owned session while the submitting client is awake. Drawing
            // and input subsequently use the system session directly, without calling that client.
            val session = RuntimePlatformDriver.connect().windowSession
            val proxy = Class.forName("android.view.IWindowSession\$Stub")
                .getMethod("asInterface", IBinder::class.java).invoke(null, session)
            Class.forName("android.view.WindowManagerGlobal").getDeclaredField("sWindowSession")
                .apply { isAccessible = true }.set(null, proxy)
            Owner(application, clientUid)
        }
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) initialize.run()
        else android.os.Handler(android.os.Looper.getMainLooper()).post(initialize)
        return initialize.get(30, java.util.concurrent.TimeUnit.SECONDS).also { instance = it }
    }

    /**
     * Vendor framework helpers may start background threads that call services which reject
     * the shell process (for example content observers keyed to an application PID). Such a
     * helper thread may end, but it must not terminate the Agent owner. Failures carrying any
     * Droidance frame, and failures on the main thread, keep the default crash behavior.
     */
    private fun isolatePlatformHelperFailures() {
        val fallback = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            val owned = generateSequence(error) { it.cause }.any { cause ->
                cause.stackTrace.any { it.className.startsWith("com.droidance.") }
            }
            if (thread === android.os.Looper.getMainLooper().thread || owned) fallback?.uncaughtException(thread, error)
            else android.util.Log.w("DroidanceRuntime", "Platform helper thread ${thread.name} ended", error)
        }
    }

    private class Owner(private val application: Application, private val clientUid: Int) : IAgentRuntimeOwner.Stub() {
        private val entry = EntryPointAccessors.fromApplication(application, RuntimeOwnerEntryPoint::class.java)
        private val runtime = entry.runtime()
        private val events = RuntimeEventServer(entry.events())
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val transport = RuntimeTransportPolicy.read(entry.rules())
        private inner class Task {
            @Volatile var finishedAt = 0L
            var baseOffset = 0
            var characters = 0
            private val buffer = java.util.ArrayDeque<AssistantStreamChunk>()
            @Synchronized fun append(chunk: AssistantStreamChunk) {
                buffer.addLast(chunk); characters += chunk.text.length
                while (buffer.size > transport.maxBufferedChunks || characters > transport.maxBufferedChars) {
                    characters -= buffer.removeFirst().text.length; baseOffset++
                }
            }
            @Synchronized fun progress(offset: Int): Bundle = Bundle().apply {
                val cursor = maxOf(offset, baseOffset)
                val chunks = buffer.drop(cursor - baseOffset).take(transport.resultBatchChunks)
                putInt("nextOffset", cursor + chunks.size)
                putParcelableArrayList("chunks", ArrayList(chunks.map { chunk -> Bundle().apply {
                    putString("text", chunk.text); putString("round", chunk.roundId)
                } }))
                if (cursor + chunks.size == baseOffset + buffer.size) putString("result", result)
            }
            @Volatile var started = false
            @Volatile var result: String? = null
            @Volatile var error: String? = null
        }
        private val tasks = ConcurrentHashMap<String, Task>()
        @Volatile private var presentation: IBinder? = null

        private inline fun <T> authenticated(action: () -> T): T {
            val caller = Binder.getCallingUid()
            check(caller == clientUid || caller == android.os.Process.myUid()) { "Runtime caller is not authorized" }
            val token = Binder.clearCallingIdentity()
            return try { action() } finally { Binder.restoreCallingIdentity(token) }
        }

        override fun command(operation: String, arguments: Bundle): ParcelFileDescriptor = authenticated {
            val descriptor = arguments.getParcelable<ParcelFileDescriptor>("payload")
            val values = if (descriptor != null) RuntimeWire.read(descriptor) else arguments
            val result = when (operation) {
                "prepareDisplay" -> {
                    val ready = entry.readiness().ensureReadyResult(values.getBoolean("probe"))
                    Bundle().apply {
                        putString("status", ready.status.name); putString("message", ready.message)
                        ready.displayId?.let { putInt("display", it) }
                        entry.virtualSpace().activeDisplayMetrics()?.let {
                            putInt("width", it.width); putInt("height", it.height); putInt("density", it.densityDpi)
                        }
                    }
                }
                "automationAdmin" -> automationAdmin(values)
                "permission" -> {
                    val broker = entry.permissions()
                    val id = values.getString("id")!!
                    when (values.getString("operation")) {
                        "prompt" -> broker.requestSystemPermissionDialog(id)
                        "consume" -> broker.consumeSystemDialogRequest(id)
                        "launched" -> broker.markLaunched(id)
                        "resolve" -> broker.resolve(id, values.getBoolean("settings"))
                        else -> error("Unsupported permission presentation command")
                    }
                    Bundle()
                }
                "attach" -> {
                    val client = requireNotNull(arguments.getBinder("client"))
                    presentation = client
                    client.linkToDeath({ if (presentation == client) {
                        presentation = null
                        entry.foreground().update(false)
                        entry.surfaces().setViewerExpanded(false)
                    } }, 0)
                    Bundle()
                }
                "configuration" -> {
                    if (RuntimeConfigurationSync.apply(application, values)) entry.providers().reloadFromStorage()
                    val locale = java.util.Locale.forLanguageTag(values.getString("language")!!)
                    val configuration = android.content.res.Configuration(application.resources.configuration)
                    configuration.setLocale(locale)
                    @Suppress("DEPRECATION") application.resources.updateConfiguration(configuration, application.resources.displayMetrics)
                    Bundle()
                }
                "deleteHistory" -> {
                    runBlocking { entry.historyDeletion().delete(values.getStringArrayList("threads")?.toSet()) }
                    Bundle()
                }
                "clearLogs" -> {
                    runBlocking { runtime.withStorageMaintenance {
                        entry.diagnostics().clearAll()
                        entry.files().deleteFile("debug/conversation-latest.md")
                    } }
                    Bundle()
                }
                "file" -> managedFile(values)
                "events" -> runBlocking { events.exchange(values.getString("operation")!!, values) }
                "conversation", "automation", "takeover" -> submit(operation, values)
                "poll" -> poll(values)
                "foreground" -> { entry.foreground().update(values.getBoolean("visible")); Bundle() }
                "viewer" -> { entry.surfaces().setViewerExpanded(values.getBoolean("expanded")); Bundle() }
                "stop" -> { runtime.stopCurrentMobileUse(values.getString("origin") ?: "user"); Bundle() }
                "cancel" -> { runtime.cancelCurrentTask(values.getString("taskId"), values.getString("origin") ?: "user"); Bundle() }
                "pause" -> { runtime.togglePause(); Bundle() }
                "resume" -> Bundle().apply { putBoolean("result", runtime.resumeManualTakeover()) }
                "takeControl" -> Bundle().apply { putBoolean("result", runtime.beginManualTakeoverFromOverlay()) }
                "stopResidency" -> Bundle().apply { putBoolean("result", runtime.stopAppTakeoverResidency(values.getString("origin") ?: "user")) }
                "refreshResidency" -> { runtime.refreshAppTakeoverResidency(); Bundle() }
                "cancelTurn" -> Bundle().apply { putBoolean("result", runtime.cancelTurn(values.getString("turnId")!!)) }
                "clearConversation" -> { runtime.clearConversation(); Bundle() }
                "switchAgent" -> { runtime.switchAgent(values.getString("name")!!); Bundle() }
                "resumeSkillDraft" -> { runtime.resumeSkillDraftWork(); Bundle() }
                "cancelSkillDraft" -> { runtime.cancelSkillDraftWork(); Bundle() }
                "removeQueued" -> Bundle().apply { putBoolean("result", entry.tasks().deleteQueued(values.getString("conversationId")!!, values.getString("turnId")!!)) }
                "steerQueued" -> Bundle().apply { putBoolean("result", runBlocking { entry.tasks().steerQueued(values.getString("conversationId")!!, values.getString("turnId")!!) }) }
                "steer" -> Bundle().apply { putBoolean("result", runBlocking {
                    runtime.steerConversationInput(values.getString("conversationId")!!,
                        values.getString("turnId")!!, Json.decodeFromString(values.getString("request")!!))
                }) }
                else -> error("Unsupported runtime command: $operation")
            }
            RuntimeWire.write(application.cacheDir, result)
        }

        private fun automationAdmin(values: Bundle): Bundle {
            val manager = entry.automations()
            val id = values.getString("id").orEmpty()
            val now = values.getLong("now")
            return Bundle().apply {
                when (values.getString("operation")) {
                    "create" -> putString("result", Json.encodeToString(manager.create(values.getString("title")!!,
                        values.getString("instruction")!!, values.getBoolean("generated"),
                        Json.decodeFromString(values.getString("schedule")!!), now, id, values.getString("original").orEmpty())))
                    "update" -> putString("result", Json.encodeToString(manager.update(id, values.getString("title")!!,
                        values.getString("instruction")!!, Json.decodeFromString(values.getString("schedule")!!), now, values.getString("original"))))
                    "enable" -> manager.setEnabled(id, values.getBoolean("enabled"), now)
                    "delete" -> manager.delete(id)
                    "run" -> putString("result", Json.encodeToString(manager.runNow(id, now)))
                    "retry" -> manager.retryDelivery(id, now)
                    "alarm" -> putBoolean("result", manager.onAlarm(id, values.getInt("version"), values.getLong("scheduled"), now))
                    "dispatch" -> putInt("result", manager.dispatchDue(now))
                    "restore" -> putInt("result", manager.restoreSchedules(now))
                    else -> error("Unsupported scheduler operation")
                }
            }
        }

        private fun managedFile(args: Bundle): Bundle {
            val files = entry.files()
            val path = args.getString("path").orEmpty()
            return Bundle().apply {
                when (args.getString("operation")) {
                    "read" -> putString("text", files.readFile(path))
                    "frontMatter" -> putString("text", files.readFrontMatter(path))
                    "readBytes" -> putByteArray("bytes", files.readBytes(path))
                    "write" -> { files.writeFile(path, args.getString("text")!!); entry.rules().invalidate(path) }
                    "writeBytes" -> files.writeBytes(path, args.getByteArray("bytes")!!)
                    "append" -> files.appendFile(path, args.getString("text")!!)
                    "exists" -> putBoolean("result", files.exists(path))
                    "modified" -> putLong("time", files.lastModified(path))
                    "list" -> putStringArrayList("names", ArrayList(files.listFiles(path)))
                    "delete" -> putBoolean("result", files.deleteFile(path))
                    "deleteDirectory" -> putBoolean("result", files.deleteDirectory(path))
                    "replaceDirectory" -> {
                        val values = args.getBundle("files")!!
                        files.replaceDirectory(path, values.keySet().associateWith { values.getString(it)!! })
                    }
                    else -> error("Unsupported managed file operation")
                }
            }
        }

        private fun submit(operation: String, values: Bundle): Bundle {
            val id = requireNotNull(values.getString("id"))
            if (tasks.containsKey(id)) return Bundle().apply { putString("id", id) }
            val now = android.os.SystemClock.elapsedRealtime()
            tasks.entries.filter { it.value.finishedAt > 0L && now - it.value.finishedAt > transport.completedRetentionMs }
                .forEach { tasks.remove(it.key, it.value) }
            val retained = tasks.entries.filter { it.value.finishedAt > 0L }.sortedBy { it.value.finishedAt }
            retained.take((retained.size - transport.maxRetainedTasks + 1).coerceAtLeast(0))
                .forEach { tasks.remove(it.key, it.value) }
            check(tasks.values.count { it.finishedAt == 0L } < transport.maxPendingTasks) { "Resident task queue is full" }
            if (operation == "conversation") check(runBlocking { entry.events().eventsForTurn("turn:$id").isEmpty() }) {
                "This Turn already owns durable execution facts; inspect its existing result"
            }
            val task = Task()
            if (tasks.putIfAbsent(id, task) != null) return Bundle().apply { putString("id", id) }
            if (operation == "conversation") {
                entry.tasks().submit(values.getString("conversationId")!!,
                    Json.decodeFromString<TurnRequest>(values.getString("request")!!),
                    onStream = task::append,
                    onCompleted = { task.started = true; task.result = Json.encodeToString(it) },
                    onFailure = { task.error = if (it is CancellationException) "Task canceled" else "Resident task failed: ${it.javaClass.simpleName}" },
                    onFinished = {
                        if (task.result == null && task.error == null) task.error = "Task canceled"
                        task.finishedAt = android.os.SystemClock.elapsedRealtime()
                    })
                scope.launch {
                    entry.tasks().activeTasks.collect { queued ->
                        if (queued.values.flatten().any { it.turnId == "turn:$id" && it.started }) {
                            task.started = true
                            cancel()
                        }
                        if (task.result != null || task.error != null) cancel()
                    }
                }
                return Bundle().apply { putString("id", id) }
            }
            scope.launch {
                val job = currentCoroutineContext()[Job]!!
                runtime.registerActiveJob("turn:$id", job)
                val stream: suspend (AssistantStreamChunk) -> Unit = task::append
                try {
                    task.result = when (operation) {
                        "automation" -> Json.encodeToString(runtime.processAutomationInput(
                            values.getString("text")!!, values.getString("conversationId")!!,
                            prepareDevice = { entry.preparation().guardSession("scheduled") },
                            onTurnStarted = { task.started = true }, onStreamContent = stream))
                        else -> Json.encodeToString(runtime.processAppTakeoverInput(
                            values.getString("text")!!, values.getString("conversationId")!!,
                            executionStrategy(), values.getByteArray("frame")!!,
                            { values.getString("roundContext").orEmpty() }, values.getString("runId")!!,
                            values.getString("modelPlan")?.let { Json.decodeFromString(it) }, stream))
                    }
                } catch (cancel: CancellationException) { task.error = "Task canceled" }
                catch (error: Exception) {
                    android.util.Log.e("DroidanceRuntime", "Task failed: ${error.javaClass.simpleName}")
                    task.error = "Resident task failed: ${error.javaClass.simpleName}"
                } finally {
                    task.finishedAt = android.os.SystemClock.elapsedRealtime()
                    runtime.unregisterActiveJob("turn:$id", job)
                }
            }
            return Bundle().apply { putString("id", id) }
        }

        private fun executionStrategy(): com.droidance.core.agent.device.DeviceExecutionStrategy =
            EntryPointAccessors.fromApplication(application, RuntimeConfigurationEntryPoint::class.java)
                .capabilities().currentStrategy()

        private fun poll(values: Bundle): Bundle {
            val task = tasks[values.getString("id")] ?: error("Unknown resident task")
            val offset = values.getInt("offset")
            require(offset >= 0)
            return task.progress(offset).apply {
                putBoolean("started", task.started)
                putString("error", task.error)
            }
        }

        override fun state(): ParcelFileDescriptor = authenticated { RuntimeWire.write(application.cacheDir, snapshot()) }

        private fun snapshot(): Bundle = Bundle().apply {
            putString("queuedTasks", Json.encodeToString(entry.tasks().activeTasks.value.mapValues { (_, tasks) ->
                tasks.map { it.copy(request = it.request.copy(images = emptyList(), modelPlan = null)) }
            }))
            entry.database().openHelper.writableDatabase.query("SELECT COUNT(*), COALESCE(MAX(sequence), 0) FROM agent_events").use {
                check(it.moveToFirst())
                putString("historyRevision", "${it.getLong(0)}:${it.getLong(1)}")
            }
            putString("usage", Json.encodeToString(entry.usage().records.value))
            putString("turnUsage", Json.encodeToString(entry.usage().turnRecords.value))
            putString("automationDefinitions", Json.encodeToString(entry.automationStore().definitions.value))
            putString("automationOccurrences", Json.encodeToString(entry.automationStore().occurrences.value))
            putString("takeovers", Json.encodeToString(entry.takeovers().list()))
            putInt("ownerPid", android.os.Process.myPid())
            putInt("ownerUid", android.os.Process.myUid())
            entry.permissions().pendingRequest.value?.let { request -> putBundle("permission", Bundle().apply {
                putString("id", request.requestId); putString("turn", request.turnId); putString("call", request.callId)
                putStringArrayList("permissions", ArrayList(request.permissions)); putString("reason", request.reason)
                putLong("at", request.requestedAtMs); putBoolean("launched", request.launched)
                putBoolean("settings", request.settingsRequired)
            }) }
            putString("permissionPrompt", entry.permissions().systemDialogPromptRequested.value)
            runtime.userInteractionRequest.value?.let { request -> putBundle("interaction", Bundle().apply {
                putString("id", request.requestId); putString("conversation", request.conversationId)
                putString("kind", request.kind.name); putString("reason", request.reason); putLong("at", request.requestedAtMs)
                putString("state", request.state.name); putString("source", request.responseSource?.name)
                request.resolvedAtMs?.let { putLong("resolved", it) }
            }) }
            entry.virtualSpace().activeDisplayMetrics()?.let { metrics -> putBundle("display", Bundle().apply {
                entry.virtualSpace().retainedExecution.value?.let { execution ->
                    putString("thread", execution.threadId); putString("execution", execution.executionId)
                }
                putInt("id", metrics.displayId); putInt("width", metrics.width)
                putInt("height", metrics.height); putInt("density", metrics.densityDpi)
            }) }
            entry.surfaces().virtualSpaceHandCue.value?.let { cue -> putBundle("handCue", Bundle().apply {
                putString("id", cue.actionId); putInt("display", cue.displayId)
                putLong("started", cue.startedAtMs); putLong("finished", cue.finishedAtMs)
                putInt("fromX", cue.fromX); putInt("fromY", cue.fromY); putLong("travel", cue.travelDurationMs)
                when (val action = cue.action) {
                    is com.droidance.core.device.tools.InputAction.Tap -> { putInt("x", action.x); putInt("y", action.y) }
                    is com.droidance.core.device.tools.InputAction.Swipe -> {
                        putInt("x", action.startX); putInt("y", action.startY)
                        putInt("endX", action.endX); putInt("endY", action.endY); putInt("duration", action.durationMs)
                    }
                }
            }) }
            putString("state", runtime.state.value.name)
            putString("agentLoopState", runtime.agentLoopState.value.name)
            putString("currentConversationId", runtime.currentConversationId.value)
            putString("currentTaskExecutionSpace", runtime.currentTaskExecutionSpace.value?.name)
            putString("lastResponse", runtime.lastResponse.value)
            putString("currentAction", runtime.currentAction.value)
            putString("currentTaskTitle", runtime.currentTaskTitle.value)
            putBoolean("isMobileUseTaskActive", runtime.isMobileUseTaskActive.value)
            putString("takeoverResidentStatus", runtime.takeoverResidentStatus.value)
            putBoolean("appTakeoverTurnActive", runtime.appTakeoverTurnActive.value)
            putString("appTakeoverConversationId", runtime.appTakeoverConversationId.value)
            putString("appTakeoverLiveOutput", runtime.appTakeoverLiveOutput.value)
            putString("streamingOutput", runtime.streamingOutput.value)
            putString("currentActionDetail", runtime.currentActionDetail.value)
            putBoolean("isPaused", runtime.isPaused.value)
            putString("manualTakeoverState", runtime.manualTakeoverState.value.name)
            putBoolean("manualTakeoverAvailable", runtime.manualTakeoverAvailable.value)
            putString("activeAgent", runtime.getActiveAgent())
            runtime.taskTiming.value?.let { timing -> putBundle("timing", Bundle().apply {
                putString("turnId", timing.turnId); putLong("accumulatedMs", timing.clock.accumulatedMs)
                timing.clock.runningSinceMs?.let { putLong("runningSinceMs", it) }
            }) }
            val work = runtime.skillDraftWorkState.value
            putInt("skillPending", work.pending); putBoolean("skillRunning", work.running); putInt("skillFailures", work.failures)
        }

        override fun files(): ParcelFileDescriptor = authenticated { RuntimeStorageSnapshot.managedFiles(application) }

        override fun blob(operation: String, contentRef: String, maxBytes: Long,
            input: ParcelFileDescriptor?): ParcelFileDescriptor = authenticated {
            val store = entry.blobs()
            if (operation == "read") {
                val file = File.createTempFile("runtime-blob-", ".bin", application.cacheDir)
                try {
                    val size = file.outputStream().use { store.copyTo(contentRef, it) }
                    if (size > maxBytes) throw com.droidance.core.agent.blob.BlobReadLimitExceededException(contentRef, size, maxBytes)
                    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                } finally { file.delete() }
            } else RuntimeWire.write(application.cacheDir, Bundle().apply {
                when (operation) {
                    "put" -> {
                        val bytes = ParcelFileDescriptor.AutoCloseInputStream(requireNotNull(input)).use { stream ->
                            val output = java.io.ByteArrayOutputStream()
                            val buffer = ByteArray(32 * 1024)
                            while (true) {
                                val count = stream.read(buffer)
                                if (count < 0) break
                                check(output.size() + count <= 64 * 1024 * 1024) { "Artifact exceeds the transfer limit" }
                                output.write(buffer, 0, count)
                            }
                            output.toByteArray()
                        }
                        val stored = store.put(bytes)
                        putString("ref", stored.contentRef); putString("hash", stored.contentHash); putLong("size", stored.sizeBytes)
                    }
                    "delete" -> putBoolean("deleted", store.delete(contentRef))
                    "list" -> putStringArrayList("refs", ArrayList(store.listAllRefs()))
                    else -> error("Unsupported artifact operation: $operation")
                }
            })
        }
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface RuntimeConfigurationEntryPoint {
    fun capabilities(): com.droidance.agent.RuntimeCapabilityRegistry
}
