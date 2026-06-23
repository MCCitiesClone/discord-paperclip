package io.github.mccitiesclone.paperclip.discord

import io.github.mccitiesclone.paperclip.PaperclipConfig
import io.github.mccitiesclone.paperclip.link.LinkResult
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.events.session.ReadyEvent
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Role
import net.dv8tion.jda.api.entities.UserSnowflake
import net.dv8tion.jda.api.events.guild.member.GuildMemberRoleAddEvent
import net.dv8tion.jda.api.events.guild.member.GuildMemberRoleRemoveEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.Commands
import net.dv8tion.jda.api.requests.GatewayIntent
import java.util.concurrent.CompletableFuture
import java.util.logging.Logger

class DiscordService(
    private val config: PaperclipConfig,
    private val logger: Logger,
) {
    @Volatile
    private var jda: JDA? = null

    fun start(
        roleUpdateHandler: (discordUserId: String) -> Unit,
        linkHandler: (discordUserId: String, code: String) -> CompletableFuture<LinkResult>,
    ) {
        if (config.discordToken.isBlank() || config.discordToken == "replace-me") {
            logger.warning("Discord token is not configured; Discord sync is disabled.")
            return
        }
        if (config.guildId.isBlank() || config.guildId == "000000000000000000") {
            logger.warning("Discord guild-id is not configured; Discord sync is disabled.")
            return
        }

        jda = JDABuilder.createDefault(config.discordToken)
            .enableIntents(GatewayIntent.GUILD_MEMBERS)
            .addEventListeners(
                RoleChangeListener(config.guildId, roleUpdateHandler),
                LinkSlashListener(config.guildId, linkHandler, logger),
            )
            .build()
    }

    fun shutdown() {
        jda?.shutdownNow()
        jda = null
    }

    fun rolesFor(discordUserId: String): CompletableFuture<Set<String>> {
        val guild = guild() ?: return CompletableFuture.failedFuture(IllegalStateException("Discord guild ${config.guildId} is unavailable."))
        val future = CompletableFuture<Set<String>>()
        guild.retrieveMemberById(discordUserId).queue(
            { member -> future.complete(member.roles.map { it.id }.toSet()) },
            { throwable ->
                logger.warning("Failed to load Discord member $discordUserId: ${throwable.message}")
                future.completeExceptionally(throwable)
            }
        )
        return future
    }

    fun availableRoles(): List<DiscordRole> =
        guild()
            ?.roles
            ?.asSequence()
            ?.filterNot { it.isPublicRole || it.isManaged }
            ?.map { role -> DiscordRole(id = role.id, name = role.name, color = role.colorRaw, position = role.position) }
            ?.sortedWith(compareByDescending(DiscordRole::position).thenBy(String.CASE_INSENSITIVE_ORDER, DiscordRole::name))
            ?.toList()
            ?: emptyList()

    /**
     * Reconciles the guild's manageable roles against the editor's desired list. Entries without an
     * id are created; entries with an id are renamed/recoloured when changed. The list order (first =
     * top) is applied to the roles the bot can move. Roles are never deleted.
     *
     * This issues blocking JDA calls and MUST be invoked off the server's main thread and off JDA's
     * own websocket thread (the editor websocket handler thread is fine).
     */
    fun applyDiscordRoleChanges(desired: List<DesiredDiscordRole>) {
        if (desired.isEmpty()) {
            return
        }
        val guild = guild() ?: return

        val orderedTopToBottom = ArrayList<Role>(desired.size)
        desired.forEach { request ->
            val name = request.name.trim()
            if (name.isBlank()) {
                return@forEach
            }
            val color = request.color.takeIf { it in 1..0xFFFFFF }
            val existingId = request.id?.trim().orEmpty()

            val role = if (existingId.isBlank()) {
                createRole(guild, name, color)
            } else {
                updateRole(guild, existingId, name, color)
            }
            if (role != null) {
                orderedTopToBottom += role
            }
        }

        reorderRoles(guild, orderedTopToBottom)
    }

    private fun createRole(guild: Guild, name: String, color: Int?): Role? {
        if (!guild.selfMember.hasPermission(Permission.MANAGE_ROLES)) {
            logger.warning("Cannot create Discord role $name: bot lacks Manage Roles permission.")
            return null
        }
        // Discord treats role colour 0 as "no colour" (inherit). JDA reports an unset colour back as
        // Role.DEFAULT_COLOR_RAW, so we write 0 but compare reads against the sentinel below.
        return runCatching {
            guild.createRole().setName(name).setColor(color ?: 0).complete()
        }.onFailure {
            logger.warning("Failed to create Discord role $name: ${it.message}")
        }.getOrNull()
    }

    private fun updateRole(guild: Guild, roleId: String, name: String, color: Int?): Role? {
        val role = guild.getRoleById(roleId) ?: run {
            logger.warning("Discord role $roleId no longer exists; skipping update.")
            return null
        }
        if (role.isManaged || role.isPublicRole || !guild.selfMember.canInteract(role)) {
            // Keep it in the ordering reference, but the bot cannot edit it.
            return role
        }

        val targetColorRaw = color ?: Role.DEFAULT_COLOR_RAW
        val needsRename = role.name != name
        val needsRecolor = role.colorRaw != targetColorRaw
        if (needsRename || needsRecolor) {
            runCatching {
                val manager = role.manager
                if (needsRename) manager.setName(name)
                if (needsRecolor) manager.setColor(color ?: 0)
                manager.complete()
            }.onFailure {
                logger.warning("Failed to update Discord role ${role.name} ($roleId): ${it.message}")
            }
        }
        return role
    }

    /**
     * Re-orders the supplied roles (first = highest) within the position slots they already occupy,
     * leaving roles the bot cannot move untouched. Best effort: failures are logged, not thrown.
     */
    private fun reorderRoles(guild: Guild, orderedTopToBottom: List<Role>) {
        val movable = orderedTopToBottom.filter { guild.selfMember.canInteract(it) && !it.isPublicRole }
        if (movable.size < 2) {
            return
        }

        runCatching {
            val action = guild.modifyRolePositions()
            val current = action.currentOrder.toMutableList()
            // modifyRolePositions() may hand back roles in ascending or descending order; detect it
            // so we map our top-to-bottom intent onto the action's orientation.
            val ascending = current.size >= 2 && current.first().position <= current.last().position
            val desiredSequence = if (ascending) movable.asReversed() else movable

            val movableIds = movable.map { it.id }.toSet()
            val slots = current.indices.filter { current[it].id in movableIds }
            slots.forEachIndexed { slotPosition, slotIndex ->
                desiredSequence.getOrNull(slotPosition)?.let { current[slotIndex] = it }
            }

            current.forEachIndexed { index, role ->
                runCatching { action.selectPosition(role).moveTo(index) }
            }
            action.complete()
        }.onFailure {
            logger.warning("Failed to reorder Discord roles: ${it.message}")
        }
    }

    fun setRoles(discordUserId: String, desiredRoleIds: Set<String>, managedRoleIds: Set<String>): CompletableFuture<Void> {
        val guild = guild() ?: return CompletableFuture.completedFuture(null)
        val future = CompletableFuture<Void>()
        guild.retrieveMemberById(discordUserId).queue(
            { member ->
                val currentRoleIds = member.roles.map { it.id }.toSet()
                val rolesToAdd = (desiredRoleIds - currentRoleIds).mapNotNull { guild.roleForSync(it) }
                val rolesToRemove = ((currentRoleIds intersect managedRoleIds) - desiredRoleIds).mapNotNull { guild.roleForSync(it) }
                applyRoleChanges(guild, discordUserId, rolesToAdd, rolesToRemove, future)
            },
            { throwable ->
                logger.warning("Failed to load Discord member $discordUserId for role sync: ${throwable.message}")
                future.complete(null)
            }
        )
        return future
    }

    private fun applyRoleChanges(
        guild: Guild,
        discordUserId: String,
        rolesToAdd: List<Role>,
        rolesToRemove: List<Role>,
        future: CompletableFuture<Void>,
    ) {
        val member = UserSnowflake.fromId(discordUserId)
        val tasks = mutableListOf<CompletableFuture<Unit>>()

        rolesToAdd.forEach { role ->
            val task = CompletableFuture<Unit>()
            guild.addRoleToMember(member, role).queue({ task.complete(Unit) }, { task.completeExceptionally(it) })
            tasks += task
        }
        rolesToRemove.forEach { role ->
            val task = CompletableFuture<Unit>()
            guild.removeRoleFromMember(member, role).queue({ task.complete(Unit) }, { task.completeExceptionally(it) })
            tasks += task
        }

        CompletableFuture.allOf(*tasks.toTypedArray()).whenComplete { _, throwable ->
            if (throwable != null) {
                logger.warning("Failed to apply Discord roles for $discordUserId: ${throwable.message}")
            }
            future.complete(null)
        }
    }

    private fun guild(): Guild? {
        val currentJda = jda ?: return null
        return currentJda.getGuildById(config.guildId)
    }

    private fun Guild.roleForSync(roleId: String): Role? {
        val role = getRoleById(roleId)
        if (role == null) {
            logger.warning("Configured Discord role $roleId is unavailable in guild $id.")
            return null
        }
        if (role.isManaged || role.isPublicRole) {
            logger.warning("Configured Discord role ${role.name} ($roleId) cannot be managed.")
            return null
        }
        if (!selfMember.canInteract(role)) {
            logger.warning("Configured Discord role ${role.name} ($roleId) is higher than or equal to the bot's top role.")
            return null
        }
        return role
    }
}

data class DiscordRole(
    val id: String,
    val name: String,
    val color: Int,
    val position: Int = 0,
)

/** A Discord role the editor wants to exist, in display order (first = top). */
data class DesiredDiscordRole(
    val id: String?,
    val name: String,
    val color: Int,
)

private class RoleChangeListener(
    private val guildId: String,
    private val roleUpdateHandler: (discordUserId: String) -> Unit,
) : ListenerAdapter() {
    override fun onGuildMemberRoleAdd(event: GuildMemberRoleAddEvent) {
        if (event.guild.id == guildId) {
            roleUpdateHandler(event.user.id)
        }
    }

    override fun onGuildMemberRoleRemove(event: GuildMemberRoleRemoveEvent) {
        if (event.guild.id == guildId) {
            roleUpdateHandler(event.user.id)
        }
    }
}

private class LinkSlashListener(
    private val guildId: String,
    private val linkHandler: (discordUserId: String, code: String) -> CompletableFuture<LinkResult>,
    private val logger: Logger,
) : ListenerAdapter() {
    override fun onReady(event: ReadyEvent) {
        val guild = event.jda.getGuildById(guildId)
        if (guild == null) {
            logger.warning("Could not register Discord /link command because guild $guildId is unavailable.")
            return
        }

        guild.upsertCommand(
            Commands.slash("link", "Link your Discord account to your Minecraft account.")
                .addOption(OptionType.STRING, "code", "The code from Minecraft /link.", true)
        ).queue(
            { logger.info("Registered Discord /link slash command in guild $guildId.") },
            { throwable -> logger.warning("Failed to register Discord /link slash command: ${throwable.message}") },
        )
    }

    override fun onSlashCommandInteraction(event: SlashCommandInteractionEvent) {
        if (event.name != "link" || event.guild?.id != guildId) {
            return
        }

        val code = event.getOption("code")?.asString.orEmpty()
        event.deferReply(true).queue()
        linkHandler(event.user.id, code).whenComplete { result, throwable ->
            val message = when {
                throwable != null -> "Could not link accounts: ${throwable.message ?: throwable.javaClass.simpleName}"
                result is LinkResult.Linked -> {
                    val name = result.minecraftName.ifBlank { result.minecraftUuid.toString() }
                    "Linked your Discord account to Minecraft account $name."
                }
                result is LinkResult.Failed -> result.reason
                else -> "Could not link accounts."
            }
            event.hook.editOriginal(message).queue(
                {},
                { replyError -> logger.warning("Failed to send Discord /link response: ${replyError.message}") },
            )
        }
    }
}
