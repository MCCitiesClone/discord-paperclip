package io.github.mccitiesclone.paperclip.command

import io.github.mccitiesclone.paperclip.DiscordPaperclipPlugin
import io.github.mccitiesclone.paperclip.editor.EditorResult
import io.papermc.paper.command.brigadier.BasicCommand
import io.papermc.paper.command.brigadier.CommandSourceStack
import org.bukkit.ChatColor
import org.bukkit.command.CommandSender
import java.util.concurrent.CompletableFuture

class PaperclipCommand(
    private val plugin: DiscordPaperclipPlugin,
    private val reloadAction: () -> Unit,
) : BasicCommand {
    override fun execute(commandSourceStack: CommandSourceStack, args: Array<out String>) {
        val sender = commandSourceStack.sender
        when (args.firstOrNull()?.lowercase()) {
            "reload" -> {
                reloadAction()
                sender.sendMessage("${ChatColor.GREEN}Discord Paperclip reloaded.")
            }
            "sync" -> {
                val syncService = plugin.syncService
                plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable { syncService.syncAll() })
                sender.sendMessage("${ChatColor.GREEN}Discord Paperclip sync started.")
            }
            "editor" -> openEditor(sender, args)
            else -> sender.sendMessage("${ChatColor.YELLOW}Usage: /paperclip <reload|sync|editor [trust <nonce>]>")
        }
    }

    override fun permission(): String = "paperclip.admin"

    private fun openEditor(sender: CommandSender, args: Array<out String>) {
        if (args.getOrNull(1)?.lowercase() == "trust") {
            trustEditor(sender, args.getOrNull(2))
            return
        }

        sender.sendMessage("${ChatColor.GRAY}Creating hosted editor session...")
        plugin.editorClient.createSession(::applyEditorChanges).whenComplete { session, throwable ->
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

        val fingerprint = plugin.editorClient.trust(nonce)
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
