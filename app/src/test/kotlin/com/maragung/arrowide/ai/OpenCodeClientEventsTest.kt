package com.maragung.arrowide.ai

import com.maragung.arrowide.github.FakeTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * [OpenCodeClient.events] end-to-end against a hand-rolled SSE server on
 * the loopback interface (an in-process [ServerSocket] — no external
 * network): parsed event delivery and clean cancellation. The parser
 * itself is covered exhaustively by [SseParserTest].
 */
class OpenCodeClientEventsTest {

    @Test
    fun eventsDeliversParsedPayloadsUntilCancelled() {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val serverSideSocket = AtomicReference<Socket?>(null)
        try {
            val writer = thread(isDaemon = true) {
                val socket = server.accept()
                serverSideSocket.set(socket)
                try {
                    val output = socket.getOutputStream()
                    output.write(
                        "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n\r\n"
                            .toByteArray(Charsets.UTF_8),
                    )
                    output.write(
                        "event: server.connected\r\ndata: {}\r\n\r\n"
                            .toByteArray(Charsets.UTF_8),
                    )
                    output.write(
                        "event: message.updated\r\ndata: {\"id\":\"m1\"}\r\n\r\n"
                            .toByteArray(Charsets.UTF_8),
                    )
                    output.flush()
                    // Hold the connection open until the test closes it.
                    socket.getInputStream().read()
                } catch (e: IOException) {
                    // Client disconnected; expected on cancellation.
                } finally {
                    socket.close()
                }
            }

            // The transport is unused by events() (the SSE stream reads its
            // own connection) but is part of the constructor.
            val client = OpenCodeClient(
                transport = FakeTransport(),
                baseUrl = "http://127.0.0.1:${server.localPort}",
                ioDispatcher = Dispatchers.IO,
            )
            val received = mutableListOf<Pair<String, String>>()
            val gotBoth = CompletableDeferred<Unit>()

            runBlocking {
                val job = launch(Dispatchers.IO) {
                    client.events { eventName, dataJson ->
                        received += eventName to dataJson
                        if (received.size >= 2) gotBoth.complete(Unit)
                    }
                }
                try {
                    withTimeout(10_000) { gotBoth.await() }
                } finally {
                    // A blocked socket read does not react to thread
                    // interruption, so the subscription is unwound by
                    // forcing EOF on the stream first.
                    serverSideSocket.get()?.close()
                    job.cancelAndJoin()
                }
            }

            assertEquals(
                listOf(
                    "server.connected" to "{}",
                    "message.updated" to "{\"id\":\"m1\"}",
                ),
                received,
            )
            // The writer thread finishes once the socket pair closes.
            writer.join(5_000)
        } finally {
            server.close()
        }
    }
}
