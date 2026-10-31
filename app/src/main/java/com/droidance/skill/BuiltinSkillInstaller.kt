package com.droidance.skill

import com.droidance.core.knowledge.skill.*

import com.droidance.skill.*

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Seeds built-in read-only packages from the APK without replacing user-authored packages. */
@Singleton
class BuiltinSkillInstaller @Inject constructor(
    @ApplicationContext private val context: Context,
    private val engine: SkillEngine
) {
    fun install() {
        context.assets.list("skills").orEmpty().forEach { id ->
            val existing = engine.loadSkill(id)
            if (existing != null && !existing.isReadOnly) return@forEach
            val files = linkedMapOf<String, String>()
            fun read(path: String) {
                val children = context.assets.list("skills/$id/$path").orEmpty()
                if (children.isEmpty()) files[path] = context.assets.open("skills/$id/$path").bufferedReader().use { it.readText() }
                else children.forEach { read(if (path.isEmpty()) it else "$path/$it") }
            }
            read("")
            engine.installBuiltinPackage(files)
        }
    }
}
