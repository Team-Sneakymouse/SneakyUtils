package com.sneakyutils.commands

import com.sneakyutils.SneakyUtils
import org.bukkit.command.CommandSender
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CommandBaseTest {

    private class TestCommand : CommandBase("reload") {
        override fun execute(
            sender: CommandSender,
            commandLabel: String,
            args: Array<out String>,
        ): Boolean = true
    }

    @Test
    fun permissionFollowsIdentifierAndCommandName() {
        assertEquals("${SneakyUtils.IDENTIFIER}.command.reload", TestCommand().permission)
    }
}
