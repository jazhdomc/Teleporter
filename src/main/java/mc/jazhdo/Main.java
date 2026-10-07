package mc.jazhdo;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;

import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;

@SuppressWarnings("CallToPrintStackTrace")
public class Main extends JavaPlugin {
    private Logger log;
    private Connection conn;
    private BukkitScheduler scheduler;
    private FileConfiguration config;

    private class Commands implements CommandExecutor {
        private final HashMap<TPRequest, BukkitTask> requests = new HashMap<>();
        private final Main plugin;
        private enum RequestType {TPATO, TPAHERE}

        public Commands(Main plugin) {
            this.plugin = plugin;
        }

        private record TPRequest(String sender, String reciever, RequestType type) {
            public TPRequest(String sender, String reciever, RequestType type) {
                this.sender = sender;
                this.reciever = reciever;
                this.type = type;
            }
        }

        private void handleRequest(Player player, String sender, boolean accept) {
            sender = sender.toLowerCase();
            String playerName = player.getName().toLowerCase();
            boolean tpto = true;
            TPRequest request = new TPRequest(sender, playerName, RequestType.TPATO);
            BukkitTask removeTask = requests.get(request);
            if (removeTask == null) {
                tpto = false;
                request = new TPRequest(sender, playerName, RequestType.TPAHERE);
                removeTask = requests.get(request);
            }

            // Final null check
            if (removeTask == null) {
                sendError(player, "TP request not found.");
                return;
            }
            
            // Remove request to prevent 2nd handling
            if (removeTask != null && !removeTask.isCancelled()) removeTask.cancel();
            requests.remove(request);

            if (accept) {
                // Player null check
                Player fromPlayer, toPlayer, senderPlayer = Bukkit.getPlayer(sender);
                if (sender == null) {
                    sendError(player, "Player " + sender + " is no longer online. Teleportion failed.");
                    return;
                }

                // Switch up the order if needed
                if (tpto) {
                    fromPlayer = senderPlayer;
                    toPlayer = player;
                } else {
                    fromPlayer = player;
                    toPlayer = senderPlayer;
                }

                // Teleport
                sendInfo(fromPlayer, "Teleporting you to " + toPlayer.getName() + "...");
                sendInfo(toPlayer, "Teleporting " + fromPlayer.getName() + " to you...");
                fromPlayer.teleport(toPlayer);
            }
        }

        public void expireRequest(TPRequest request) {
            // Remove request from being able to be handled
            requests.remove(request);

            // Send expiration message
            String senderName = request.sender(), recieverName = request.reciever();
            Player sender = Bukkit.getPlayer(senderName), reciever = Bukkit.getPlayer(recieverName);
            boolean isNormalTpa = request.type() == RequestType.TPATO;
            if (sender != null) sendError(sender, "Your tp request sent " + (isNormalTpa ? "to" : "from") + " " + recieverName + " has expired.");
            if (reciever != null) sendError(reciever, "Your tp request sent " + (isNormalTpa ? "from" : "to") + " " + senderName + " has expired.");
        }

        private void makeRequest(Player player, String[] args, boolean tpato) {
            RequestType type = tpato ? RequestType.TPATO : RequestType.TPAHERE;

            // Get and verify the player teleporting to
            Player to = Bukkit.getPlayer(args[0]);
            if (to == null) {
                sendError(player, "Player " + args[0] + " was not found. You can only send tp requests to online players.");
                return;
            }

            // Prevent duplicates
            String playerName = player.getName(), lowerCasePlayerName = playerName.toLowerCase(), toName = to.getName(), lowerCaseToName = toName.toLowerCase();
            if (requests.containsKey(new TPRequest(lowerCasePlayerName, lowerCaseToName, type)) || requests.containsKey(new TPRequest(lowerCaseToName, lowerCasePlayerName, (tpato ? RequestType.TPAHERE : RequestType.TPATO)))) {
                sendError(player, "There is already a tp request for teleporting you to " + toName + ". Please wait for it to expire before requesting again.");
                return;
            }
            
            // Make the request and add it to the map
            TPRequest request = new TPRequest(lowerCasePlayerName, lowerCaseToName, type);
            requests.put(request, scheduler.runTaskLater(plugin, () -> expireRequest(request), 1200l));

            // Send notifications to both parties
            sendInfo(player, "Your tp request to " + toName + " has been sent. It expires in 60s.");

            TextComponent prefix = new TextComponent("[Teleporter] "), message = new TextComponent(playerName + " has requested " + (tpato ? "to tp to you" : "you to tp to them") + ". The request expires in 60s. Click to "),
            accept = new TextComponent("Accept"), acceptHoverText = new TextComponent("Click to accept"), or = new TextComponent(" or "), deny = new TextComponent("Deny"), denyHoverText = new TextComponent("Click to deny"), period = new TextComponent(".");
            prefix.setColor(net.md_5.bungee.api.ChatColor.GOLD);
            message.setColor(net.md_5.bungee.api.ChatColor.WHITE);
            acceptHoverText.setColor(net.md_5.bungee.api.ChatColor.GOLD);
            accept.setColor(net.md_5.bungee.api.ChatColor.GREEN);
            accept.setBold(true);
            accept.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new BaseComponent[] {acceptHoverText}));
            accept.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/tpaccept " + playerName));
            or.setColor(net.md_5.bungee.api.ChatColor.WHITE);
            or.setBold(false);
            denyHoverText.setColor(net.md_5.bungee.api.ChatColor.GOLD);
            deny.setColor(net.md_5.bungee.api.ChatColor.RED);
            deny.setBold(true);
            deny.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new BaseComponent[] {denyHoverText}));
            deny.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/tpdeny " + playerName));
            period.setColor(net.md_5.bungee.api.ChatColor.WHITE);
            period.setBold(false);
            to.spigot().sendMessage(new BaseComponent[] {prefix, message, accept, or, deny, period});
        }

        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (sender == null) return false;

            if (sender instanceof Player player) {
                if (command.getName().startsWith("tp") && args.length < 1) {
                    sendError(player, "<player_name> argument required. (/" + command.getName() + " <player_name>)");
                    return true;
                }

                switch (command.getName()) {
                    // TP Requesting
                    case "tpa" -> makeRequest(player, args, true);
                    case "tpahere" -> makeRequest(player, args, false);

                    // TP Request Handling
                    case "tpaccept" -> handleRequest(player, args[0], true);
                    case "tpdeny" -> handleRequest(player, args[0], false);

                    // // TP Request management
                    // case "tptoggle" -> {

                    // }
                    // case "tpon" -> {

                    // }
                    // case "tpoff" -> {

                    // }
                    // case "tpblock" -> {

                    // }
                    // case "tpunblock" -> {

                    // }
                    // // Random TP
                    // case "tpr" -> {

                    // }

                    // Homes
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
                                String build = ChatColor.GOLD + "=== Your Homes ===" + ChatColor.YELLOW;
                                while (result.next()) build += "\n- " + result.getString("name");
                                String output = build;
                                scheduler.runTask(plugin, () -> player.sendMessage(output));
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
                                        Location loc = new Location(Bukkit.getWorld(world), x, y, z, yaw, pitch);
                                        loc.getChunk().load();
                                        if (!player.teleport(loc)) sendInfo(player, ChatColor.RED + "Teleport unsuccessful.");
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
            player.sendMessage(ChatColor.GOLD + "[Teleporter] " + ChatColor.WHITE + msg);
        }
    }

    @Override
    public void onEnable() {
        log = getLogger();
        log.log(Level.INFO, "Starting...");

        // Initalize commands
        Commands commands = new Commands(this);
        String[] cmds = new String[]{"tpa", "tpahere", "tpaccept", "tpdeny", "sethome", "delhome", "homes", "renamehome", "home"};
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
                "UNIQUE(\"player\", \"name\"))"
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