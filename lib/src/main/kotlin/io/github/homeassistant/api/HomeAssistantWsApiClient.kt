package io.github.homeassistant.api

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.ktor.client.*
import io.ktor.client.engine.java.*
import io.ktor.client.plugins.websocket.*
import io.ktor.serialization.jackson.*
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class HomeAssistantWsApiClient(
    private val host: String,
    private val accessToken: String,
) {

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

    private data class AuthMessage(val accessToken: String) {
        val type: String = "auth"
    }

    private data class GetStatesMessage(val id: Int) {
        val type: String = "get_states"
    }

    private data class SubscribeEventsMsg(val id: Int, val eventType: String?) {
        val type: String = "subscribe_events"
    }

    suspend fun runConnection() {
        httpClient.webSocket("wss://$host/api/websocket") {
            println("Got here")
            val con = this

            val receiverJob = launch {
                while (con.isActive) {
                    val msg: JsonNode = receiveDeserialized()
                    println("received $msg")
                    if (msg["type"].textValue() == "auth_required") {
                        println("Sending auth message")
                        send(AuthMessage(accessToken))
                        println("And getting states")
                        send(SubscribeEventsMsg(1, "state_changed"))
                    } else {
                        println("Received: $msg")
                    }
                }
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
}

fun main() {
    val accessToken =
        System.getenv("HOME_ASSISTANT_ACCESS_TOKEN") ?: error("HOME_ASSISTANT_ACCESS_TOKEN env var not set")
    runBlocking {
        val homeAssistant = HomeAssistantWsApiClient(
            "robohome.duckdns.org", accessToken,
        )
        val connectionJob = launch {
            homeAssistant.runConnection()
        }
        println("Connected")
        connectionJob.join()
        println("Disconnected")
    }
}
