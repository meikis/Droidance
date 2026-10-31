// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.tools

import com.droidance.core.agent.tools.*

import com.droidance.core.device.tools.*

import java.security.MessageDigest

/** Cache identity covers every encoded image byte, including changing content in its middle. */
fun screenFrameFingerprint(bytes: ByteArray): String = MessageDigest
    .getInstance("SHA-256").digest(bytes)
    .joinToString("") { "%02x".format(it) }
