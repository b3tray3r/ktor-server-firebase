package com.example.rcon

import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.net.URI
import kotlinx.coroutines.CompletableDeferred

class RconClient(
    private val ip: String,
    private val port: Int,
    private val password: String
) {
    private lateinit var client: WebSocketClient
    private val response = CompletableDeferred<String>()

    suspend fun connectAndFetchStatus(): String {
        return try {
        val uri = URI("ws://$ip:$port/$password")

        client = object : WebSocketClient(uri) {
            override fun onOpen(handshakedata: ServerHandshake?) {
                // После подключения отправим команду
                send("{\"Identifier\": -1, \"Message\": \"status\", \"Name\": \"WebRcon\"}")
            }

            override fun onMessage(message: String?) {
                if (message != null) {
                    response.complete(message)
                    close()
                }
            }

            override fun onClose(code: Int, reason: String?, remote: Boolean) {}
            override fun onError(ex: Exception?) {
                if (!response.isCompleted) response.completeExceptionally(ex ?: Exception("Unknown error"))
            }
        }

        client.connectBlocking()

            // ... существующий код ...
            if (!client.connectBlocking()) {
                throw Exception("Failed to connect to RCON server")
            }
            response.await()
        } catch (e: Exception) {
            client.close()
            throw e
        }
    }
}
