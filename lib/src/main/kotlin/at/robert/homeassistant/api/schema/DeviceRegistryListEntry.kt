package at.robert.homeassistant.api.schema

data class DeviceRegistryListEntry(
    val areaId: String?,
    val configurationUrl: String?,
    val configEntries: List<String>,
    val connections: List<List<String>>,
    val disabledBy: String?,
    val entryType: String?,
    val hwVersion: String?,
    val id: String,
    val identifiers: List<List<String>>,
    val labels: List<String>,
    val manufacturer: String?,
    val model: String?,
    val nameByUser: String?,
    val name: String,
    val serialNumber: String?,
    val swVersion: String?,
    val viaDeviceId: String?,
)
