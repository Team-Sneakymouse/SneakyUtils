package com.sneakyutils.commands

import com.sneakyutils.SneakyUtils
import org.bukkit.command.Command

/**
 * Base class for plugin commands. Sets permission to
 * `{identifier}.command.{name}` by default.
 */
abstract class CommandBase(name: String) : Command(name) {

    init {
        this.permission = "${SneakyUtils.IDENTIFIER}.command.$name"
    }
}
