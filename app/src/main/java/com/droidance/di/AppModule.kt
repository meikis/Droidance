// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.di

import com.droidance.core.agent.di.*

import com.droidance.di.*

import android.content.Context
import androidx.room.Room
import com.droidance.core.agent.blob.BlobStore
import com.droidance.agent.blob.FileBlobStore
import com.droidance.agent.attempt.AndroidExecutionAttemptBackendProbe
import com.droidance.core.agent.attempt.ExecutionAttemptBackendProbe
import com.droidance.agent.device.AndroidDeviceBackend
import com.droidance.core.device.agent.device.DeviceBackend
import com.droidance.core.persistence.agent.event.AgentEventDatabase
import com.droidance.core.agent.event.AgentEventStore
import com.droidance.core.persistence.agent.event.RoomAgentEventStore
import com.droidance.core.agent.di.ApplicationScope
import com.droidance.core.persistence.memory.MemoryCandidateDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * Hilt 依赖注入模块
 * 提供全局单例依赖
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    fun provideObservationSamplingPolicy(rules: com.droidance.config.RuleConfigManager): com.droidance.core.device.tools.ObservationSamplingPolicy =
        object : com.droidance.core.device.tools.ObservationSamplingPolicy {
            override fun sampleSide() = rules.harnessPolicy().runtimeThresholds.preActionObservationSampleSide
        }

    @Provides
    fun provideScriptExecutionPort(executor: com.droidance.tools.ToolExecutor): com.droidance.core.agent.tools.ScriptExecutionPort = executor

    @Provides
    fun provideAgentConfiguration(configuration: com.droidance.config.AndroidAgentConfiguration): com.droidance.core.agent.ports.AgentConfiguration = configuration

    @Provides
    fun provideTaskRuntimeInfo(): com.droidance.core.agent.ports.TaskRuntimeInfo =
        com.droidance.core.agent.ports.TaskRuntimeInfo(com.droidance.BuildConfig.VERSION_NAME, android.os.SystemClock::elapsedRealtime)

    @Provides
    fun provideConversationTaskRuntime(runtime: com.droidance.agent.AgentRuntime):
        com.droidance.agent.ConversationTaskRuntime = runtime

    @Provides
    fun provideToolExecutorPort(executor: com.droidance.tools.ToolExecutor): com.droidance.core.agent.ports.ToolExecutorPort = executor

    @Provides
    fun provideRuleSource(manager: com.droidance.config.RuleConfigManager): com.droidance.core.agent.ports.RuleSource = manager

    @Provides
    fun provideDebugLogAppender(manager: com.droidance.debug.DebugLogManager): com.droidance.core.agent.debug.DebugLogAppender = manager

    @Provides
    fun provideVirtualSpaceFrameInspector(policy: com.droidance.agent.device.VirtualSpaceRuntimePolicy): com.droidance.core.device.agent.device.VirtualSpaceFrameInspector = policy

    @Provides
    fun provideRuntimeModeProvider(registry: com.droidance.agent.RuntimeCapabilityRegistry): com.droidance.core.agent.device.RuntimeModeProvider = registry

    @Provides
    fun provideSkillDocumentStorage(
        files: com.droidance.memory.MarkdownFileManager
    ): com.droidance.core.knowledge.skill.SkillDocumentStorage = files

    @Provides
    fun provideSkillScriptValidator(
        validator: com.droidance.skill.RhinoSkillScriptValidator
    ): com.droidance.core.knowledge.skill.SkillScriptValidator = validator

    @Provides
    @Singleton
    fun providePermissionBroker(
        @ApplicationContext context: Context
    ): com.droidance.core.device.permission.PermissionBroker = com.droidance.core.device.permission.PermissionBroker(context) { requestId ->
        context.startActivity(
            android.content.Intent(context, com.droidance.MainActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                .putExtra(com.droidance.core.device.permission.PermissionBroker.EXTRA_PERMISSION_REQUEST_ID, requestId)
        )
    }

    @Provides
    fun provideMemoryDocumentStorage(
        files: com.droidance.memory.MarkdownFileManager
    ): com.droidance.core.knowledge.memory.MemoryDocumentStorage = files

    @Provides
    @Singleton
    fun provideAgentEventDatabase(
        @ApplicationContext context: Context
    ): AgentEventDatabase = Room.databaseBuilder(
        context,
        AgentEventDatabase::class.java,
        "droidance-agent-events.db"
    ).build()

    @Provides
    @Singleton
    fun provideAgentEventStore(database: AgentEventDatabase): AgentEventStore =
        RoomAgentEventStore(database)

    @Provides
    @Singleton
    fun provideMemoryCandidateDatabase(
        @ApplicationContext context: Context
    ): MemoryCandidateDatabase = Room.databaseBuilder(
        context,
        MemoryCandidateDatabase::class.java,
        "droidance-memory-candidates.db"
    ).addMigrations(MemoryCandidateDatabase.MIGRATION_1_2, MemoryCandidateDatabase.MIGRATION_2_3,
        MemoryCandidateDatabase.MIGRATION_3_4).build()

    @Provides
    @Singleton
    fun provideBlobStore(
        @ApplicationContext context: Context
    ): BlobStore = FileBlobStore(context.filesDir.resolve("agent-blobs"))

    @Provides
    @Singleton
    fun provideExecutionAttemptBackendProbe(
        implementation: AndroidExecutionAttemptBackendProbe
    ): ExecutionAttemptBackendProbe = implementation

    @Provides
    @Singleton
    fun provideDeviceBackend(implementation: AndroidDeviceBackend): DeviceBackend = implementation

    @Provides
    @Singleton
    fun provideInterruptedNoticeProvider(
        @ApplicationContext context: Context
    ): com.droidance.core.agent.InterruptedNoticeProvider = com.droidance.core.agent.InterruptedNoticeProvider {
        context.getString(com.droidance.R.string.execution_interrupted_result)
    }

    /** 提供 OkHttpClient 单例 */
    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        // Keep provider credentials and request contents out of logcat.
        return OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)     // LLM 响应可能较慢
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
