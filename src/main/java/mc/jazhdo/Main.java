package mc.jazhdo;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;

import net.md_5.bungee.api.chat.TextComponent;

@SuppressWarnings("CallToPrintStackTrace")
public class Main extends JavaPlugin {
    private Logger log;
    private Connection conn;
    private BukkitScheduler scheduler;

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
                if (command.getName().startsWith("tp") && args.length < 1) {
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
                    case "sethome" -> {
                        if (args.length < 1) sendError(player, "<home_name> argument required. (/sethome <home_name>)");
                        else {
                            Location loc = player.getLocation();
                            double x = loc.getX(), y = loc.getY(), z = loc.getZ();
                            float yaw = loc.getYaw(), pitch = loc.getPitch();
                            String playerName = player.getName(), world = loc.getWorld().getName();
                            scheduler.runTaskAsynchronously(plugin, () -> {
                                try (PreparedStatement ps = conn.prepareStatement("INSERT OR IGNORE INTO homes (player, name, world, x, y, z, yaw, pitch) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
                                    ps.setString(1, playerName.toLowerCase());
                                    ps.setString(2, args[0]);
                                    ps.setString(3, world);
                                    ps.setDouble(4, x);
                                    ps.setDouble(5, y);
                                    ps.setDouble(6, z);
                                    ps.setFloat(7, yaw);
                                    ps.setFloat(8, pitch);
                                    int affected = ps.executeUpdate();
                                    scheduler.runTask(plugin, () -> {
                                        if (affected < 1) sendError(player, "A home named \"" + args[0] + "\" already exists. Please chose a different name. (Home names are case insensitive.)");
                                        else sendInfo(player, "Successfully created a home named \"" + args[0] + "\".");
                                    });
                                } catch (SQLException ex) {
                                    ex.printStackTrace();
                                    log.log(Level.WARNING, "There was an error setting the home of player {0} named {1}. Error message: {2}", new String[]{playerName, args[0], ex.getMessage()});
                                }
                            });
                        }
                    }
                    // TODO: GO through async tasks and make sure EVERYTHING is thread safe (logging is thread safe)
                    case "delhome" -> {
                        if (args.length < 1) sendError(player, "<home_name> argument required. (/delhome <home_name>)");
                        else {
                            String playerName = player.getName();
                            scheduler.runTaskAsynchronously(plugin, () -> {
                                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM homes WHERE player = ? AND name = ?")) {
                                    ps.setString(1, playerName.toLowerCase());
                                    ps.setString(2, args[0]);
                                    int affected = ps.executeUpdate();
                                    scheduler.runTask(plugin, () -> {
                                        if (affected < 1) sendError(player, "You have no home named \"" + args[0] + "\".");
                                        else sendInfo(player, "Successfully deleted your home named \"" + args[0] + "\".");
                                    });
                                } catch (SQLException ex) {
                                    ex.printStackTrace();
                                    log.log(Level.WARNING, "There was an error deleting player {0}'s home named {1}. Error message: {2}", new String[]{playerName, args[0], ex.getMessage()});
                                }
                            });
                        }
                    }
                    case "homes" -> {
                        String playerName = player.getName();
                        scheduler.runTaskAsynchronously(plugin, () -> {
                            try (PreparedStatement ps = conn.prepareStatement("SELECT name FROM homes WHERE player = ?")) {
                                ps.setString(1, playerName.toLowerCase());
                                ResultSet result = ps.executeQuery();
                                String build = ChatColor.GOLD + "=== Your Homes ===";
                                while (result.next()) build += "\n" + result.getString("name");
                                String output = build;
                                scheduler.runTask(plugin, () -> sendMsg(player, output));
                            } catch (SQLException ex) {
                                ex.printStackTrace();
                                log.log(Level.WARNING, "There was an error getting all the homes from player {0}. Error message: {1}", new String[]{playerName, ex.getMessage()});
                            }
                        });
                    }
                    case "renamehome" -> {
                        if (args.length > 1) {
                            String playerName = player.getName();
                            scheduler.runTaskAsynchronously(plugin, () -> {
                                try (PreparedStatement ps = conn.prepareStatement("UPDATE OR IGNORE homes SET name = ? WHERE player = ? AND name = ?")) {
                                    ps.setString(1, args[1]);
                                    ps.setString(2, playerName.toLowerCase());
                                    ps.setString(3, args[0]);
                                    int affected = ps.executeUpdate();
                                    scheduler.runTask(plugin, () -> {
                                        if (affected < 1) sendError(player, "You have no home named \"" + args[0] + "\" or you already have a home named \"" + args[1] + "\". (Home names are case insensitive.)");
                                        else sendInfo(player, "Successfully renamed home " + args[0] + " to " + args[1] + ".");
                                    });
                                } catch (SQLException ex) {
                                    ex.printStackTrace();
                                    log.log(Level.WARNING, "There was an error renaming {0}'s home from {1} to {2}. Error message: {3}", new String[]{playerName, args[0], args[1], ex.getMessage()});
                                }
                            });
                        } else if (args.length == 1) sendError(player, "<new_name> argument required. (/renamehome <old_name> <new_name>)");
                        else sendError(player, "<old_name> argument required. (/renamehome <old_name> <new_name>)");
                    }
                    case "home" -> {
                        if (args.length > 0) {
                            String playerName = player.getName();
                            scheduler.runTaskAsynchronously(plugin, () -> {
                                try (PreparedStatement ps = conn.prepareStatement("SELECT world, x, y, z, yaw, pitch FROM homes WHERE player = ? AND name = ?")) {
                                    ps.setString(1, playerName.toLowerCase());
                                    ps.setString(2, args[0]);
                                    ResultSet result = ps.executeQuery();
                                    if (!result.next()) {
                                        scheduler.runTask(plugin, () -> sendError(player, "You have no home named \"" + args[0] + "\"."));
                                        return;
                                    }
                                    String world = result.getString("world");
                                    double x = result.getDouble("x"), y = result.getDouble("y"), z = result.getDouble("z");
                                    float yaw = result.getFloat("yaw"), pitch = result.getFloat("pitch");
                                    scheduler.runTask(plugin, () -> {
                                        sendInfo(player, "Teleporting you to your " + args[0] + " home.");
                                        player.teleport(new Location(Bukkit.getWorld(world), x, y, z, yaw, pitch));
                                    });
                                } catch (SQLException ex) {
                                    ex.printStackTrace();
                                    log.log(Level.WARNING, "There was an error querying for player {0}'s home {1}'s location. Error message: {2}", new String[]{playerName, args[0], ex.getMessage()});
                                }
                            });
                        } else sendError(player, "<home_name> argument required. (/home <home_name)");
                    }
                }
            } else sender.sendMessage(ChatColor.RED + "Only players can use this command.");

            return true;
        }

        private void sendError(Player player, String msg) {
            sendInfo(player, ChatColor.RED + msg);
        }

        private void sendInfo(Player player, String msg) {
            sendMsg(player, ChatColor.GOLD + "[Teleporter] " + ChatColor.WHITE + msg);
        }

        private void sendMsg(Player player, String msg) {
            player.spigot().sendMessage(TextComponent.fromLegacyText(msg));
        }
    }

    @Override
    public void onEnable() {
        log = getLogger();
        log.log(Level.INFO, "Starting...");

        // Initalize commands
        Commands commands = new Commands(this);
        String[] cmds = new String[]{"tpa", "tpr", "sethome", "delhome", "homes", "renamehome", "home"};
        for (String cmd : cmds) getCommand(cmd).setExecutor(commands);

        // Get scheduler
        scheduler = Bukkit.getScheduler();

        // Create data directory
        File dataDirectory = getDataFolder();
        if (!dataDirectory.exists() && !dataDirectory.mkdirs()) {
            log.log(Level.SEVERE, "There was an error creating data directory (and parent directories).");
            return;
        }

        // Load JDBC drivers
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException ex) {
            ex.printStackTrace();
            log.log(Level.SEVERE, "There was an error loading the JDBC drivers. Error message: {0}", ex.getMessage());
            return;
        }

        // Get database connection
        try {
            conn = DriverManager.getConnection("jdbc:sqlite:" + new File(dataDirectory, "database.db").toPath().toAbsolutePath());
        } catch (SQLException ex) {
            ex.printStackTrace();
            log.log(Level.SEVERE, "There was an error creating a connection to the database. Error message: {0}", ex.getMessage());
            return;
        }

        // Create homes database
        try (Statement statement = conn.createStatement()) {
            statement.execute(
                "CREATE TABLE IF NOT EXISTS \"homes\" (" +
                "\"player\" TEXT NOT NULL COLLATE NOCASE, \"name\" TEXT NOT NULL COLLATE NOCASE, " + 
                "\"world\" TEXT NOT NULL COLLATE NOCASE, \"x\" REAL NOT NULL COLLATE BINARY, \"y\" REAL NOT NULL COLLATE BINARY, \"z\" REAL NOT NULL COLLATE BINARY, \"yaw\" REAL NOT NULL COLLATE BINARY, \"pitch\" REAL NOT NULL COLLATE BINARY, " +
                "UNIQUE(\"player\", \"name\")"
            );
        } catch (SQLException ex) {
            ex.printStackTrace();
            log.log(Level.SEVERE, "There was an error making sure the \"homes\" table exists. Error message: {0}", ex.getMessage());
        }
    }

    @Override
    public void onDisable() {
        log.log(Level.INFO, "Shutting down...");
    }
}