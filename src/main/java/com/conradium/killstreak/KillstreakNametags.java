// src/main/java/com/conradium.killstreak/KillstreakNametags.java
package com.conradium.killstreak;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay; // Import TextDisplay
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent; // New import for crouching
import org.bukkit.scheduler.BukkitTask; // Import for managing tasks

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class KillstreakNametags extends JavaPlugin implements Listener, CommandExecutor {

    // Store player kill counts.
    private final Map<UUID, Integer> playerKillCounts = new HashMap<>();

    // Store the last time a player achieved their current tier, used for decay calculation.
    private final Map<UUID, Long> playerTierEntryTimes = new HashMap<>();

    // Store the UUID of the TextDisplay entity for each player
    private final Map<UUID, UUID> playerTextDisplays = new HashMap<>();

    // Reference to the repeating task for updating hologram positions
    private BukkitTask hologramUpdateTask;

    // Configuration values
    private int greenThreshold;
    private int yellowThreshold; // Kills >= yellowThreshold and < redThreshold
    private int redThreshold;    // Kills >= redThreshold

    private long redToYellowDurationTicks;   // Duration in ticks (20 ticks = 1 second)
    private long yellowToGreenDurationTicks; // Duration in ticks

    // Offset for text display entity above player's head - Increased
    private static final double TEXT_DISPLAY_Y_OFFSET = 2.7; // Adjusted upwards

    // --- Plugin Lifecycle Methods ---

    @Override
    public void onEnable() {
        getLogger().info("KillstreakNametags has been enabled!");

        // Save default config if it doesn't exist
        saveDefaultConfig();
        // Load configuration values
        loadPluginConfig();

        // Register event listener
        getServer().getPluginManager().registerEvents(this, this);

        // Register commands with a null check
        PluginCommand ksnameCommand = getCommand("ksname");
        if (ksnameCommand != null) {
            ksnameCommand.setExecutor(this);
            getLogger().info("Command '/ksname' registered successfully.");
        } else {
            getLogger().severe("Could not register command '/ksname'! Is it defined correctly in plugin.yml under the 'commands' section?");
        }

        // Start tasks
        startDecayTask();
        startTextDisplayUpdateTask();

        // On plugin enable, update text displays for any players already online
        for (Player player : Bukkit.getOnlinePlayers()) {
            // Only update/create if they are not sneaking when the plugin starts
            if (!player.isSneaking()) {
                updateTextDisplay(player);
            }
        }
    }

    @Override
    public void onDisable() {
        getLogger().info("KillstreakNametags has been disabled!");

        // Cancel the repeating task
        if (hologramUpdateTask != null) {
            hologramUpdateTask.cancel();
            getLogger().info("Cancelled Text Display update task.");
        }

        // Remove all active Text Displays when the plugin disables
        for (UUID textDisplayUuid : playerTextDisplays.values()) {
            org.bukkit.entity.Entity entity = Bukkit.getEntity(textDisplayUuid);
            if (entity != null) {
                entity.remove();
            }
        }
        playerTextDisplays.clear();
        getLogger().info("All active Text Displays removed.");
    }

    // --- Configuration Handling ---

    private void loadPluginConfig() {
        reloadConfig(); // Ensure latest config is loaded
        greenThreshold = getConfig().getInt("tiers.green-max-kills", 4); // Up to 4 kills is green
        yellowThreshold = getConfig().getInt("tiers.yellow-min-kills", 5); // 5-9 kills is yellow
        redThreshold = getConfig().getInt("tiers.red-min-kills", 10);     // 10+ kills is red

        // Convert minutes from config to ticks (1 minute = 1200 ticks)
        redToYellowDurationTicks = getConfig().getLong("decay.red-to-yellow-duration-minutes", 5) * 60 * 20;
        yellowToGreenDurationTicks = getConfig().getLong("decay.yellow-to-green-duration-minutes", 10) * 60 * 20;

        // Ensure thresholds are logical
        if (yellowThreshold <= greenThreshold) {
            getLogger().warning("Yellow min kills (" + yellowThreshold + ") should be greater than green max kills (" + greenThreshold + ")! Adjusting.");
            yellowThreshold = greenThreshold + 1;
        }
        if (redThreshold <= yellowThreshold) {
            getLogger().warning("Red min kills (" + redThreshold + ") should be greater than yellow min kills (" + yellowThreshold + ")! Adjusting.");
            redThreshold = yellowThreshold + 1;
        }

        getLogger().info("Loaded Config: Green max=" + greenThreshold + ", Yellow min=" + yellowThreshold + ", Red min=" + redThreshold);
        getLogger().info("Decay Durations: Red->Yellow=" + (redToYellowDurationTicks / 1200) + "m, Yellow->Green=" + (yellowToGreenDurationTicks / 1200) + "m");
    }

    /**
     * Determines the appropriate ChatColor based on the player's kill count.
     *
     * @param kills The player's kill count.
     * @return The ChatColor representing their tier.
     */
    private ChatColor getTierColor(int kills) {
        if (kills >= redThreshold) {
            return ChatColor.RED;
        } else if (kills >= yellowThreshold) {
            return ChatColor.YELLOW;
        } else {
            return ChatColor.GREEN;
        }
    }

    /**
     * Creates or updates a player's Text Display entity. If the player is sneaking,
     * it will remove any existing Text Display.
     *
     * @param player The player whose text display needs to be updated.
     */
    private void updateTextDisplay(Player player) {
        // If the player is sneaking, ensure the Text Display is removed and do not create it.
        if (player.isSneaking()) {
            removeTextDisplay(player);
            getLogger().info("Player " + player.getName() + " is sneaking. Text Display will not be shown.");
            return;
        }

        int kills = playerKillCounts.getOrDefault(player.getUniqueId(), 0);
        ChatColor color = getTierColor(kills);
        String displayText = color + "▼"; // The triangle with color

        // Get the player's location for the text display, adding an offset.
        Location displayLocation = player.getLocation().add(0, TEXT_DISPLAY_Y_OFFSET, 0);

        // Try to get the existing Text Display entity
        TextDisplay textDisplay = null;
        UUID textDisplayUuid = playerTextDisplays.get(player.getUniqueId());
        if (textDisplayUuid != null) {
            org.bukkit.entity.Entity entity = Bukkit.getEntity(textDisplayUuid);
            if (entity instanceof TextDisplay) {
                textDisplay = (TextDisplay) entity;
            } else {
                // Entity with this UUID exists but is not a TextDisplay, or is null. Remove old mapping.
                playerTextDisplays.remove(player.getUniqueId());
                if (entity != null) entity.remove(); // Clean up if it's a different entity type
            }
        }

        if (textDisplay == null) {
            // Create a new Text Display if it doesn't exist
            textDisplay = (TextDisplay) player.getWorld().spawnEntity(displayLocation, EntityType.TEXT_DISPLAY);
            playerTextDisplays.put(player.getUniqueId(), textDisplay.getUniqueId());
            getLogger().info("Created new Text Display for " + player.getName());
        }

        // Update the text display's properties
        textDisplay.setText(displayText);
        textDisplay.setBillboard(org.bukkit.entity.Display.Billboard.CENTER); // Always face the player
        textDisplay.setSeeThrough(false); // Make sure it's not transparent through other blocks
        textDisplay.setShadowed(true); // Add a small shadow for better visibility
        textDisplay.setGravity(false); // Prevent it from falling

        // Teleport to the current location (initial placement)
        textDisplay.teleport(displayLocation);

        getLogger().info("Updated Text Display for " + player.getName() + " with text: " + displayText);
    }

    /**
     * Removes a player's Text Display entity from the world.
     *
     * @param player The player whose text display to remove.
     */
    private void removeTextDisplay(Player player) {
        UUID textDisplayUuid = playerTextDisplays.remove(player.getUniqueId());
        if (textDisplayUuid != null) {
            org.bukkit.entity.Entity entity = Bukkit.getEntity(textDisplayUuid);
            if (entity != null) {
                entity.remove();
                getLogger().info("Removed Text Display for " + player.getName());
            }
        }
    }

    // --- Event Listeners ---

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        // Initialize kill count and tier entry time if player is new or not tracked
        playerKillCounts.putIfAbsent(player.getUniqueId(), 0);
        playerTierEntryTimes.putIfAbsent(player.getUniqueId(), System.currentTimeMillis());

        // Schedule Text Display creation and initial update shortly after join
        // Only if the player is NOT sneaking when they join
        if (!player.isSneaking()) {
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (player.isOnline() && !player.isSneaking()) { // Re-check if still online and not sneaking
                    updateTextDisplay(player);
                }
            }, 10L); // 10 ticks delay (0.5 seconds)
            getLogger().info("Scheduled Text Display creation/update for " + player.getName() + " on join (if not sneaking).");
        } else {
            getLogger().info(player.getName() + " joined while sneaking. Text Display will not be shown initially.");
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        // Remove the player's Text Display when they leave the server
        removeTextDisplay(event.getPlayer());
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        // Get the entity that died
        Player deadPlayer = event.getEntity();

        // Get the last damage cause
        EntityDamageEvent damageCause = deadPlayer.getLastDamageCause();

        // Check if the cause of death was an entity (e.g., another player)
        if (damageCause instanceof EntityDamageByEntityEvent) {
            EntityDamageByEntityEvent entityDamageByEntityEvent = (EntityDamageByEntityEvent) damageCause;
            // Get the damager
            org.bukkit.entity.Entity damager = entityDamageByEntityEvent.getDamager();

            // Check if the damager was a player
            if (damager instanceof Player killer) {
                // Now proceed with kill count logic
                UUID killerId = killer.getUniqueId();
                int currentKills = playerKillCounts.getOrDefault(killerId, 0);
                int newKills = currentKills + 1;
                playerKillCounts.put(killerId, newKills);

                // Update their tier entry time as they just got a kill, resetting decay
                playerTierEntryTimes.put(killerId, System.currentTimeMillis());

                // Update killer's Text Display
                updateTextDisplay(killer);
                getLogger().info(killer.getName() + " got a kill! New count: " + newKills);
            }
        }
    }

    @EventHandler
    public void onPlayerToggleSneak(PlayerToggleSneakEvent event) {
        Player player = event.getPlayer();
        if (event.isSneaking()) {
            // Player started sneaking, remove Text Display
            removeTextDisplay(player);
            getLogger().info(player.getName() + " started sneaking. Text Display removed.");
        } else {
            // Player stopped sneaking, re-create Text Display
            // Add a small delay to ensure player's un-sneak animation is complete
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (player.isOnline() && !player.isSneaking()) { // Re-check if still online and not sneaking
                    updateTextDisplay(player);
                }
            }, 5L); // 5 ticks delay
            getLogger().info(player.getName() + " stopped sneaking. Text Display will be re-created.");
        }
    }

    // --- Decay Task ---

    private void startDecayTask() {
        // Run every minute (1200 ticks)
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            getLogger().fine("Running nametag decay task...");
            long currentTime = System.currentTimeMillis();

            // Iterate through all online players to check for decay
            for (Player player : Bukkit.getOnlinePlayers()) {
                UUID playerId = player.getUniqueId();

                // Get current kills and the time they entered their current tier
                int currentKills = playerKillCounts.getOrDefault(playerId, 0);
                long tierEntryTime = playerTierEntryTimes.getOrDefault(playerId, currentTime); // Default to current time if not set

                // Determine the current visual tier based on current kills
                ChatColor currentVisualTierColor = getTierColor(currentKills);

                // Check for decay based on current visual tier and time spent in it
                if (currentVisualTierColor == ChatColor.RED) {
                    // Check if RED tier should decay to YELLOW
                    // (currentTime - tierEntryTime) is in milliseconds, divide by 50 to get ticks
                    if ((currentTime - tierEntryTime) / 50 > redToYellowDurationTicks) {
                        getLogger().info(player.getName() + "'s RED tier is decaying to YELLOW.");
                        playerKillCounts.put(playerId, yellowThreshold - 1); // Set to just below yellow threshold
                        playerTierEntryTimes.put(playerId, currentTime); // Reset decay timer for new tier
                        updateTextDisplay(player); // Update Text Display after decay
                    }
                } else if (currentVisualTierColor == ChatColor.YELLOW) {
                    // Check if YELLOW tier should decay to GREEN
                    if ((currentTime - tierEntryTime) / 50 > yellowToGreenDurationTicks) {
                        getLogger().info(player.getName() + "'s YELLOW tier is decaying to GREEN.");
                        playerKillCounts.put(playerId, greenThreshold - 1); // Set to just below green threshold
                        playerTierEntryTimes.put(playerId, currentTime); // Reset decay timer
                        updateTextDisplay(player); // Update Text Display after decay
                    }
                }
                // Green tier does not decay further
            }
        }, 20 * 60, 20 * 60); // Initial delay 1 minute, repeat every 1 minute
    }

    // --- Text Display Update Task (to make them follow players) ---
    private void startTextDisplayUpdateTask() {
        // Run very frequently (every 1 tick) for smooth following
        hologramUpdateTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                // Only update if the player is not sneaking
                if (!player.isSneaking()) {
                    UUID textDisplayUuid = playerTextDisplays.get(player.getUniqueId());
                    if (textDisplayUuid != null) {
                        org.bukkit.entity.Entity entity = Bukkit.getEntity(textDisplayUuid);
                        if (entity instanceof TextDisplay textDisplay) {
                            // Update Text Display location to follow the player
                            textDisplay.teleport(player.getLocation().add(0, TEXT_DISPLAY_Y_OFFSET, 0));
                        } else {
                            // If entity is not a TextDisplay or null, remove it from map to recreate on next update
                            playerTextDisplays.remove(player.getUniqueId());
                            if (entity != null) entity.remove();
                            getLogger().warning("Text Display for " + player.getName() + " was null or wrong type. Will attempt to recreate.");
                        }
                    } else {
                        // If no Text Display exists for a non-sneaking player, try to create one
                        updateTextDisplay(player);
                    }
                } else {
                    // If player is sneaking, ensure their Text Display is removed
                    removeTextDisplay(player);
                }
            }
        }, 0L, 1L); // Run every 1 tick (0.05 seconds) for smooth tracking
        getLogger().info("Started Text Display following task (1 tick frequency).");
    }

    // --- Command Executor ---

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("ksname")) {
            return false; // Not our command
        }

        if (args.length == 0) {
            sender.sendMessage(ChatColor.GOLD + "--- KillstreakNametags Plugin ---");
            sender.sendMessage(ChatColor.YELLOW + "/ksname version " + ChatColor.GRAY + "- Display plugin version.");
            sender.sendMessage(ChatColor.YELLOW + "/ksname reload " + ChatColor.GRAY + "- Reload the plugin configuration.");
            sender.sendMessage(ChatColor.YELLOW + "/ksname setkills <player> <kills> " + ChatColor.GRAY + "- Set a player's kill count.");
            return true;
        }

        String subCommand = args[0].toLowerCase();

        switch (subCommand) {
            case "version":
                sender.sendMessage(ChatColor.GOLD + "KillstreakNametags Version: " + ChatColor.AQUA + getDescription().getVersion());
                return true;

            case "reload":
                if (!sender.hasPermission("ksname.reload")) {
                    sender.sendMessage(ChatColor.RED + "You do not have permission to use this command.");
                    return true;
                }
                // Remove existing Text Displays before reloading config and recreating them
                for (UUID textDisplayUuid : playerTextDisplays.values()) {
                    org.bukkit.entity.Entity entity = Bukkit.getEntity(textDisplayUuid);
                    if (entity != null) {
                        entity.remove();
                    }
                }
                playerTextDisplays.clear();

                loadPluginConfig(); // Reload config
                // Recreate and reapply Text Displays to all online players after config reload
                for (Player p : Bukkit.getOnlinePlayers()) {
                    // Only update/create if they are not sneaking
                    if (!p.isSneaking()) {
                        updateTextDisplay(p);
                    }
                }
                sender.sendMessage(ChatColor.GREEN + "KillstreakNametags configuration reloaded and Text Displays updated for online players.");
                return true;

            case "setkills":
                if (!sender.hasPermission("ksname.setkills")) {
                    sender.sendMessage(ChatColor.RED + "You do not have permission to use this command.");
                    return true;
                }
                if (args.length < 3) {
                    sender.sendMessage(ChatColor.RED + "Usage: /ksname setkills <player> <kills>");
                    return true;
                }

                Player targetPlayer = Bukkit.getPlayer(args[1]);
                if (targetPlayer == null) {
                    sender.sendMessage(ChatColor.RED + "Player '" + args[1] + "' not found or is not online.");
                    return true;
                }

                int kills;
                try {
                    kills = Integer.parseInt(args[2]);
                    if (kills < 0) {
                        sender.sendMessage(ChatColor.RED + "Kill count cannot be negative.");
                        return true;
                    }
                } catch (NumberFormatException e) {
                    sender.sendMessage(ChatColor.RED + "Invalid kill count. Please enter a number.");
                    return true;
                }

                playerKillCounts.put(targetPlayer.getUniqueId(), kills);
                playerTierEntryTimes.put(targetPlayer.getUniqueId(), System.currentTimeMillis()); // Reset decay timer
                updateTextDisplay(targetPlayer); // Update Text Display after setting kills
                sender.sendMessage(ChatColor.GREEN + "Set " + targetPlayer.getName() + "'s kill count to " + kills + " and updated Text Display.");
                return true;

            default:
                sender.sendMessage(ChatColor.RED + "Unknown subcommand. Type /ksname for help.");
                return true;
        }
    }
}
