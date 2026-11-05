// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.agent.device

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.util.Log

/** The broadcast contains only framework types, so its payload survives app obfuscation. */
object SidecarBinderHandoff {
    private const val BINDER = "sidecar_binder"
    private const val UID = "sidecar_uid"
    private const val PROTOCOL = "sidecar_protocol_version"
    private const val SOURCE = "sidecar_source"
    private const val UPDATED_AT = "sidecar_package_updated_at"
    private const val SHELL_UID = 2000

    @JvmStatic
    fun put(intent: Intent, binder: IBinder, uid: Int, protocol: Int, source: String, updatedAt: Long) {
        intent.putExtras(Bundle().apply {
            putBinder(BINDER, binder)
            putInt(UID, uid)
            putInt(PROTOCOL, protocol)
            putString(SOURCE, source)
            putLong(UPDATED_AT, updatedAt)
        })
    }

    /** Old broadcasts can fail while reading any extra, before a version can be checked. */
    @JvmStatic
    fun receive(context: Context, intent: Intent): IBinder? {
        return try {
            if (intent.action != SidecarBinderReceiver.ACTION_SIDECAR_BINDER_READY) return null
            val extras = intent.extras ?: return null
            if (extras.getInt(UID, -1) != SHELL_UID ||
                extras.getInt(PROTOCOL, -1) != VirtualSpaceSidecarService.SIDECAR_PROTOCOL_VERSION ||
                extras.getString(SOURCE) != context.applicationInfo.sourceDir ||
                extras.getLong(UPDATED_AT, -1L) != context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
            ) return null
            extras.getBinder(BINDER)?.takeIf { it.isBinderAlive }
        } catch (error: RuntimeException) {
            Log.w("DroidanceVSpace", "Ignored invalid sidecar broadcast: ${error.javaClass.simpleName}")
            null
        } catch (error: LinkageError) {
            Log.w("DroidanceVSpace", "Ignored incompatible sidecar broadcast: ${error.javaClass.simpleName}")
            null
        } catch (error: android.content.pm.PackageManager.NameNotFoundException) {
            Log.w("DroidanceVSpace", "Ignored sidecar broadcast: installed package unavailable")
            null
        }
    }
}
