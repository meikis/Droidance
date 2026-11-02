// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.air

import androidx.annotation.StringRes
import com.droidance.core.device.R

@StringRes
fun AirPairingFailure.messageRes(): Int = when (this) {
    AirPairingFailure.PAIRING_REQUIRED -> R.string.settings_air_pairing_required_detail
    AirPairingFailure.LOCAL_ADB_CONNECTION_FAILED -> R.string.settings_air_local_adb_connection_failed
    AirPairingFailure.LOCAL_PORT_CLOSE_FAILED -> R.string.settings_air_local_port_close_failed
    AirPairingFailure.WIFI_UNAVAILABLE -> R.string.settings_air_wifi_unavailable_detail
    AirPairingFailure.NOTIFICATION_PERMISSION_REQUIRED ->
        R.string.settings_air_notification_permission_required
    AirPairingFailure.APP_NOTIFICATIONS_DISABLED -> R.string.settings_air_notifications_disabled
    AirPairingFailure.NOTIFICATION_CHANNEL_DISABLED ->
        R.string.settings_air_notification_channel_disabled
    AirPairingFailure.NOTIFICATION_POST_FAILED -> R.string.settings_air_notification_post_failed
    AirPairingFailure.PAIRING_SERVICE_NOT_FOUND -> R.string.settings_air_pairing_service_not_found
    AirPairingFailure.INVALID_PAIRING_CODE -> R.string.settings_air_notification_invalid_code_text
    AirPairingFailure.PAIRING_REQUEST_UNAVAILABLE ->
        R.string.settings_air_notification_unavailable_text
    AirPairingFailure.PAIRING_TIMEOUT -> R.string.settings_air_pairing_timeout
    AirPairingFailure.PAIRING_REJECTED -> R.string.settings_air_pairing_rejected
    AirPairingFailure.PAIRING_CONNECTION_FAILED -> R.string.settings_air_pairing_connection_failed
    AirPairingFailure.ADB_HEALTH_CHECK_FAILED -> R.string.settings_air_adb_health_check_failed
    AirPairingFailure.ADB_HEALTH_RESPONSE_MISSING ->
        R.string.settings_air_adb_health_response_missing
    AirPairingFailure.ADB_IDENTITY_CHECK_FAILED -> R.string.settings_air_adb_identity_check_failed
    AirPairingFailure.ADB_IDENTITY_RESPONSE_MISSING ->
        R.string.settings_air_adb_identity_response_missing
    AirPairingFailure.ADB_CONNECTION_FAILED -> R.string.settings_air_adb_connection_failed
    AirPairingFailure.ADB_ENDPOINT_NOT_FOUND -> R.string.settings_air_adb_endpoint_not_found
}
