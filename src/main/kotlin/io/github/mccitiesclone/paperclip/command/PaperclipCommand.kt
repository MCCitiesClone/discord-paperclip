package io.github.mccitiesclone.paperclip.command

import io.github.mccitiesclone.paperclip.DiscordPaperclipPlugin
import io.github.mccitiesclone.paperclip.editor.EditorClient
import io.github.mccitiesclone.paperclip.editor.EditorResult
import io.github.mccitiesclone.paperclip.sync.SyncService
import org.bukkit.ChatColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import java.util.concurrent.CompletableFuture

class PaperclipCommand(
    private val plugin: DiscordPaperclipPlugin,
    private val reloadAction: () -> Unit,
    private val syncService: SyncService,
    private val editorClient: EditorClient,
) : CommandExecutor {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        when (args.firstOrNull()?.lowercase()) {
            "reload" -> {
                reloadAction()
                sender.sendMessage("${ChatColor.GREEN}Discord Paperclip reloaded.")
            }
            "sync" -> {
                plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable { syncService.syncAll() })
                sender.sendMessage("${ChatColor.GREEN}Discord Paperclip sync started.")
            }
            "editor" -> openEditor(sender, args)
            else -> sender.sendMessage("${ChatColor.YELLOW}Usage: /$label <reload|sync|editor [trust <nonce>]>")
        }
        return true
    }

    private fun openEditor(sender: CommandSender, args: Array<out String>) {
        if (args.getOrNull(1)?.lowercase() == "trust") {
            trustEditor(sender, args.getOrNull(2))
            return
        }

        sender.sendMessage("${ChatColor.GRAY}Creating hosted editor session...")
        editorClient.createSession(::applyEditorChanges).whenComplete { session, throwable ->
            if (throwable != null) {
                sender.sendMessage("${ChatColor.RED}Could not create editor session: ${throwable.message}")
                return@whenComplete
            }

            sender.sendMessage("${ChatColor.GREEN}Open this editor URL: ${session.editorUrl}")
            sender.sendMessage("${ChatColor.GRAY}Socket channel: ${session.channelId}")
        }
    }

    private fun trustEditor(sender: CommandSender, nonce: String?) {
        if (nonce.isNullOrBlank()) {
            sender.sendMessage("${ChatColor.YELLOW}Usage: /paperclip editor trust <nonce>")
            return
        }

        val fingerprint = editorClient.trust(nonce)
        if (fingerprint == null) {
            sender.sendMessage("${ChatColor.RED}No pending editor session found for nonce $nonce.")
            return
        }

        plugin.trustEditorKey(fingerprint)
        sender.sendMessage("${ChatColor.GREEN}Trusted editor key $fingerprint.")
    }

    private fun applyEditorChanges(result: EditorResult): EditorResult {
        if (plugin.server.isPrimaryThread) {
            val refreshed = plugin.applyEditorUpdate(result)
            plugin.server.scheduler.runTaskLater(plugin, Runnable { reloadAction() }, 40L)
            return refreshed
        }

        val future = CompletableFuture<EditorResult>()
        plugin.server.scheduler.runTask(plugin, Runnable {
            runCatching {
                val refreshed = plugin.applyEditorUpdate(result)
                plugin.server.scheduler.runTaskLater(plugin, Runnable { reloadAction() }, 40L)
                refreshed
            }.fold(future::complete, future::completeExceptionally)
        })
        return future.join()
    }
}
