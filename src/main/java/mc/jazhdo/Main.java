package mc.jazhdo;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import net.md_5.bungee.api.chat.TextComponent;

public class Main extends JavaPlugin {
    private Logger log;

    private class Commands implements CommandExecutor {
        private List<TPRequest> requests = new ArrayList<>();
        private Main plugin;

        public Commands(Main plugin) {
            this.plugin = plugin;
        }

        private class TPRequest {
            private String from, to;
            private BukkitTask removeTask = null;

            public TPRequest(String from, String to) {
                this.from = from;
                this.to = to;
            }

            public void setTask(BukkitTask removeTask) {
                this.removeTask = removeTask;
            }

            public void expire() {
                requests.remove(this);
                Player fromPlayer = Bukkit.getPlayer(from), toPlayer = Bukkit.getPlayer(to);
                if (fromPlayer != null) sendError(fromPlayer, "Your tp request to " + to + " has expired.");
                if (toPlayer != null) sendError(toPlayer, "Your tp request from " + from + " has expired.");
            }

            public boolean equals(String from, String to) {
                return this.from.equalsIgnoreCase(from) && this.to.equalsIgnoreCase(to);
            }
            
            public void teleport() {
                if (removeTask != null && !removeTask.isCancelled()) removeTask.cancel();
                requests.remove(this);
                Player fromPlayer = Bukkit.getPlayer(from), toPlayer = Bukkit.getPlayer(to);
                if (fromPlayer == null) sendError(toPlayer, "Player " + from + " is no longer online. Teleportation failed.");
                else {
                    sendInfo(fromPlayer, "Teleporting you to " + to + "...");
                    sendInfo(toPlayer, "Teleporting " + from + " to you...");
                    fromPlayer.teleport(toPlayer);
                }
            }
        }

        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (sender == null) return false;

            if (sender instanceof Player player) {
                if (args.length < 1) {
                    sendError(player, "<playername> argument required. (/ " + command.getName() + " <playername>)");
                    return true;
                }

                switch (command.getName()) {
                    case "tpa" -> {
                        String from = args[0], to = player.getName();
                        for (TPRequest request : requests) {
                            if (request.equals(from, to)) {
                                request.teleport();
                                return true;
                            }
                        }
                        sendError(player, "TP request not found.");
                    }
                    case "tpr" -> {
                        // Get and verify the player teleporting to
                        Player to = Bukkit.getPlayer(args[0]);
                        if (to == null) {
                            sendError(player, "Player " + args[0] + " was not found. You can only send tp requests to online players.");
                            return true;
                        }
                        
                        // Make the request and add it to the list
                        String playerName = player.getName(), toName = to.getName();
                        TPRequest request = new TPRequest(playerName, toName);
                        request.setTask(Bukkit.getScheduler().runTaskLater(plugin, request::expire, 1200l));
                        requests.add(request);

                        // Send notifications to both parties
                        sendInfo(player, "Your tp request to " + toName + " has been sent. It will expire in 60 seconds.");
                        sendInfo(to, playerName + " has sent a tp request to you. It will expire in 60 seconds. Use the command \"/tpa " + playerName + "\" to accept.");
                    }
                }
            } else sender.sendMessage(ChatColor.RED + "Only players can use this command.");

            return true;
        }

        private void sendError(Player player, String msg) {
            sendInfo(player, ChatColor.RED + msg);
        }

        private void sendInfo(Player player, String msg) {
            player.spigot().sendMessage(TextComponent.fromLegacyText(ChatColor.GOLD + "[Teleporter] " + ChatColor.WHITE + msg));
        }

    }

    @Override
    public void onEnable() {
        log = getLogger();
        log.log(Level.INFO, "Starting...");

        Commands commands = new Commands(this);
        getCommand("tpa").setExecutor(commands);
        getCommand("tpr").setExecutor(commands);
    }

    @Override
    public void onDisable() {
        log.log(Level.INFO, "Shutting down...");
    }
}