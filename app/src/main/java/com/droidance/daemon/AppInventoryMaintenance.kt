// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.daemon

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.work.WorkManager
import com.droidance.core.device.config.InstalledAppsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Package events maintain the cache; a lookup miss repairs events missed while stopped. */
fun startAppInventoryMaintenance(context: Context, apps: InstalledAppsManager, scope: CoroutineScope) {
    // Retire already-enqueued work as well as removing future periodic scheduling.
    WorkManager.getInstance(context).cancelUniqueWork("droidance_app_inventory")
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val packageName = intent.data?.schemeSpecificPart ?: return
            val removed = intent.action == Intent.ACTION_PACKAGE_REMOVED
            // Replacement sends remove/add and then REPLACED; collect the final state once.
            if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false) &&
                intent.action != Intent.ACTION_PACKAGE_REPLACED) return
            val pending = goAsync()
            scope.launch {
                try { apps.onPackageChanged(packageName, removed) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { apps.invalidate() }
                finally { pending.finish() }
            }
        }
    }
    val filter = IntentFilter().apply {
        addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED)
        addAction(Intent.ACTION_PACKAGE_REPLACED); addAction(Intent.ACTION_PACKAGE_CHANGED)
        addDataScheme("package")
    }
    ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
}
