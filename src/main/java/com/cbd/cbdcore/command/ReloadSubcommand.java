package com.cbd.cbdcore.command;

import com.cbd.cbdcore.CBDCore;
import org.bukkit.command.CommandSender;

final class ReloadSubcommand implements Subcommand {

    private final CBDCore plugin;

    ReloadSubcommand(CBDCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        plugin.reload();
        CommandFeedback.send(sender, "&aCBDCore 설정을 다시 불러왔습니다.");
    }
}
