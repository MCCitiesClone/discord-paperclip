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
    val roleGroupMap: Map<String, String>,
    val groupFolders: List<ConfigFolder>,
    val roleFolders: List<ConfigFolder>,
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
            val roleGroupMap = config.getConfigurationSection("role-group-map")
                ?.getKeys(false)
                ?.associateWith { roleId -> config.getString("role-group-map.$roleId").orEmpty() }
                ?.filterKeys { it.isNotBlank() && it != "000000000000000000" }
                ?.filterValues { it.isNotBlank() }
                ?: emptyMap()

            return PaperclipConfig(
                groupFolders = parseFolders(config, "group-folders"),
                roleFolders = parseFolders(config, "role-folders"),
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
                roleGroupMap = roleGroupMap,
            )
        }

        private fun parseFolders(config: FileConfiguration, path: String): List<ConfigFolder> =
            config.getMapList(path).mapNotNull { raw ->
                val name = (raw["name"] as? String)?.trim().orEmpty()
                if (name.isBlank()) return@mapNotNull null
                val members = (raw["members"] as? List<*>)
                    ?.mapNotNull { (it as? String)?.trim()?.ifBlank { null } }
                    ?: emptyList()
                val color = (raw["color"] as? Number)?.toInt()?.takeIf { it in 1..0xFFFFFF }
                val id = (raw["id"] as? String)?.trim()?.ifBlank { null }
                val parent = (raw["parent"] as? String)?.trim()?.ifBlank { null }
                ConfigFolder(name, members, color, id, parent)
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

/**
 * A visual folder grouping LuckPerms groups or Discord roles in the editor. Organizational only,
 * except [color]: when set on a role folder it is the shared color applied to every child role.
 */
data class ConfigFolder(
    val name: String,
    val members: List<String>,
    val color: Int? = null,
    val id: String? = null,
    val parent: String? = null,
)

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
