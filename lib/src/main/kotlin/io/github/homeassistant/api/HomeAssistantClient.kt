package io.github.homeassistant.api

class HomeAssistantClient(
    private val url: String,
    private val accessToken: String
) {
    suspend fun getName(): String {
        TODO()
    }

    suspend fun getIntegrations(): List<HomeAssistantIntegration> {
        TODO()
    }
}
