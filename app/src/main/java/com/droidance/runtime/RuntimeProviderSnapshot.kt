// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0
package com.droidance.runtime

import android.content.Context
import kotlinx.serialization.Serializable

/** Provider credentials travel with the bootstrap descriptor and remain in memory in the owner. */
@Serializable
internal data class RuntimeProviderSnapshot(val configs: String?, val schemaVersion: Int) {
    companion object {
        fun capture(context: Context): RuntimeProviderSnapshot {
            val prefs = context.getSharedPreferences("droidance_providers", Context.MODE_PRIVATE)
            return RuntimeProviderSnapshot(
                configs = prefs.getString("provider_configs", null),
                schemaVersion = prefs.getInt("provider_config_schema_version", 0)
            )
        }
    }
}

/** Set before the owner graph is created; the provider manager consumes this once. */
internal object RuntimeProviderBootstrap {
    private var snapshot: RuntimeProviderSnapshot? = null

    @Synchronized fun install(value: RuntimeProviderSnapshot) { snapshot = value }
    @Synchronized fun take(): RuntimeProviderSnapshot? = snapshot.also { snapshot = null }
}
