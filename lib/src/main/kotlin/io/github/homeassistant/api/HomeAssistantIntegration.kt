package io.github.homeassistant.api

data class HomeAssistantIntegration(
    val name: String,
    val devices: List<HomeAssistantDevice>
)
