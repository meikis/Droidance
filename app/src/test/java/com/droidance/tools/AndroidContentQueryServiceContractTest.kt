// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.tools

import com.droidance.core.agent.tools.*
import com.droidance.core.knowledge.tools.*
import com.droidance.core.device.tools.*

import com.droidance.tools.*

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidContentQueryServiceContractTest {
    @Test
    fun providerReadsUseContentResolverWithoutShellOrBusinessRules() {
        val source = projectFile(
            "core/device/src/main/java/com/droidance/core/device/tools/AndroidContentQueryService.kt"
        ).readText()

        assertTrue(source.contains("class AndroidContentQueryService("))
        assertTrue(source.contains("suspend fun query(arguments: String)"))
        assertTrue(source.contains("context.contentResolver.query("))
        assertTrue(source.contains("[ANDROID_CONTENT_RESULT]"))
        assertTrue(source.contains("ToolProtocolResult.failed(\"PERMISSION_MISSING\""))
        assertTrue(source.contains("ToolProtocolResult.invalidArguments()"))
        assertFalse(source.contains("android_read(op=\"content\") accepts only"))
        assertFalse(source.contains("run_shell"))
        assertFalse(source.contains("RootShell"))
        assertFalse(source.contains("com.tencent"))
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
