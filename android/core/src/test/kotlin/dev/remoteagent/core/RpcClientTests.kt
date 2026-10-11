package dev.remoteagent.core

import dev.remoteagent.core.net.PairingLink
import dev.remoteagent.core.net.Pairing
import dev.remoteagent.core.net.RpcClient
import dev.remoteagent.core.net.RpcError
import dev.remoteagent.core.net.ServerMessage
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class RpcClientTests {
    private val server = MockWebServer().apply { start() }
    private val other = MockWebServer().apply { start() }
    private val rad = FakeRad()

    @AfterTest
    fun tearDown() {
        rad.shutDown()
        server.close()
        other.close()
    }

    @Test
    fun connectsToTheFirstAddressThatAnswers() = runBlocking {
        server.upgrade(rad)
        val client = RpcClient("tok")
        val info = client.connect(listOf(DEAD_URL, server.base()))
        assertEquals("Test", info.name)
        assertEquals(server.base(), client.connectedURL)
        assertEquals("Bearer tok", server.takeRequest().headers["Authorization"])
        client.disconnect()
    }

    @Test
    fun unauthorizedStopsTryingAddresses() = runBlocking {
        server.enqueue(MockResponse.Builder().code(401).body("""{"code":"unauthorized","message":"bad token"}""").build())
        val e = assertFailsWith<RpcError> { RpcClient("tok").connect(listOf(server.base(), other.base())) }
        assertEquals("unauthorized", e.code)
        assertEquals(0, other.requestCount)
    }

    @Test
    fun revokedCloseReportsUnauthorized() = runBlocking {
        server.upgrade(rad)
        val client = RpcClient("tok")
        client.connect(listOf(server.base()))
        rad.socket!!.close(4001, "revoked")
        val closed = assertIs<ServerMessage.Closed>(withTimeout(5.seconds) { client.messages.receive() })
        assertEquals(RpcError.unauthorized().message, closed.reason)
    }

    @Test
    fun callsTimeOutAndFailOnDisconnect() = runBlocking {
        server.upgrade(rad)
        val client = RpcClient("tok")
        client.connect(listOf(server.base()))
        assertEquals("timeout", assertFailsWith<RpcError> { client.callRaw("slow", timeout = 200.milliseconds) }.code)

        val pending = async { runCatching { client.callRaw("slow", timeout = 10.seconds) }.exceptionOrNull() }
        rad.nextCall("slow")
        client.disconnect()
        assertEquals("disconnected", (pending.await() as RpcError).code)
        assertEquals(ServerMessage.Closed("closed by client"), client.messages.receive())
    }

    @Test
    fun serverErrorsAreThrown() = runBlocking {
        rad.answer = { _, method, _ ->
            if (method == "project.remove") FakeRad.error("conflict", "busy") else FakeRad.serverInfo
        }
        server.upgrade(rad)
        val client = RpcClient("tok")
        client.connect(listOf(server.base()))
        val e = assertFailsWith<RpcError> { client.callRaw("project.remove") }
        assertEquals("conflict" to "busy", e.code to e.message)
        client.disconnect()
    }

    @Test
    fun uploadErrorsCarryTheServersCode() = runBlocking {
        server.upgrade(rad)
        server.enqueue(MockResponse.Builder().code(400).body("""{"code":"invalid","message":"not an image"}""").build())
        val client = RpcClient("tok")
        client.connect(listOf(server.base()))
        val e = assertFailsWith<RpcError> { client.uploadImage(byteArrayOf(1, 2, 3)) }
        assertEquals("invalid", e.code)
        server.takeRequest()
        val upload = server.takeRequest()
        assertEquals("/v1/images", upload.url.encodedPath)
        assertEquals("Bearer tok", upload.headers["Authorization"])
        client.disconnect()
    }

    @Test
    fun pairingStopsWhenAServerAnswers() = runBlocking {
        server.enqueue(MockResponse.Builder().code(401).body("""{"code":"unauthorized","message":"bad code"}""").build())
        val link = PairingLink("Test", "abc", listOf(server.base(), other.base()))
        val e = assertFailsWith<RpcError> { Pairing.pair(link, "Phone") }
        assertEquals("unauthorized", e.code)
        assertEquals(0, other.requestCount)
    }

    @Test
    fun pairingSkipsUnreachableAddresses() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body(fixture("http_pair_response.json")).build())
        val (result, url) = Pairing.pair(PairingLink("Test", "abc", listOf(DEAD_URL, server.base())), "Phone")
        assertEquals("0123abcd", result.token)
        assertEquals(server.base(), url)
        assertEquals("""{"code":"abc","deviceName":"Phone"}""", server.takeRequest().body?.utf8())
    }
}
