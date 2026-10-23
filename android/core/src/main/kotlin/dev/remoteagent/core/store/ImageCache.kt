package dev.remoteagent.core.store

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files

/**
 * Images by id, in memory and on disk. An id names the image's content, so a
 * cached copy never goes stale, and servers can share entries.
 * Mirrors apple/RAKit/Sources/RAKit/Store/ImageCache.swift.
 */
class ImageCache(private val dir: File?) {
    private val memory = LinkedHashMap<String, ByteArray>(16, 0.75f, true)
    private var memoryBytes = 0L
    private val loading = HashMap<String, Deferred<ByteArray>>()
    private val mutex = Mutex()
    // Loads outlive the request that started them: others may be waiting.
    private val loads = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        dir?.mkdirs()
    }

    /**
     * The image's bytes, from the cache or else from `load`. Concurrent
     * requests for one id share a load.
     */
    suspend fun data(id: String, load: suspend () -> ByteArray): ByteArray {
        remembered(id)?.let { return it }
        file(id)?.let { f -> withContext(Dispatchers.IO) { runCatching { f.takeIf { it.isFile }?.readBytes() }.getOrNull() } }?.let {
            remember(id, it)
            return it
        }
        val task = mutex.withLock {
            // A load that finished while the file was read has kept its bytes.
            remembered(id)?.let { return it }
            loading.getOrPut(id) { loads.async { load() } }
        }
        try {
            val data = task.await()
            store(data, id)
            return data
        } finally {
            mutex.withLock { if (loading[id] === task) loading.remove(id) }
        }
    }

    /**
     * The image as a file named for people ("Image.png") rather than by its
     * hash, for sharing. Loads the image first if needed.
     */
    suspend fun namedFile(id: String, load: suspend () -> ByteArray): File {
        val data = data(id, load)
        val cached = file(id) ?: throw FileNotFoundException("no cache file for $id")
        val named = File(dir, "Named/${cached.nameWithoutExtension}/Image.${cached.extension}")
        withContext(Dispatchers.IO) {
            if (!named.exists()) {
                named.parentFile?.mkdirs()
                // A link to the cached file takes no space; it may be missing, though.
                try {
                    Files.createLink(named.toPath(), cached.toPath())
                } catch (_: Exception) {
                    write(named, data)
                }
            }
        }
        return named
    }

    /** Keeps bytes this device already has, e.g. an image it uploaded. */
    suspend fun store(data: ByteArray, id: String) {
        remember(id, data)
        file(id)?.let { f -> withContext(Dispatchers.IO) { runCatching { write(f, data) } } }
    }

    /**
     * The cache file for an id the server made ("<sha256>.<ext>"); null for
     * anything else, so no id can name a path outside the directory.
     */
    private fun file(id: String): File? {
        val dir = dir ?: return null
        return if (idPattern.matches(id)) File(dir, id) else null
    }

    private fun write(f: File, data: ByteArray) {
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, ".${f.name}.tmp")
        tmp.writeBytes(data)
        if (!tmp.renameTo(f)) {
            f.writeBytes(data)
            tmp.delete()
        }
    }

    private fun remembered(id: String): ByteArray? = synchronized(memory) { memory[id] }

    private fun remember(id: String, data: ByteArray) = synchronized(memory) {
        memory.put(id, data)?.let { memoryBytes -= it.size }
        memoryBytes += data.size
        val it = memory.entries.iterator()
        while (memoryBytes > MEMORY_LIMIT && it.hasNext()) {
            val e = it.next()
            if (e.key == id) continue
            memoryBytes -= e.value.size
            it.remove()
        }
    }

    private companion object {
        const val MEMORY_LIMIT = 64L shl 20
        val idPattern = Regex("[0-9a-f]{64}\\.[a-z]{3,4}")
    }
}
