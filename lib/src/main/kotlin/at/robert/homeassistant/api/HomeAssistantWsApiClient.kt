package at.robert.homeassistant.api

import at.robert.homeassistant.api.schema.ConfigEntry
import at.robert.homeassistant.api.schema.DeviceRegistryListEntry
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.treeToValue
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
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger

class HomeAssistantWsApiClient(
    private val host: String,
    private val accessToken: String,
    coroutineScope: CoroutineScope,
) {

    private val log = LoggerFactory.getLogger(HomeAssistantWsApiClient::class.java)

    private val nextId = AtomicInteger(1)

    @Suppress("MemberVisibilityCanBePrivate")
    val objectMapper = jacksonObjectMapper().also { om ->
        om.propertyNamingStrategy = PropertyNamingStrategies.SNAKE_CASE
        om.setSerializationInclusion(JsonInclude.Include.NON_NULL)
    }

    private val httpClient = HttpClient(Java) {
        install(WebSockets) {
            contentConverter = JacksonWebsocketContentConverter(objectMapper)
        }
    }

    private data class AuthMessage(val accessToken: String) {
        @Suppress("unused")
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
            log.debug("Websocket connection to \"wss://$host/api/websocket\" established")

            val authRequiredMsg: JsonNode = receiveDeserialized()
            require(authRequiredMsg["type"].asText() == "auth_required")
            sendSerialized(AuthMessage(accessToken))
            val authOk: JsonNode = receiveDeserialized()
            require(authOk["type"].asText() == "auth_ok")
            log.debug("Authenticated successfully")

            val receiverJob = launch {
                while (con.isActive) {
                    val msg: JsonNode = receiveDeserialized()
                    receiverQueue.send(msg)
                }
                receiverQueue.close()
                log.trace("Receiver job stopped because connection is no longer active")
            }
            val senderJob = launch {
                for (msg in senderQueue) {
                    sendSerialized(msg)
                    if (log.isTraceEnabled) {
                        log.trace("Sent: ${objectMapper.writeValueAsString(msg)}")
                    }
                }
                log.trace("Sender job stopped because senderQueue is closed")
            }
            val processingJob = launch {
                for (msg in receiverQueue) {
                    val type = msg["type"].asText()
                    val id = msg["id"].asInt() //TODO handle missing id
                    when (type) {
                        "result" -> awaitingResultsMutex.withLock {
                            val success = msg["success"].asBoolean()
                            val result = awaitingResults.remove(id)
                            if (success) {
                                when (result?.complete(msg["result"])) {
                                    true -> log.trace("Handled $id")
                                    false -> log.error("Received duplicate answer $msg")
                                    null -> log.error("Received unexpected message $msg")
                                }
                            } else {
                                result?.completeExceptionally(RuntimeException("Request failed: $msg"))
                            }
                        }

                        "event" -> subscriptionsMutex.withLock {
                            val channel = subscriptions[id]
                            if (channel != null) {
                                channel.send(msg)
                            } else {
                                log.error("Received event for unknown subscription $msg")
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

    suspend fun getConfigEntries(): List<ConfigEntry> {
        return requestResponse { id ->
            object : CommandMessage {
                override val id: Int = id
                override val type: String = "config_entries/get"
            }
        }.let {
            objectMapper.treeToValue<List<ConfigEntry>>(it)
        }
    }

    suspend fun getDeviceRegistryList(): List<DeviceRegistryListEntry> {
        return requestResponse { id ->
            object : CommandMessage {
                override val id: Int = id
                override val type: String = "config/device_registry/list"
            }
        }.let {
            objectMapper.treeToValue<List<DeviceRegistryListEntry>>(it)
        }
    }

    suspend fun subscribeEvents(eventType: String? = null): Pair<Int, Channel<JsonNode>> {
        require(!closed) { "Client is closed" }
        val id = nextId.getAndIncrement()
        val channel = Channel<JsonNode>(Channel.UNLIMITED)
        subscriptionsMutex.withLock {
            subscriptions[id] = channel
        }
        val response = requestResponse(id) { SubscribeEventsMsg(it, eventType) }
        if (log.isTraceEnabled) {
            log.trace("Subscription result: $response")
        }
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

fun main(args: Array<String>) {
    val accessToken = System.getenv("HOME_ASSISTANT_ACCESS_TOKEN")
        ?: error("HOME_ASSISTANT_ACCESS_TOKEN env var not set")
    val host = args.single()

    runBlocking {
        val homeAssistant = HomeAssistantWsApiClient(
            host, accessToken, this
        )

        val shellys = async {
            homeAssistant.getConfigEntries().filter {
                it.domain == "shelly"
            }
        }
        val deviceRegistryList = async { homeAssistant.getDeviceRegistryList() }
        val shellyEntryIds = shellys.await().map { it.entryId }.toSet()
        val shellyDeviceRegistryEntries = deviceRegistryList.await().filter {
            it.configEntries.any { entryId -> shellyEntryIds.contains(entryId) }
        }
        shellyDeviceRegistryEntries.forEach {
            println("${it.nameByUser ?: it.name}: ${it.configurationUrl}")
        }

        homeAssistant.disconnect()
        println("Disconnected")
    }

    println("Done")
}
