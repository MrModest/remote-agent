package dev.remoteagent.core

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * A TCP relay on loopback in front of a server. `quiet()` makes the
 * connections open so far stop bringing anything back, as a socket that died
 * while the phone slept does: what the client sends still gets there, but no
 * answer or pong comes back, and nothing says the socket closed.
 */
class Relay(private val target: InetSocketAddress) : Closeable {
    private class Link(val client: Socket, val server: Socket) {
        @Volatile var quiet = false

        fun close() {
            runCatching { client.close() }
            runCatching { server.close() }
        }
    }

    private val listener = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    private val links = CopyOnWriteArrayList<Link>()

    val base: String get() = "http://127.0.0.1:${listener.localPort}"
    /** Connections accepted so far. */
    val connectionCount: Int get() = links.size

    init {
        thread(isDaemon = true, name = "Relay") {
            while (true) {
                val client = runCatching { listener.accept() }.getOrNull() ?: break
                val link = Link(client, Socket(target.address, target.port))
                links += link
                thread(isDaemon = true) { pump(link, client.getInputStream(), link.server.getOutputStream()) { false } }
                thread(isDaemon = true) { pump(link, link.server.getInputStream(), client.getOutputStream()) { link.quiet } }
            }
        }
    }

    /** The connections open now go quiet; later ones relay as usual. */
    fun quiet() {
        for (l in links) l.quiet = true
    }

    private fun pump(link: Link, from: InputStream, to: OutputStream, dropping: () -> Boolean) {
        val buf = ByteArray(8192)
        try {
            while (true) {
                val n = from.read(buf)
                if (n < 0) break
                if (!dropping()) {
                    to.write(buf, 0, n)
                    to.flush()
                }
            }
        } catch (_: IOException) {
        }
        link.close()
    }

    override fun close() {
        runCatching { listener.close() }
        for (l in links) l.close()
    }
}
