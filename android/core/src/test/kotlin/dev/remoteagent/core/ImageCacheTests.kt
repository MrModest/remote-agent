package dev.remoteagent.core

import dev.remoteagent.core.net.RpcError
import dev.remoteagent.core.store.ImageCache
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

/** As apple/RAKit/Tests/RAKitTests/ImageCacheTests.swift. */
class ImageCacheTests {
    private val id = "ab".repeat(32) + ".png"
    private val dir: File = Files.createTempDirectory("ImageCacheTests").toFile()

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun loadsOnceAndKeepsOnDisk() = runBlocking {
        val loads = AtomicInteger()
        val cache = ImageCache(dir)
        val a = async { cache.data(id) { loads.incrementAndGet(); delay(50); byteArrayOf(1, 2, 3) } }
        val b = async { cache.data(id) { loads.incrementAndGet(); byteArrayOf(9) } }
        // Whichever request started the load, both get its bytes.
        val x = a.await()
        assertContentEquals(x, b.await())
        assertEquals(1, loads.get())

        // A new cache (the app relaunched) reads the file instead of loading.
        val again = ImageCache(dir).data(id) { fail("loaded again") }
        assertContentEquals(x, again)
    }

    @Test
    fun keepsNoFileForOddIds() = runBlocking {
        val cache = ImageCache(dir)
        cache.store(byteArrayOf(1), "../escape.png")
        assertContentEquals(byteArrayOf(1), cache.data("../escape.png") { byteArrayOf() }) // memory only
        assertTrue(dir.listFiles().isNullOrEmpty())
        assertTrue(!File(dir.parentFile, "escape.png").exists())
    }

    @Test
    fun namedFileHasTheImage() = runBlocking {
        val cache = ImageCache(dir)
        val file = cache.namedFile(id) { byteArrayOf(1, 2, 3) }
        assertEquals("Image.png", file.name)
        assertContentEquals(byteArrayOf(1, 2, 3), file.readBytes())
        // Asked again, it's the same file, and nothing loads.
        assertEquals(file, cache.namedFile(id) { fail("loaded again") })

        // The system cleared the caches: the file is written again from memory.
        dir.deleteRecursively()
        assertEquals(file, cache.namedFile(id) { byteArrayOf() })
        assertContentEquals(byteArrayOf(1, 2, 3), file.readBytes())

        // Without a cache directory there is no file to give.
        assertFailsWith<FileNotFoundException> { ImageCache(null).namedFile(id) { byteArrayOf(1) } }
        Unit
    }

    @Test
    fun failedLoadsAreRetried() = runBlocking {
        val cache = ImageCache(null)
        assertFailsWith<RpcError> { cache.data(id) { throw RpcError.disconnected() } }
        assertContentEquals(byteArrayOf(7), cache.data(id) { byteArrayOf(7) })
    }
}
