package at.robert.homeassistant.api

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.ktor.client.*
import io.ktor.client.engine.java.*
import io.ktor.client.plugins.websocket.*
import io.ktor.serialization.jackson.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.future.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger

class HomeAssistantWsApiClient(
    private val host: String,
    private val accessToken: String,
) {

    private val nextId = AtomicInteger(1)

    private val objectMapper = jacksonObjectMapper().also { om ->
        om.propertyNamingStrategy = PropertyNamingStrategies.SNAKE_CASE
        om.setSerializationInclusion(JsonInclude.Include.NON_NULL)
    }

    private val httpClient = HttpClient(Java) {
        install(WebSockets) {
            contentConverter = JacksonWebsocketContentConverter(objectMapper)
        }
    }

    private val senderQueue = Channel<Any>(Channel.UNLIMITED)
    suspend fun send(msg: Any) {
        senderQueue.send(msg)
    }

    private val receiverQueue = Channel<JsonNode>(Channel.UNLIMITED)

    private data class AuthMessage(val accessToken: String) {
        val type: String = "auth"
    }

    interface CommandMessage {
        val id: Int
        val type: String
    }

    private data class GetStatesMessage(
        override val id: Int
    ) : CommandMessage {
        override val type: String = "get_states"
    }

    private data class GetConfigMessage(
        override val id: Int
    ) : CommandMessage {
        override val type: String = "get_config"
    }

    private data class SubscribeEventsMsg(
        override val id: Int,
        val eventType: String?
    ) : CommandMessage {
        override val type: String = "subscribe_events"
    }

    suspend fun runConnection() {
        httpClient.webSocket("wss://$host/api/websocket") {
            println("Connected")
            val con = this

            val authRequiredMsg: JsonNode = receiveDeserialized()
            require(authRequiredMsg["type"].asText() == "auth_required")
            sendSerialized(AuthMessage(accessToken))
            val authOk: JsonNode = receiveDeserialized()
            require(authOk["type"].asText() == "auth_ok")
            println("Authenticated, ready to do commands")

            val receiverJob = launch {
                while (con.isActive) {
                    val msg: JsonNode = receiveDeserialized()
                    receiverQueue.send(msg)
                }
                receiverQueue.close()
                println("Receiver job stopped because connection is no longer active")
            }
            val senderJob = launch {
                for (msg in senderQueue) {
                    sendSerialized(msg)
                    println("Sent: $msg")
                }
                println("Sender job stopped because senderQueue is closed")
            }
            receiverJob.join()
            senderJob.cancelAndJoin()
        }
        httpClient.close()
    }

    private val awaitingResultsMutex = Mutex()
    private val awaitingResults = mutableMapOf<Int, CompletableFuture<JsonNode>>()
    suspend fun processMessages() {
        for (msg in receiverQueue) {
            awaitingResultsMutex.withLock {
                val id = msg["id"].asInt() //TODO handle missing id
                val result = awaitingResults.remove(id)?.complete(msg)
                when (result) {
                    true -> println("Handled $id")
                    false -> println("Received duplicate answer $msg")
                    null -> println("Received unexpected message $msg")
                }
            }
        }
    }

    private suspend fun requestResponse(msg: (Int) -> CommandMessage): JsonNode {
        val id = nextId.getAndIncrement()
        val future = CompletableFuture<JsonNode>()
        awaitingResultsMutex.withLock {
            awaitingResults[id] = future
        }
        send(msg(id))
        return future.await()
    }

    suspend fun getStates(): JsonNode {
        return requestResponse { GetStatesMessage(it) }
    }

    suspend fun getConfig(): JsonNode {
        return requestResponse { GetConfigMessage(it) }
    }
}

fun main() {
    val accessToken = System.getenv("HOME_ASSISTANT_ACCESS_TOKEN")
        ?: error("HOME_ASSISTANT_ACCESS_TOKEN env var not set")

    runBlocking {
        val homeAssistant = HomeAssistantWsApiClient(
            "robohome.duckdns.org", accessToken,
        )
        val connectionJob = launch {
            homeAssistant.runConnection()
        }
        val processingJob = launch {
            homeAssistant.processMessages()
        }

        val states = async { homeAssistant.getStates() }
        val config = async { homeAssistant.getConfig() }
        println("Received states")
        println(states.await().toString())
        println(config.await().toString())

        processingJob.join()
        connectionJob.join()
        println("Disconnected")
    }
}
