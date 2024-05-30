package at.robert.homeassistant.api

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.ktor.client.*
import io.ktor.client.engine.java.*
import io.ktor.client.plugins.websocket.*
import io.ktor.serialization.jackson.*
import io.ktor.websocket.*
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
    private val coroutineScope: CoroutineScope,
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

    private data class UnsubscribeEventsMsg(
        override val id: Int,
        val subscription: Int,
    ) : CommandMessage {
        override val type: String = "unsubscribe_events"
    }

    private val senderQueue = Channel<Any>(Channel.UNLIMITED)
    private val receiverQueue = Channel<JsonNode>(Channel.UNLIMITED)
    private val subscriptionsMutex = Mutex()
    private val subscriptions = mutableMapOf<Int, Channel<JsonNode>>()
    private val awaitingResultsMutex = Mutex()
    private val awaitingResults = mutableMapOf<Int, CompletableFuture<JsonNode>>()

    private lateinit var con: DefaultClientWebSocketSession
    private val websocketJob: Job = coroutineScope.launch {
        httpClient.webSocket("wss://$host/api/websocket") {
            con = this
            println("Connected")

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
            val processingJob = launch {
                for (msg in receiverQueue) {
                    val type = msg["type"].asText()
                    val id = msg["id"].asInt() //TODO handle missing id
                    when (type) {
                        "result" -> awaitingResultsMutex.withLock {
                            val result = awaitingResults.remove(id)?.complete(msg)
                            when (result) {
                                true -> println("Handled $id")
                                false -> println("Received duplicate answer $msg")
                                null -> println("Received unexpected message $msg")
                            }
                        }

                        "event" -> subscriptionsMutex.withLock {
                            val channel = subscriptions[id]
                            if (channel != null) {
                                channel.send(msg)
                            } else {
                                println("Received event for unknown subscription $msg")
                            }
                        }
                    }
                }
            }
            receiverJob.join()
            processingJob.join()
            senderJob.cancelAndJoin()
        }
        httpClient.close()
    }

    private suspend fun requestResponse(
        id: Int = nextId.getAndIncrement(),
        msg: (Int) -> CommandMessage
    ): JsonNode {
        require(!closed) { "Client is closed" }
        val future = CompletableFuture<JsonNode>()
        awaitingResultsMutex.withLock {
            awaitingResults[id] = future
        }
        senderQueue.send(msg(id))
        return future.await()
    }

    suspend fun getStates(): JsonNode {
        return requestResponse { GetStatesMessage(it) }
    }

    suspend fun getConfig(): JsonNode {
        return requestResponse { GetConfigMessage(it) }
    }

    suspend fun subscribeEvents(eventType: String? = null): Pair<Int, Channel<JsonNode>> {
        require(!closed) { "Client is closed" }
        val id = nextId.getAndIncrement()
        val channel = Channel<JsonNode>(Channel.UNLIMITED)
        subscriptionsMutex.withLock {
            subscriptions[id] = channel
        }
        val response = requestResponse(id) { SubscribeEventsMsg(it, eventType) }
        println("Subscription result: $response")
        return id to channel
    }

    suspend fun unsubscribe(subscriptionId: Int) {
        val rsp = requestResponse {
            UnsubscribeEventsMsg(it, subscriptionId)
        }
        require(rsp["type"].asText() == "result" && rsp["success"].asBoolean()) {
            "Unsubscribe failed: $rsp"
        }
        subscriptionsMutex.withLock {
            subscriptions.remove(subscriptionId)!!.close()
        }
    }

    private var closed = false
    suspend fun disconnect() {
        closed = true
        senderQueue.close()
        receiverQueue.close()
        con.close()
        subscriptionsMutex.withLock {
            subscriptions.values.forEach { it.close() }
            subscriptions.clear()
        }
        awaitingResultsMutex.withLock {
            awaitingResults.values.forEach {
                it.completeExceptionally(CancellationException())
            }
            awaitingResults.clear()
        }
        websocketJob.join()
    }
}

fun main() {
    val accessToken = System.getenv("HOME_ASSISTANT_ACCESS_TOKEN")
        ?: error("HOME_ASSISTANT_ACCESS_TOKEN env var not set")

    runBlocking {
        val homeAssistant = HomeAssistantWsApiClient(
            "robohome.duckdns.org", accessToken, this
        )

        val states = async { homeAssistant.getStates() }
        val config = async { homeAssistant.getConfig() }
        println("Received states")

        val (subscriptionId, stateChangeChannel) = homeAssistant.subscribeEvents("state_changed")
        launch {
            for (msg in stateChangeChannel) {
                println("State changed: ${msg["event"]["data"]["entity_id"]}")
            }
            println("Subscription $subscriptionId done")
        }
        println("Still here")

        delay(5000)
        homeAssistant.unsubscribe(subscriptionId)
        println("Unsubscribed")
        delay(1000)
        launch {
            homeAssistant.getStates().let {
                println("States received a second time")
            }
        }
        delay(100)
        homeAssistant.disconnect()
        println("Disconnected")
    }

    println("Done")
}
