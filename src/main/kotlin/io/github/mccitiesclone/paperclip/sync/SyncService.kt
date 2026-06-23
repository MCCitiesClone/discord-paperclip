package io.github.mccitiesclone.paperclip.sync

import io.github.mccitiesclone.paperclip.DiscordPaperclipPlugin
import io.github.mccitiesclone.paperclip.PaperclipConfig
import io.github.mccitiesclone.paperclip.discord.DiscordService
import io.github.mccitiesclone.paperclip.luckperms.LuckPermsService
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.scheduler.BukkitTask
import java.util.UUID
import java.util.logging.Logger

class SyncService(
    private val plugin: DiscordPaperclipPlugin,
    private val config: PaperclipConfig,
    private val luckPerms: LuckPermsService,
    private val discord: DiscordService,
    private val logger: Logger,
) : Listener {
    private var periodicTask: BukkitTask? = null
    private val groupToRole = config.groupRoleMap
    private val roleToGroup = groupToRole.entries.associate { (group, roleId) -> roleId to group }
    private val managedGroups = groupToRole.keys
    private val managedRoles = groupToRole.values.toSet()

    fun start() {
        if (config.sync.intervalSeconds > 0) {
            periodicTask = plugin.server.scheduler.runTaskTimerAsynchronously(
                plugin,
                Runnable { syncAll() },
                config.sync.intervalSeconds * 20L,
                config.sync.intervalSeconds * 20L,
            )
        }
    }

    fun shutdown() {
        periodicTask?.cancel()
        periodicTask = null
    }

    fun syncAll() {
        config.linkedAccounts.forEach { (minecraftUuid, discordUserId) ->
            syncAccount(minecraftUuid, discordUserId)
        }
    }

    fun syncAccount(minecraftUuid: UUID, discordUserId: String) {
        if (config.sync.minecraftToDiscord && config.sync.discordToMinecraft) {
            syncBidirectional(minecraftUuid, discordUserId)
            return
        }

        if (config.sync.minecraftToDiscord) syncMinecraftToDiscord(minecraftUuid, discordUserId)
        if (config.sync.discordToMinecraft) syncDiscordToMinecraft(minecraftUuid, discordUserId)
    }

    fun handleDiscordMemberRoleUpdate(discordUserId: String) {
        if (!config.sync.discordToMinecraft) {
            return
        }

        val minecraftUuid = config.linkedAccounts.entries
            .firstOrNull { it.value == discordUserId }
            ?.key
            ?: return

        syncDiscordToMinecraft(minecraftUuid, discordUserId)
    }

    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        val discordUserId = config.linkedAccounts[event.player.uniqueId] ?: return
        plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
            syncAccount(event.player.uniqueId, discordUserId)
        })
    }

    private fun syncMinecraftToDiscord(minecraftUuid: UUID, discordUserId: String) {
        luckPerms.loadGroups(minecraftUuid).thenCompose { groups ->
            val desiredRoles = groups.mapNotNull(groupToRole::get).toSet()
            discord.setRoles(discordUserId, desiredRoles, managedRoles)
        }.exceptionally { throwable ->
            logger.warning("Minecraft to Discord sync failed for $minecraftUuid: ${throwable.message}")
            null
        }
    }

    private fun syncBidirectional(minecraftUuid: UUID, discordUserId: String) {
        luckPerms.loadGroups(minecraftUuid)
            .thenCombine(discord.rolesFor(discordUserId)) { minecraftGroups, discordRoleIds ->
                val groupsFromDiscord = discordRoleIds.mapNotNull(roleToGroup::get).toSet()
                val rolesFromMinecraft = minecraftGroups.mapNotNull(groupToRole::get).toSet()
                val desiredGroups = (minecraftGroups intersect managedGroups) + groupsFromDiscord
                val desiredRoles = (discordRoleIds intersect managedRoles) + rolesFromMinecraft
                desiredGroups to desiredRoles
            }
            .thenCompose { (desiredGroups, desiredRoles) ->
                val minecraftFuture = luckPerms.setGroups(minecraftUuid, desiredGroups, managedGroups)
                val discordFuture = discord.setRoles(discordUserId, desiredRoles, managedRoles)
                java.util.concurrent.CompletableFuture.allOf(minecraftFuture, discordFuture)
            }
            .exceptionally { throwable ->
                logger.warning("Bidirectional sync failed for $minecraftUuid/$discordUserId: ${throwable.message}")
                null
            }
    }

    private fun syncDiscordToMinecraft(minecraftUuid: UUID, discordUserId: String) {
        discord.rolesFor(discordUserId).thenCompose { roleIds ->
            val desiredGroups = roleIds.mapNotNull(roleToGroup::get).toSet()
            luckPerms.setGroups(minecraftUuid, desiredGroups, managedGroups)
        }.exceptionally { throwable ->
            logger.warning("Discord to Minecraft sync failed for $discordUserId: ${throwable.message}")
            null
        }
    }
}
