package at.robert.homeassistant.api.schema

data class ConfigEntry(
    val entryId: String,
    val domain: String,
    val title: String,
    val source: String,
    val state: String,
    val supportsOptions: Boolean,
    val supportsRemoveDevice: Boolean,
    val supportsUnload: Boolean,
    val supportsReconfigure: Boolean,
    val prefDisableNewEntities: Boolean,
    val prefDisablePolling: Boolean,
    val disabledBy: String?,
    val reason: String?,
    val errorReasonTranslationKey: String?,
    val errorReasonTranslationPlaceholders: String?
)
