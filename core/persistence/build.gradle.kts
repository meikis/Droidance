// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

plugins {
    id("droidance.android.library")
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}
android {
    namespace = "com.droidance.core.persistence"
    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
    }
}
dependencies {
    implementation(project(":core:agent"))
    implementation(project(":core:knowledge"))
    implementation(libs.javax.inject)
    api(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
}
