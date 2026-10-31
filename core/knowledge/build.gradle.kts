// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    `java-library`
}
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
sourceSets.test { resources.srcDir(rootProject.file("skills")) }
dependencies {
    implementation(project(":core:agent"))
    implementation(libs.snakeyaml.engine)
    implementation(libs.javax.inject)
    testImplementation(libs.junit)
    testImplementation(testFixtures(project(":core:agent")))
}
