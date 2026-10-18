package com.sneakyutils.commands

import com.sneakyutils.SneakyUtils
import com.mojang.brigadier.tree.LiteralCommandNode
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands

class ReloadCommand(private val plugin: SneakyUtils) {

    fun create(): LiteralCommandNode<CommandSourceStack> =
        Commands.literal(SneakyUtils.IDENTIFIER)
            .then(
                Commands.literal("reload")
                    .requires { it.sender.hasPermission("${SneakyUtils.IDENTIFIER}.command.reload") }
                    .executes { context ->
                        plugin.reloadPlugin()
                        context.source.sender.sendMessage("${plugin.name} configuration reloaded.")
                        1
                    }
            )
            .build()
}
