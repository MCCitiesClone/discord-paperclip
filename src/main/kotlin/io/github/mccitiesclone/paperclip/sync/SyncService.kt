package io.github.mccitiesclone.paperclip.sync

import io.github.mccitiesclone.paperclip.DiscordPaperclipPlugin
import io.github.mccitiesclone.paperclip.PaperclipConfig
import io.github.mccitiesclone.paperclip.discord.DiscordService
import io.github.mccitiesclone.paperclip.luckperms.LuckPermsService
import net.luckperms.api.event.EventSubscription
import net.luckperms.api.event.node.NodeMutateEvent
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.scheduler.BukkitTask
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

class SyncService(
    private val plugin: DiscordPaperclipPlugin,
    private val config: PaperclipConfig,
    private val luckPerms: LuckPermsService,
    private val discord: DiscordService,
    private val logger: Logger,
) : Listener {
    private var periodicTask: BukkitTask? = null
    private var luckPermsSubscription: EventSubscription<NodeMutateEvent>? = null
    private val linkedAccounts = ConcurrentHashMap(config.linkedAccounts)
    private val groupToRole = config.groupRoleMap
    private val roleToGroup = config.roleGroupMap
    private val managedGroups = groupToRole.keys
    private val managedGroupsFromDiscord = roleToGroup.values.toSet()
    private val managedRoles = groupToRole.values.toSet()

    fun start() {
        if (config.sync.minecraftToDiscord) {
            luckPermsSubscription = luckPerms.subscribeToGroupChanges(::handleMinecraftGroupUpdate)
        }

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
        luckPermsSubscription?.close()
        luckPermsSubscription = null
    }

    fun syncAll() {
        linkedAccounts.forEach { (minecraftUuid, discordUserId) ->
            syncAccount(minecraftUuid, discordUserId)
        }
    }

    fun linkAccount(minecraftUuid: UUID, discordUserId: String) {
        linkedAccounts[minecraftUuid] = discordUserId
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

        val minecraftUuid = linkedAccounts.entries
            .firstOrNull { it.value == discordUserId }
            ?.key
            ?: return

        syncDiscordToMinecraft(minecraftUuid, discordUserId)
    }

    fun handleMinecraftGroupUpdate(minecraftUuid: UUID) {
        if (!config.sync.minecraftToDiscord) {
            return
        }

        val discordUserId = linkedAccounts[minecraftUuid] ?: return
        syncMinecraftToDiscord(minecraftUuid, discordUserId)
    }

    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        val discordUserId = linkedAccounts[event.player.uniqueId] ?: return
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
                val desiredGroups = applyDiscordRoleMapping(minecraftGroups, discordRoleIds)
                val desiredRoles = applyMinecraftGroupMapping(minecraftGroups, discordRoleIds)
                desiredGroups to desiredRoles
            }
            .thenCompose { (desiredGroups, desiredRoles) ->
                val minecraftFuture = luckPerms.setGroups(minecraftUuid, desiredGroups, managedGroupsFromDiscord)
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
            luckPerms.setGroups(minecraftUuid, desiredGroups, managedGroupsFromDiscord)
        }.exceptionally { throwable ->
            logger.warning("Discord to Minecraft sync failed for $discordUserId: ${throwable.message}")
            null
        }
    }

    private fun applyMinecraftGroupMapping(currentGroups: Set<String>, currentRoleIds: Set<String>): Set<String> {
        val desiredRoles = currentRoleIds.toMutableSet()
        groupToRole.forEach { (group, roleId) ->
            if (group in currentGroups) {
                desiredRoles += roleId
            } else {
                desiredRoles -= roleId
            }
        }
        return desiredRoles intersect managedRoles
    }

    private fun applyDiscordRoleMapping(currentGroups: Set<String>, currentRoleIds: Set<String>): Set<String> {
        val desiredGroups = currentGroups.toMutableSet()
        roleToGroup.forEach { (roleId, group) ->
            if (roleId in currentRoleIds) {
                desiredGroups += group
            } else {
                desiredGroups -= group
            }
        }
        return desiredGroups intersect managedGroupsFromDiscord
    }
}
