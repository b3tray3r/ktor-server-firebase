package com.example.rcon

import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.net.URI
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout

class RconClient(
    private val ip: String,
    private val port: Int,
    private val password: String
) {

    suspend fun connectAndFetchStatus(): String {
        return withTimeout(30000) { // 30 секунд таймаут
            val response = CompletableDeferred<String>()
            val uri = URI("ws://$ip:$port/$password")

            val client = object : WebSocketClient(uri) {
                override fun onOpen(handshakedata: ServerHandshake?) {
                    println("WebSocket connected, sending status command...")
                    send("{\"Identifier\": -1, \"Message\": \"status\", \"Name\": \"WebRcon\"}")
                }

                override fun onMessage(message: String?) {
                    println("Received message: $message")
                    if (message != null && !response.isCompleted) {
                        response.complete(message)
                        close()
                    }
                }

                override fun onClose(code: Int, reason: String?, remote: Boolean) {
                    println("WebSocket closed: code=$code, reason=$reason, remote=$remote")
                    if (!response.isCompleted) {
                        response.completeExceptionally(Exception("Connection closed before receiving response"))
                    }
                }

                override fun onError(ex: Exception?) {
                    println("WebSocket error: ${ex?.message}")
                    if (!response.isCompleted) {
                        response.completeExceptionally(ex ?: Exception("Unknown WebSocket error"))
                    }
                }
            }

            try {
                println("Attempting to connect to ws://$ip:$port...")
                val connected = client.connectBlocking()

                if (!connected) {
                    throw Exception("Failed to establish WebSocket connection")
                }

                val result = response.await()
                client.close()
                result

            } catch (e: Exception) {
                println("Error in connectAndFetchStatus: ${e.message}")
                try {
                    client.close()
                } catch (closeEx: Exception) {
                    println("Error closing client: ${closeEx.message}")
                }
                throw e
            }
        }
    }
}