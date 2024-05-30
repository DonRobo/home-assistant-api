package at.robert.homeassistant.api

import com.fasterxml.jackson.databind.JsonNode
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.java.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.jackson.*

class HomeAssistantClient(
    private val host: String,
    private val accessToken: String,
    private val ssl: Boolean = false,
    private val port: Int? = null
) {
    private val httpClient = HttpClient(Java) {
        install(ContentNegotiation) {
            jackson()
        }
        defaultRequest {
            header("Authorization", "Bearer $accessToken")
            url {
                protocol = if (ssl) {
                    URLProtocol.HTTPS
                } else {
                    URLProtocol.HTTP
                }
                if (this@HomeAssistantClient.port != null) {
                    port = this@HomeAssistantClient.port
                }
                host = this@HomeAssistantClient.host
            }
        }
    }

    suspend fun getName(): String {
        val resp = httpClient.get("api/config")
        val jsonNode = resp.body<JsonNode>()
        return jsonNode["location_name"].asText()
    }

    suspend fun getIntegrations(): List<HomeAssistantIntegration> {
        TODO()
    }
}

suspend fun main() {
    val accessToken =
        System.getenv("HOME_ASSISTANT_ACCESS_TOKEN") ?: error("HOME_ASSISTANT_ACCESS_TOKEN env var not set")
    val homeAssistant = HomeAssistantClient("robohome.duckdns.org", accessToken, ssl = true)
    println("Connected to ${homeAssistant.getName()}")
}
