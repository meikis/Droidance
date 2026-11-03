// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0
package com.droidance.core.device.agent.device

import android.os.Binder
import android.os.IBinder

/** Shell processes acquire providers externally because they have no AMS application record. */
object RuntimeContentProviders {
    private val token = Binder()
    @Synchronized
    fun acquire(authority: String) {
        if (!RuntimeProcessRole.isOwner) return
        val threadClass = Class.forName("android.app.ActivityThread")
        val thread = threadClass.getMethod("currentActivityThread").invoke(null)
        @Suppress("UNCHECKED_CAST")
        val providers = threadClass.getDeclaredField("mProviderMap").apply { isAccessible = true }
            .get(thread) as MutableMap<Any, Any>
        val keyClass = Class.forName("android.app.ActivityThread\$ProviderKey")
        val key = keyClass.getDeclaredConstructor(String::class.java, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.newInstance(authority, 0)
        synchronized(providers) { if (providers.containsKey(key)) return }
        val manager = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null)
        val holder = Class.forName("android.app.IActivityManager").getMethod("getContentProviderExternal",
            String::class.java, Int::class.javaPrimitiveType, IBinder::class.java, String::class.java)
            .invoke(manager, authority, 0, token, "droidance-runtime") ?: error("Content provider unavailable")
        val provider = holder.javaClass.getField("provider").get(holder)
        val recordClass = Class.forName("android.app.ActivityThread\$ProviderClientRecord")
        val record = recordClass.declaredConstructors.single().apply { isAccessible = true }
            .newInstance(arrayOf(authority), provider, null, holder)
        synchronized(providers) { providers[key] = record }
        val binder = Class.forName("android.content.IContentProvider").getMethod("asBinder").invoke(provider) as IBinder
        binder.linkToDeath({ synchronized(providers) { providers.remove(key, record) } }, 0)
    }
}
