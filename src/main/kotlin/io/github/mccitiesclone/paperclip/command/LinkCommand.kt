package io.github.mccitiesclone.paperclip.command

import io.github.mccitiesclone.paperclip.DiscordPaperclipPlugin
import io.papermc.paper.command.brigadier.BasicCommand
import io.papermc.paper.command.brigadier.CommandSourceStack
import org.bukkit.ChatColor
import org.bukkit.entity.Player
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class LinkCommand(
    private val plugin: DiscordPaperclipPlugin,
) : BasicCommand {
    override fun execute(commandSourceStack: CommandSourceStack, args: Array<out String>) {
        val player = commandSourceStack.sender as? Player
        if (player == null) {
            commandSourceStack.sender.sendMessage("${ChatColor.RED}Only players can create account link codes.")
            return
        }

        player.sendMessage("${ChatColor.GRAY}Creating Discord link code...")
        plugin.linkService.createCode(player.uniqueId, player.name).whenComplete { linkCode, throwable ->
            plugin.server.scheduler.runTask(plugin, Runnable {
                if (throwable != null) {
                    player.sendMessage("${ChatColor.RED}Could not create link code: ${throwable.message ?: throwable.javaClass.simpleName}")
                    return@Runnable
                }

                player.sendMessage("${ChatColor.GREEN}Enter this code with the Discord /link command: ${ChatColor.WHITE}${linkCode.code}")
                player.sendMessage("${ChatColor.GRAY}Expires: ${formatExpiry(linkCode.expiresAt)}")
            })
        }
    }

    override fun permission(): String = "paperclip.link"

    private fun formatExpiry(expiresAt: Long): String =
        DateTimeFormatter.ofPattern("h:mm:ss a z")
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochSecond(expiresAt))
}
