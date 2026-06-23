package io.github.mccitiesclone.paperclip

import io.github.mccitiesclone.paperclip.command.PaperclipCommand
import io.github.mccitiesclone.paperclip.discord.DiscordService
import io.github.mccitiesclone.paperclip.editor.EditorClient
import io.github.mccitiesclone.paperclip.editor.EditorResult
import io.github.mccitiesclone.paperclip.luckperms.LuckPermsService
import io.github.mccitiesclone.paperclip.sync.SyncService
import org.bukkit.event.HandlerList
import org.bukkit.plugin.java.JavaPlugin

class DiscordPaperclipPlugin : JavaPlugin() {
    private lateinit var pluginConfig: PaperclipConfig
    private lateinit var luckPermsService: LuckPermsService
    private lateinit var discordService: DiscordService
    private lateinit var syncService: SyncService
    private lateinit var editorClient: EditorClient

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
        registerCommand()
    }

    fun applyEditorUpdate(result: EditorResult): EditorResult {
        config.set("group-role-map", null)
        result.groupRoleMap.forEach { (group, roleId) -> config.set("group-role-map.$group", roleId) }

        config.set("linked-accounts", null)
        result.linkedAccounts.forEach { (uuid, discordId) -> config.set("linked-accounts.$uuid", discordId) }

        saveConfig()
        reloadConfig()
        return result
    }

    fun trustEditorKey(fingerprint: String) {
        val trustedKeys = config.getStringList("editor.trusted-editor-keys").toMutableSet()
        trustedKeys += fingerprint
        config.set("editor.trusted-editor-keys", trustedKeys.sorted())
        saveConfig()
    }

    private fun loadServices() {
        pluginConfig = PaperclipConfig.from(config)
        luckPermsService = LuckPermsService(logger)
        discordService = DiscordService(pluginConfig, logger)
        syncService = SyncService(this, pluginConfig, luckPermsService, discordService, logger)
        editorClient = EditorClient(pluginConfig, logger)

        discordService.start(syncService::handleDiscordMemberRoleUpdate)
        syncService.start()
    }

    private fun registerCommand() {
        getCommand("paperclip")?.setExecutor(
            PaperclipCommand(
                plugin = this,
                reloadAction = ::reloadPlugin,
                syncService = syncService,
                editorClient = editorClient,
            )
        )
    }
}
