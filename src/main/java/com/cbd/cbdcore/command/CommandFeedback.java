package com.cbd.cbdcore.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.CommandSender;

final class CommandFeedback {

    private CommandFeedback() {
    }

    static void send(CommandSender sender, String legacyText) {
        Component component = LegacyComponentSerializer.legacyAmpersand().deserialize(legacyText);
        sender.sendMessage(component);
    }
}
