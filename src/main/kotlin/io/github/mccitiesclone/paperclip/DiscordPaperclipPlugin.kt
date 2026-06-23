package io.github.mccitiesclone.paperclip

import io.github.mccitiesclone.paperclip.command.PaperclipCommand
import io.github.mccitiesclone.paperclip.command.LinkCommand
import io.github.mccitiesclone.paperclip.discord.DiscordService
import io.github.mccitiesclone.paperclip.editor.EditorClient
import io.github.mccitiesclone.paperclip.editor.EditorResult
import io.github.mccitiesclone.paperclip.link.LinkService
import io.github.mccitiesclone.paperclip.luckperms.LuckPermsService
import io.github.mccitiesclone.paperclip.sync.SyncService
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import org.bukkit.event.HandlerList
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID
import java.util.concurrent.CompletableFuture

class DiscordPaperclipPlugin : JavaPlugin() {
    private lateinit var pluginConfig: PaperclipConfig
    private lateinit var luckPermsService: LuckPermsService
    private lateinit var discordService: DiscordService
    internal lateinit var syncService: SyncService
        private set
    internal lateinit var editorClient: EditorClient
        private set
    internal lateinit var linkService: LinkService
        private set

    override fun onEnable() {
        saveDefaultConfig()
        loadServices()

        server.pluginManager.registerEvents(syncService, this)
        registerCommand()
    }

    override fun onDisable() {
        if (::syncService.isInitialized) {
            syncService.shutdown()
            HandlerList.unregisterAll(syncService)
        }
        if (::discordService.isInitialized) {
            discordService.shutdown()
        }
        if (::editorClient.isInitialized) {
            editorClient.shutdown()
        }
    }

    fun reloadPlugin() {
        reloadConfig()
        if (::syncService.isInitialized) {
            syncService.shutdown()
            HandlerList.unregisterAll(syncService)
        }
        if (::discordService.isInitialized) {
            discordService.shutdown()
        }
        if (::editorClient.isInitialized) {
            editorClient.shutdown()
        }
        loadServices()
        server.pluginManager.registerEvents(syncService, this)
    }

    fun applyEditorUpdate(result: EditorResult): EditorResult {
        config.set("group-role-map", null)
        result.groupRoleMap.forEach { (group, roleId) -> config.set("group-role-map.$group", roleId) }

        config.set("role-group-map", null)
        result.roleGroupMap.forEach { (roleId, group) -> config.set("role-group-map.$roleId", group) }

        config.set("linked-accounts", null)
        result.linkedAccounts.forEach { (uuid, discordId) -> config.set("linked-accounts.$uuid", discordId) }

        config.set("group-folders", foldersToConfig(result.groupFolders))
        config.set("role-folders", foldersToConfig(result.roleFolders))

        saveConfig()
        reloadConfig()
        return result
    }

    /**
     * Creates/reorders/recolours the LuckPerms groups and Discord roles requested from the editor.
     * Discord calls block, and LuckPerms storage operations are awaited, so this MUST run off the
     * server main thread (it is invoked on the editor websocket handler thread).
     */
    fun applyRoleManagement(result: EditorResult) {
        if (::discordService.isInitialized) {
            runCatching { discordService.applyDiscordRoleChanges(result.managedDiscordRoles) }
                .onFailure { logger.warning("Discord role management failed: ${it.message}") }
        }
        if (::luckPermsService.isInitialized) {
            runCatching { luckPermsService.applyGroupChanges(result.managedGroups) }
                .onFailure { logger.warning("LuckPerms group management failed: ${it.message}") }
        }
    }

    private fun foldersToConfig(folders: List<ConfigFolder>): List<Map<String, Any>> =
        folders
            .filter { it.name.isNotBlank() }
            .map { folder ->
                buildMap {
                    put("name", folder.name)
                    put("members", folder.members)
                    folder.color?.let { put("color", it) }
                    folder.id?.let { put("id", it) }
                    folder.parent?.let { put("parent", it) }
                }
            }

    fun completeAccountLink(minecraftUuid: UUID, discordUserId: String) {
        if (server.isPrimaryThread) {
            saveAccountLink(minecraftUuid, discordUserId)
            return
        }

        val future = CompletableFuture<Void>()
        server.scheduler.runTask(this, Runnable {
            runCatching {
                saveAccountLink(minecraftUuid, discordUserId)
            }.fold(
                { future.complete(null) },
                future::completeExceptionally,
            )
        })
        future.join()
    }

    fun trustEditorKey(fingerprint: String) {
        val trustedKeys = config.getStringList("editor.trusted-editor-keys").toMutableSet()
        trustedKeys += fingerprint
        config.set("editor.trusted-editor-keys", trustedKeys.sorted())
        saveConfig()
    }

    /**
     * Refresh derived state after an editor apply without tearing down the editor client.
     * A full reload via [reloadPlugin] closes every active websocket session, which would
     * disconnect the editor that just sent the changes. This reloads the config from disk,
     * refreshes the editor client's in-memory mappings/trusted keys, and recreates the
     * sync service so new mappings take effect immediately.
     */
    fun refreshAfterEditorApply() {
        reloadConfig()
        pluginConfig = PaperclipConfig.from(config)

        if (::editorClient.isInitialized) {
            editorClient.refreshConfig(pluginConfig)
        }

        if (::syncService.isInitialized) {
            syncService.shutdown()
            HandlerList.unregisterAll(syncService)
        }
        syncService = SyncService(this, pluginConfig, luckPermsService, discordService, logger)
        server.pluginManager.registerEvents(syncService, this)
        syncService.start()
    }

    private fun loadServices() {
        pluginConfig = PaperclipConfig.from(config)
        luckPermsService = LuckPermsService(logger)
        discordService = DiscordService(pluginConfig, logger)
        syncService = SyncService(this, pluginConfig, luckPermsService, discordService, logger)
        editorClient = EditorClient(
            pluginConfig,
            dataFolder.toPath(),
            logger,
            luckPermsService::listGroups,
            discordService::availableRoles,
        )
        linkService = LinkService(pluginConfig, dataFolder.toPath(), logger, ::completeAccountLink)

        discordService.start(syncService::handleDiscordMemberRoleUpdate) { discordUserId, code ->
            linkService.completeCode(code, discordUserId)
        }
        syncService.start()
    }

    private fun registerCommand() {
        val paperclipCommand = PaperclipCommand(plugin = this, reloadAction = ::reloadPlugin)
        val linkCommand = LinkCommand(plugin = this)
        lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS) { event ->
            event.registrar().register("paperclip", "Manage Discord Paperclip.", paperclipCommand)
            event.registrar().register("link", "Link your Minecraft account to Discord.", linkCommand)
        }
    }

    private fun saveAccountLink(minecraftUuid: UUID, discordUserId: String) {
        config.set("linked-accounts.$minecraftUuid", discordUserId)
        saveConfig()
        reloadConfig()
        syncService.linkAccount(minecraftUuid, discordUserId)
        server.scheduler.runTaskAsynchronously(this, Runnable {
            syncService.syncAccount(minecraftUuid, discordUserId)
        })
    }
}
