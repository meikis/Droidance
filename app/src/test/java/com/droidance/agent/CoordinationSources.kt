package com.droidance.agent

import java.io.File

/** The source contracts cover the complete coordination boundary, regardless of file layout. */
fun coordinationSources(): String {
    var root = File(System.getProperty("user.dir")).absoluteFile
    while (!File(root, "settings.gradle.kts").isFile) root = checkNotNull(root.parentFile)
    val directory = File(root, "core/agent/src/main/kotlin/com/droidance/core/agent")
    return listOf("TaskCoordinator.kt", "TaskTurnOutcomes.kt", "TaskTurnSession.kt",
        "TaskModelRound.kt", "TaskToolRound.kt", "TaskTurnRecovery.kt", "TaskContextStage.kt", "TaskCompletionStage.kt", "TaskTurnServices.kt", "TurnHost.kt")
        .joinToString("\n") { File(directory, it).readText() }
}

fun executorSources(): String {
    var root = File(System.getProperty("user.dir")).absoluteFile
    while (!File(root, "settings.gradle.kts").isFile) root = checkNotNull(root.parentFile)
    return listOf("ToolExecutor.kt", "KnowledgeToolHandler.kt", "WorkspaceToolHandler.kt")
        .joinToString("\n") { File(root, "app/src/main/java/com/droidance/tools/$it").readText() }
}
