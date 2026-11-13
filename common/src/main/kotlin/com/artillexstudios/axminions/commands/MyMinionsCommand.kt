package com.artillexstudios.axminions.commands

import com.artillexstudios.axapi.utils.StringUtils
import com.artillexstudios.axminions.api.config.Messages
import com.artillexstudios.axminions.gui.MyMinionsMenu
import org.bukkit.entity.Player
import revxrsal.commands.annotation.Command
import revxrsal.commands.annotation.Description
import revxrsal.commands.bukkit.annotation.CommandPermission

class MyMinionsCommand {

    @Command("myminions")
    @CommandPermission("axminions.command.myminions")
    @Description("List your minions and charge them remotely")
    fun myMinions(player: Player) {
        if (MyMinionsMenu.ownedMinions(player).isEmpty()) {
            player.sendMessage(StringUtils.formatToString(Messages.PREFIX() + MyMinionsMenu.message("empty", "<red>No tienes minions colocados.")))
            return
        }

        MyMinionsMenu(player).open()
    }
}
