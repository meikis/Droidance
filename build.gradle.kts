// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.hilt.android) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}

// Review this graph when adding a module; dependencies are inspected from Gradle's model.
val allowedModuleDependencies = mapOf(
    ":app" to setOf(":core:agent", ":core:model", ":core:device", ":core:knowledge", ":core:persistence"),
    ":core:agent" to setOf(":core:model"),
    ":core:model" to emptySet(),
    ":core:knowledge" to setOf(":core:agent"),
    ":core:device" to setOf(":core:agent"),
    ":core:persistence" to setOf(":core:agent", ":core:knowledge")
)
tasks.register("check") { dependsOn("verifyArchitecture") }
subprojects { tasks.matching { it.name == "check" }.configureEach { dependsOn(rootProject.tasks.named("verifyArchitecture")) } }

val verifyArchitecture = tasks.register("verifyArchitecture") {
    group = "verification"
    description = "Enforce module dependency directions and pure JVM boundaries"
    doLast {
        val modules = subprojects.filter { it.buildFile.isFile }
        check(modules.map { it.path }.toSet() == allowedModuleDependencies.keys) {
            "Module inventory changed; update the reviewed architecture graph"
        }
        val packageOwners = mutableMapOf<String, String>()
        modules.forEach { module ->
            module.fileTree("src/main").matching { include("**/*.kt", "**/*.java", "**/*.aidl") }.forEach { source ->
                val packageName = Regex("(?m)^\\s*package\\s+([A-Za-z0-9_.]+)")
                    .find(source.readText())?.groupValues?.get(1) ?: return@forEach
                val previous = packageOwners.putIfAbsent(packageName, module.path)
                check(previous == null || previous == module.path) {
                    "Package $packageName is shared by $previous and ${module.path}"
                }
            }
        }
        modules.forEach { module ->
            val dependencies = module.configurations.flatMap { it.dependencies }
            val actual = dependencies.filterIsInstance<org.gradle.api.artifacts.ProjectDependency>()
                .map { it.path }
                // AGP test configurations reference their own tested project; this is not a module edge.
                .filter { it != module.path }.toSet()
            check(actual == allowedModuleDependencies.getValue(module.path)) {
                "${module.path} dependencies $actual differ from the reviewed graph"
            }
            if (module.path in setOf(":core:agent", ":core:model", ":core:knowledge")) {
                check(module.plugins.hasPlugin("org.jetbrains.kotlin.jvm")) { "${module.path} must remain JVM-only" }
                check(dependencies.none { it.group?.let { group -> group.startsWith("androidx.") || group.startsWith("com.android.") } == true }) {
                    "Android dependency in ${module.path}"
                }
                module.fileTree("src/main").matching { include("**/*.kt", "**/*.java") }.forEach { source ->
                    check(!Regex("(?m)^\\s*import\\s+(?:android|androidx)\\.").containsMatchIn(source.readText())) {
                        "Android import in ${source.relativeTo(rootDir)}"
                    }
                }
            }
        }
    }
}
