package io.github.mccitiesclone.paperclip.luckperms

import net.luckperms.api.LuckPermsProvider
import net.luckperms.api.event.EventSubscription
import net.luckperms.api.event.node.NodeMutateEvent
import net.luckperms.api.model.data.DataMutateResult
import net.luckperms.api.model.group.Group
import net.luckperms.api.model.user.User
import net.luckperms.api.node.NodeType
import net.luckperms.api.node.types.DisplayNameNode
import net.luckperms.api.node.types.InheritanceNode
import net.luckperms.api.node.types.WeightNode
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.logging.Logger

class LuckPermsService(private val logger: Logger) {
    private val luckPerms = LuckPermsProvider.get()

    fun subscribeToGroupChanges(handler: (UUID) -> Unit): EventSubscription<NodeMutateEvent> =
        luckPerms.eventBus.subscribe(NodeMutateEvent::class.java) { event ->
            val user = event.target as? User ?: return@subscribe
            val beforeGroups = event.dataBefore
                .asSequence()
                .filter { it.type == NodeType.INHERITANCE }
                .mapNotNull { it as? InheritanceNode }
                .map { it.groupName }
                .toSet()
            val afterGroups = event.dataAfter
                .asSequence()
                .filter { it.type == NodeType.INHERITANCE }
                .mapNotNull { it as? InheritanceNode }
                .map { it.groupName }
                .toSet()

            if (beforeGroups != afterGroups) {
                handler(user.uniqueId)
            }
        }

    fun availableGroupNames(): Set<String> =
        luckPerms.groupManager.loadedGroups.map { it.name }.toSet()

    /**
     * All known groups with their display name and weight, ordered from highest weight (top) to
     * lowest. Used to populate the editor's role-management page.
     */
    fun listGroups(): List<GroupInfo> =
        luckPerms.groupManager.loadedGroups
            .map { group -> GroupInfo(group.name, group.displayName, group.weight.orElse(0)) }
            .sortedWith(compareByDescending<GroupInfo> { it.weight }.thenBy { it.name })

    /**
     * Creates any missing groups and assigns weights so the supplied order is preserved (first
     * entry = highest weight). Optionally sets each group's display name. Existing groups are never
     * deleted. Safe to call from any thread; LuckPerms storage operations are awaited.
     */
    fun applyGroupChanges(desired: List<DesiredGroup>) {
        if (desired.isEmpty()) {
            return
        }
        val manager = luckPerms.groupManager
        val size = desired.size
        desired.forEachIndexed { index, request ->
            val name = request.name.trim().lowercase()
            if (name.isBlank()) {
                return@forEachIndexed
            }
            val group = manager.getGroup(name)
                ?: runCatching { manager.createAndLoadGroup(name).join() }.getOrNull()
                ?: run {
                    logger.warning("Failed to create or load LuckPerms group $name")
                    return@forEachIndexed
                }

            val weight = (size - index) * WEIGHT_STEP
            applyWeight(group, weight)
            applyDisplayName(group, request.displayName?.trim().orEmpty())

            runCatching { manager.saveGroup(group).join() }
                .onFailure { logger.warning("Failed to save LuckPerms group $name: ${it.message}") }
        }
    }

    private fun applyWeight(group: Group, weight: Int) {
        group.data().clear { it.type == NodeType.WEIGHT }
        group.data().add(WeightNode.builder(weight).build())
    }

    private fun applyDisplayName(group: Group, displayName: String) {
        group.data().clear { it.type == NodeType.DISPLAY_NAME }
        if (displayName.isNotBlank()) {
            group.data().add(DisplayNameNode.builder(displayName).build())
        }
    }

    fun loadGroups(uuid: UUID): CompletableFuture<Set<String>> =
        luckPerms.userManager.loadUser(uuid).thenApply { user ->
            user.getNodes(NodeType.INHERITANCE).map { it.groupName }.toSet()
        }

    fun setGroups(uuid: UUID, desiredGroups: Set<String>, managedGroups: Set<String>): CompletableFuture<Void> =
        luckPerms.userManager.loadUser(uuid).thenCompose { user ->
            val currentGroups = user.getNodes(NodeType.INHERITANCE).map { it.groupName }.toSet()
            val toAdd = desiredGroups - currentGroups
            val toRemove = (currentGroups intersect managedGroups) - desiredGroups

            toAdd.forEach { addGroup(user, it) }
            toRemove.forEach { removeGroup(user, it) }

            luckPerms.userManager.saveUser(user).exceptionally { throwable ->
                logger.warning("Failed to save LuckPerms user $uuid: ${throwable.message}")
                null
            }
        }

    private fun addGroup(user: User, group: String) {
        val result = user.data().add(InheritanceNode.builder(group).build())
        logMutation("add", group, result)
    }

    private fun removeGroup(user: User, group: String) {
        val result = user.data().remove(InheritanceNode.builder(group).build())
        logMutation("remove", group, result)
    }

    private fun logMutation(action: String, group: String, result: DataMutateResult) {
        if (!result.wasSuccessful()) {
            logger.fine("LuckPerms $action group $group skipped: $result")
        }
    }

    companion object {
        private const val WEIGHT_STEP = 10
    }
}

/** A LuckPerms group as shown in the editor's role-management page. */
data class GroupInfo(
    val name: String,
    val displayName: String?,
    val weight: Int,
)

/** A group the editor wants to exist, in priority order (first = highest weight). */
data class DesiredGroup(
    val name: String,
    val displayName: String?,
)
