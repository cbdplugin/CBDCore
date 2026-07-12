package com.cbd.cbdcore.command;

import org.bukkit.command.CommandSender;

import java.util.List;

interface Subcommand {

    void execute(CommandSender sender, String[] args);

    default List<String> tabComplete(CommandSender sender, String[] args) {
        return List.of();
    }
}
