// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.air

import java.io.IOException
import javax.net.ssl.SSLHandshakeException

class AirAdbAuthorizationException(
    message: String,
    cause: Throwable? = null
) : IOException(message, cause)

object AirAdbConnectionFailurePolicy {
    private val tlsAuthorizationAlerts = listOf(
        "bad_certificate",
        "certificate_unknown",
        "certificate_required",
        "unknown_ca",
        "access_denied"
    )

    fun requiresPairing(error: Throwable): Boolean =
        generateSequence(error as Throwable?) { it.cause }
            .any { it is AirAdbAuthorizationException }

    fun isExplicitTlsAuthorizationRejection(error: SSLHandshakeException): Boolean {
        val detail = error.message.orEmpty().lowercase()
        return detail.contains("fatal alert") && tlsAuthorizationAlerts.any(detail::contains)
    }
}
