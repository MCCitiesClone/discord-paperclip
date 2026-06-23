package io.github.mccitiesclone.paperclip.luckperms

import net.luckperms.api.LuckPermsProvider
import net.luckperms.api.model.data.DataMutateResult
import net.luckperms.api.model.user.User
import net.luckperms.api.node.NodeType
import net.luckperms.api.node.types.InheritanceNode
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.logging.Logger

class LuckPermsService(private val logger: Logger) {
    private val luckPerms = LuckPermsProvider.get()

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
}
