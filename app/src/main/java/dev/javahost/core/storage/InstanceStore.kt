package dev.javahost.core.storage

import dev.javahost.core.model.ServerInstance
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Small, atomic JSON-backed store. Instances are few (tens at most), so a
 * single file rewritten on each change is simpler and more debuggable than a
 * database, and keeps the instance list observable for Compose.
 */
class InstanceStore(private val paths: Paths) {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }
    private val serializer = ListSerializer(ServerInstance.serializer())
    private val file: File = File(paths.root, "instances.json")
    private val lock = Any()

    private val _instances = MutableStateFlow<List<ServerInstance>>(emptyList())
    val instances: StateFlow<List<ServerInstance>> = _instances.asStateFlow()

    init {
        reload()
    }

    fun reload() {
        synchronized(lock) {
            val loaded = runCatching {
                if (!file.exists()) emptyList()
                else json.decodeFromString(serializer, file.readText())
            }.getOrDefault(emptyList())
            _instances.value = loaded.sortedBy { it.createdAt }
        }
    }

    fun get(id: String): ServerInstance? = _instances.value.firstOrNull { it.id == id }

    fun upsert(instance: ServerInstance) {
        synchronized(lock) {
            val current = _instances.value.toMutableList()
            val index = current.indexOfFirst { it.id == instance.id }
            if (index >= 0) current[index] = instance else current.add(instance)
            persist(current)
        }
    }

    fun update(id: String, transform: (ServerInstance) -> ServerInstance): ServerInstance? {
        synchronized(lock) {
            val current = _instances.value.toMutableList()
            val index = current.indexOfFirst { it.id == id }
            if (index < 0) return null
            val updated = transform(current[index])
            current[index] = updated
            persist(current)
            return updated
        }
    }

    /** Removes metadata and (by default) the whole working directory. */
    fun delete(id: String, deleteFiles: Boolean = true) {
        synchronized(lock) {
            val current = _instances.value.filterNot { it.id == id }
            persist(current)
            if (deleteFiles) paths.deleteInstanceFiles(id)
        }
    }

    fun create(instance: ServerInstance): File {
        val dir = paths.workingDir(instance.id)
        persist((_instances.value + instance).sortedBy { it.createdAt })
        return dir
    }

    private fun persist(next: List<ServerInstance>) {
        _instances.value = next.sortedBy { it.createdAt }
        runCatching {
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(json.encodeToString(serializer, _instances.value))
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText())
                tmp.delete()
            }
        }
    }
}
