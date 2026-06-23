package io.github.mccitiesclone.paperclip

import org.bukkit.configuration.file.FileConfiguration
import java.util.UUID

data class PaperclipConfig(
    val discordToken: String,
    val guildId: String,
    val sync: SyncSettings,
    val editor: EditorSettings,
    val linkedAccounts: Map<UUID, String>,
    val groupRoleMap: Map<String, String>,
) {
    companion object {
        fun from(config: FileConfiguration): PaperclipConfig {
            val linkedAccounts = config.getConfigurationSection("linked-accounts")
                ?.getKeys(false)
                ?.mapNotNull { key ->
                    runCatching { UUID.fromString(key) }.getOrNull()?.let { uuid ->
                        uuid to config.getString("linked-accounts.$key").orEmpty()
                    }
                }
                ?.filter { it.second.isNotBlank() }
                ?.toMap()
                ?: emptyMap()

            val groupRoleMap = config.getConfigurationSection("group-role-map")
                ?.getKeys(false)
                ?.associateWith { group -> config.getString("group-role-map.$group").orEmpty() }
                ?.filterValues { it.isNotBlank() && it != "000000000000000000" }
                ?: emptyMap()

            return PaperclipConfig(
                discordToken = config.getString("discord-token").orEmpty(),
                guildId = config.getString("guild-id").orEmpty(),
                sync = SyncSettings(
                    intervalSeconds = config.getLong("sync.interval-seconds", 300L),
                    discordToMinecraft = config.getBoolean("sync.discord-to-minecraft", true),
                    minecraftToDiscord = config.getBoolean("sync.minecraft-to-discord", true),
                    ignoreUnmapped = config.getBoolean("sync.ignore-unmapped", true),
                ),
                editor = EditorSettings(
                    baseUrl = config.getString("editor.base-url").orEmpty().trimEnd('/'),
                    bytebinUrl = config.getString("editor.bytebin-url").orEmpty().trimEnd('/'),
                    bytesocksUrl = normalizeWebSocketUrl(config.getString("editor.bytesocks-url").orEmpty()),
                    sessionTtlSeconds = config.getLong("editor.session-ttl-seconds", 900L),
                    trustedCaCertificates = config.getStringList("editor.trusted-ca-certificates"),
                    allowInsecureTls = config.getBoolean("editor.allow-insecure-tls", false),
                    trustedEditorKeys = config.getStringList("editor.trusted-editor-keys").toSet(),
                ),
                linkedAccounts = linkedAccounts,
                groupRoleMap = groupRoleMap,
            )
        }

        private fun normalizeWebSocketUrl(value: String): String {
            val trimmed = value.trim().trimEnd('/')
            return when {
                trimmed.startsWith("wss://https://", ignoreCase = true) -> "wss://${trimmed.substringAfter("wss://https://")}"
                trimmed.startsWith("ws://http://", ignoreCase = true) -> "ws://${trimmed.substringAfter("ws://http://")}"
                trimmed.startsWith("https://", ignoreCase = true) -> "wss://${trimmed.substringAfter("https://")}"
                trimmed.startsWith("http://", ignoreCase = true) -> "ws://${trimmed.substringAfter("http://")}"
                else -> trimmed
            }
        }
    }
}

data class SyncSettings(
    val intervalSeconds: Long,
    val discordToMinecraft: Boolean,
    val minecraftToDiscord: Boolean,
    val ignoreUnmapped: Boolean,
)

data class EditorSettings(
    val baseUrl: String,
    val bytebinUrl: String,
    val bytesocksUrl: String,
    val sessionTtlSeconds: Long,
    val trustedCaCertificates: List<String>,
    val allowInsecureTls: Boolean,
    val trustedEditorKeys: Set<String>,
)
