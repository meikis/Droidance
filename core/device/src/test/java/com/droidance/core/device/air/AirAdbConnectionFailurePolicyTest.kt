// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.air

import java.io.IOException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AirAdbConnectionFailurePolicyTest {
    @Test
    fun explicitAdbAuthorizationRejectionRequiresPairing() {
        assertTrue(
            AirAdbConnectionFailurePolicy.requiresPairing(
                AirAdbAuthorizationException("ADB key rejected")
            )
        )
        assertTrue(
            AirAdbConnectionFailurePolicy.requiresPairing(
                IOException("connect failed", AirAdbAuthorizationException("ADB key rejected"))
            )
        )
    }

    @Test
    fun transportFailuresRemainReconnectable() {
        assertFalse(AirAdbConnectionFailurePolicy.requiresPairing(SocketTimeoutException("timed out")))
        assertFalse(AirAdbConnectionFailurePolicy.requiresPairing(IOException("connection reset")))
    }

    @Test
    fun onlyExplicitTlsPeerAuthorizationAlertsAreClassifiedAsTrustLoss() {
        assertTrue(
            AirAdbConnectionFailurePolicy.isExplicitTlsAuthorizationRejection(
                SSLHandshakeException("Received fatal alert: bad_certificate")
            )
        )
        assertFalse(
            AirAdbConnectionFailurePolicy.isExplicitTlsAuthorizationRejection(
                SSLHandshakeException("Remote host terminated the handshake")
            )
        )
    }
}
