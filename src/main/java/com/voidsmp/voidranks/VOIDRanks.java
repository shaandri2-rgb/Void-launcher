package com.voidsmp.voidranks;

import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.plugin.java.JavaPlugin;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.io.File;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;

public final class VOIDRanks extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
    private final SecureRandom random = new SecureRandom();
    private final Map<UUID, Integer> grades = new HashMap<>();
    private final Map<UUID, String> hashes = new HashMap<>();
    private final Map<UUID, Long> sessions = new HashMap<>();
    private final Map<UUID, Integer> failures = new HashMap<>();
    private final Map<UUID, Long> lockedUntil = new HashMap<>();
    private final Map<UUID, PermissionAttachment> attachments = new HashMap<>();
    private final Map<UUID, SavedState> spectating = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadData();
        for (String name : List.of("alogin", "achange", "agrade", "makeadmin", "re", "vrank")) {
            PluginCommand command = getCommand(name);
            if (command == null) {
                getLogger().severe("Missing command in plugin.yml: " + name);
                getServer().getPluginManager().disablePlugin(this);
                return;
            }
            command.setExecutor(this);
            command.setTabCompleter(this);
        }
        getServer().getPluginManager().registerEvents(this, this);

        if (hashes.isEmpty() && getConfig().getString("bootstrap.hash") == null) {
            String firstKey = randomKey(5);
            getConfig().set("bootstrap.hash", hash(firstKey));
            saveConfig();
            getLogger().warning("==================================================");
            getLogger().warning("VOID RANKS FIRST OWNER KEY: " + firstKey);
            getLogger().warning("Give this key only to the server owner.");
            getLogger().warning("Anyone who claims it first becomes Owner. Keep console private.");
            getLogger().warning("==================================================");
        }

        for (Player player : Bukkit.getOnlinePlayers()) apply(player);
        getServer().getScheduler().runTaskTimer(this, this::expireSessions, 20L * 30, 20L * 30);
        getLogger().info("VOID Ranks enabled.");
    }

    @Override
    public void onDisable() {
        for (Player player : Bukkit.getOnlinePlayers()) stopSpectating(player, false);
        for (Map.Entry<UUID, PermissionAttachment> entry : attachments.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null) player.removeAttachment(entry.getValue());
        }
        attachments.clear();
        saveData();
    }

    private void loadData() {
        File file = new File(getDataFolder(), "data.yml");
        if (!file.exists()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = yaml.getConfigurationSection("players");
        if (section == null) return;
        for (String id : section.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(id);
                int grade = yaml.getInt("players." + id + ".grade", 0);
                if (grade < 0 || grade > 12) grade = 0;
                grades.put(uuid, grade);
                String storedHash = yaml.getString("players." + id + ".hash");
                if (storedHash != null && !storedHash.isBlank()) hashes.put(uuid, storedHash);
            } catch (IllegalArgumentException ignored) {
                getLogger().warning("Ignoring invalid player UUID in data.yml: " + id);
            }
        }
    }

    private void saveData() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<UUID, Integer> entry : grades.entrySet()) {
            String path = "players." + entry.getKey();
            yaml.set(path + ".grade", entry.getValue());
            if (hashes.containsKey(entry.getKey())) yaml.set(path + ".hash", hashes.get(entry.getKey()));
        }
        try {
            if (!getDataFolder().exists() && !getDataFolder().mkdirs()) throw new IllegalStateException("Cannot create plugin folder");
            yaml.save(new File(getDataFolder(), "data.yml"));
        } catch (Exception ex) {
            getLogger().severe("Could not save data.yml: " + ex.getMessage());
        }
    }

    private String randomKey(int length) {
        StringBuilder result = new StringBuilder(length);
        for (int i = 0; i < length; i++) result.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        return result.toString();
    }

    private String hash(String value) {
        try {
            byte[] salt = new byte[16];
            random.nextBytes(salt);
            PBEKeySpec spec = new PBEKeySpec(value.toCharArray(), salt, 210000, 256);
            byte[] derived = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            spec.clearPassword();
            return Base64.getEncoder().encodeToString(salt) + ":" + Base64.getEncoder().encodeToString(derived);
        } catch (Exception ex) {
            throw new IllegalStateException("Password hashing unavailable", ex);
        }
    }

    private boolean verify(String value, String stored) {
        try {
            String[] parts = stored.split(":", 2);
            if (parts.length != 2) return false;
            byte[] salt = Base64.getDecoder().decode(parts[0]);
            byte[] expected = Base64.getDecoder().decode(parts[1]);
            PBEKeySpec spec = new PBEKeySpec(value.toCharArray(), salt, 210000, 256);
            byte[] actual = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            spec.clearPassword();
            return MessageDigest.isEqual(actual, expected);
        } catch (Exception ex) {
            return false;
        }
    }

    private String color(String value) {
        return ChatColor.translateAlternateColorCodes('&', value == null ? "" : value);
    }

    private String message(String key) {
        return color(getConfig().getString("messages." + key, "&c" + key));
    }

    private int grade(Player player) {
        return grades.getOrDefault(player.getUniqueId(), 0);
    }

    private String gradeName(int grade) {
        return getConfig().getString("grades." + grade + ".name", "Grade " + grade);
    }

    private boolean loggedIn(Player player) {
        Long started = sessions.get(player.getUniqueId());
        long timeout = getConfig().getLong("settings.session-timeout-minutes", 60) * 60_000L;
        return started != null && System.currentTimeMillis() - started < timeout;
    }

    private boolean requireLogin(Player player) {
        if (!loggedIn(player)) {
            sessions.remove(player.getUniqueId());
            apply(player);
            player.sendMessage(message("not-logged-in"));
            return false;
        }
        return true;
    }

    private boolean requireGrade(Player player, int min, int max) {
        if (!requireLogin(player)) return false;
        int current = grade(player);
        if (current < min || current > max) {
            player.sendMessage(message("no-permission"));
            return false;
        }
        return true;
    }

    private void expireSessions() {
        long now = System.currentTimeMillis();
        long timeout = getConfig().getLong("settings.session-timeout-minutes", 60) * 60_000L;
        for (UUID uuid : new ArrayList<>(sessions.keySet())) {
            Long started = sessions.get(uuid);
            if (started == null || now - started >= timeout) {
                sessions.remove(uuid);
                Player player = Bukkit.getPlayer(uuid);
                if (player != null) {
                    apply(player);
                    player.sendMessage(message("session-expired"));
                }
            }
        }
    }

    private void apply(Player player) {
        PermissionAttachment previous = attachments.remove(player.getUniqueId());
        if (previous != null) player.removeAttachment(previous);

        // Staff permissions are attached ONLY after successful /alogin.
        if (grade(player) > 0 && loggedIn(player)) {
            PermissionAttachment attachment = player.addAttachment(this);
            ConfigurationSection section = getConfig().getConfigurationSection("grades." + grade(player));
            if (section != null) {
                for (String node : section.getStringList("permissions")) {
                    if (validPermission(node)) attachment.setPermission(node, true);
                }
            }
            attachments.put(player.getUniqueId(), attachment);
        }

        String prefix = getConfig().getString("grades." + grade(player) + ".prefix", "&7[Member]");
        player.setDisplayName(color(prefix + " &f" + player.getName()));
        player.setPlayerListName(player.getDisplayName());
    }

    private boolean validPermission(String node) {
        return node != null && !node.isBlank() && !node.equals("*") && !node.endsWith(".*")
                && node.matches("[a-zA-Z0-9_.-]+");
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        apply(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        stopSpectating(player, false);
        sessions.remove(player.getUniqueId());
        failures.remove(player.getUniqueId());
        lockedUntil.remove(player.getUniqueId());
        PermissionAttachment attachment = attachments.remove(player.getUniqueId());
        if (attachment != null) player.removeAttachment(attachment);
        // If the spectated target leaves, return every spectator safely.
        for (UUID watcherId : new ArrayList<>(spectating.keySet())) {
            Player watcher = Bukkit.getPlayer(watcherId);
            if (watcher != null && watcher.getGameMode() == GameMode.SPECTATOR) {
                // Keep the saved state; the watcher can use /re to return.
            }
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        if (name.equals("vrank")) return rankCommand(sender, args);
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command can only be used by a player.");
            return true;
        }

        UUID uuid = player.getUniqueId();
        switch (name) {
            case "alogin" -> {
                if (args.length != 1) {
                    player.sendMessage(color("&cUsage: /alogin <key>"));
                    return true;
                }
                long lock = lockedUntil.getOrDefault(uuid, 0L);
                if (lock > System.currentTimeMillis()) {
                    player.sendMessage(message("login-locked"));
                    return true;
                }
                String stored = hashes.get(uuid);
                boolean valid = stored != null && verify(args[0], stored);
                String bootstrap = getConfig().getString("bootstrap.hash");
                if (!valid && grade(player) == 0 && bootstrap != null && verify(args[0], bootstrap)) {
                    grades.put(uuid, 12);
                    hashes.put(uuid, hash(args[0]));
                    getConfig().set("bootstrap.hash", null);
                    saveConfig();
                    saveData();
                    valid = true;
                }
                if (!valid) {
                    int attempts = failures.merge(uuid, 1, Integer::sum);
                    int maximum = getConfig().getInt("settings.login-max-attempts", 5);
                    if (attempts >= maximum) {
                        lockedUntil.put(uuid, System.currentTimeMillis()
                                + getConfig().getLong("settings.login-lockout-seconds", 60) * 1000L);
                        failures.remove(uuid);
                    }
                    player.sendMessage(message("login-failed"));
                    return true;
                }
                sessions.put(uuid, System.currentTimeMillis());
                failures.remove(uuid);
                lockedUntil.remove(uuid);
                apply(player);
                player.sendMessage(color(message("login-success")
                        .replace("%grade%", String.valueOf(grade(player)))
                        .replace("%name%", gradeName(grade(player)))));
                if (grade(player) == 12 && bootstrap != null) {
                    player.sendMessage(color("&eYou claimed the initial Owner account. Change your key with /achange."));
                }
            }
            case "achange" -> {
                if (!requireLogin(player)) return true;
                if (args.length < 1) {
                    player.sendMessage(color("&cUsage: /achange <new-key>"));
                    return true;
                }
                String newKey = String.join(" ", args);
                if (newKey.length() > 128) {
                    player.sendMessage(color("&cKey is too long (maximum 128 characters)."));
                    return true;
                }
                hashes.put(uuid, hash(newKey));
                saveData();
                player.sendMessage(message("key-changed"));
            }
            case "agrade" -> {
                if (!requireGrade(player, 12, 12)) return true;
                if (args.length != 2) {
                    player.sendMessage(color("&cUsage: /agrade <player> <1-12>"));
                    return true;
                }
                Player target = Bukkit.getPlayerExact(args[0]);
                int requested;
                try { requested = Integer.parseInt(args[1]); }
                catch (NumberFormatException ex) {
                    player.sendMessage(color("&cGrade must be between 1 and 12."));
                    return true;
                }
                if (target == null || requested < 1 || requested > 12) {
                    player.sendMessage(color("&cTarget must be online and grade must be 1-12."));
                    return true;
                }
                if (target.equals(player) && requested != 12) {
                    player.sendMessage(color("&cYou cannot remove your own Owner rank."));
                    return true;
                }
                String temporaryKey = randomKey(5);
                grades.put(target.getUniqueId(), requested);
                hashes.put(target.getUniqueId(), hash(temporaryKey));
                sessions.remove(target.getUniqueId());
                saveData();
                apply(target);
                player.sendMessage(color("&aAssigned " + gradeName(requested) + " to " + target.getName()
                        + ". Temporary key: &e" + temporaryKey));
                target.sendMessage(color("&aYour rank is now " + gradeName(requested)
                        + ". Temporary key: &e" + temporaryKey + "&a. Use /alogin <key>, then /achange."));
            }
            case "makeadmin" -> {
                if (!requireGrade(player, 9, 10)) return true;
                if (args.length != 1) {
                    player.sendMessage(color("&cUsage: /makeadmin <player>"));
                    return true;
                }
                Player target = Bukkit.getPlayerExact(args[0]);
                if (target == null) {
                    player.sendMessage(color("&cPlayer must be online."));
                    return true;
                }
                if (grade(target) != 0 || hashes.containsKey(target.getUniqueId())) {
                    player.sendMessage(color("&cThat player already has a staff account or rank."));
                    return true;
                }
                String temporaryKey = randomKey(5);
                grades.put(target.getUniqueId(), 1);
                hashes.put(target.getUniqueId(), hash(temporaryKey));
                saveData();
                apply(target);
                player.sendMessage(color("&aCreated Helper account for " + target.getName()
                        + ". Temporary key: &e" + temporaryKey));
                target.sendMessage(color("&aYou are now Helper. Temporary key: &e" + temporaryKey
                        + "&a. Use /alogin <key>, then /achange."));
            }
            case "re" -> {
                if (!requireGrade(player, 1, 12)) return true;
                if (args.length == 0) {
                    stopSpectating(player, true);
                    return true;
                }
                Player target = Bukkit.getPlayerExact(args[0]);
                if (target == null || target.equals(player)) {
                    player.sendMessage(color("&cPlayer not found or invalid target."));
                    return true;
                }
                if (!spectating.containsKey(uuid)) {
                    spectating.put(uuid, new SavedState(player.getLocation().clone(), player.getGameMode(),
                            player.getAllowFlight(), player.isFlying()));
                }
                player.setGameMode(GameMode.SPECTATOR);
                player.teleport(target.getLocation());
                player.sendMessage(color(message("spectate-start").replace("%player%", target.getName())));
            }
        }
        return true;
    }

    private void stopSpectating(Player player, boolean notify) {
        SavedState state = spectating.remove(player.getUniqueId());
        if (state == null) {
            if (notify) player.sendMessage(color("&cYou are not spectating anyone."));
            return;
        }
        player.setGameMode(state.gameMode());
        player.setAllowFlight(state.allowFlight());
        if (state.allowFlight() && state.flying()) player.setFlying(true);
        player.teleport(state.location());
        if (notify) player.sendMessage(message("spectate-stop"));
    }

    private boolean rankCommand(CommandSender sender, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("list")) {
            sender.sendMessage(color("&6VOID SMP Ranks:"));
            for (int i = 0; i <= 12; i++) sender.sendMessage(color("&e" + i + ". &f" + gradeName(i)));
            return true;
        }
        if (args[0].equalsIgnoreCase("reload")) {
            if (sender instanceof Player player) {
                if (!requireGrade(player, 12, 12)) return true;
            } else if (!sender.hasPermission("voidranks.admin")) {
                sender.sendMessage("Only console or Owner can reload VOID Ranks.");
                return true;
            }
            reloadConfig();
            for (Player online : Bukkit.getOnlinePlayers()) apply(online);
            sender.sendMessage(color("&aVOID Ranks configuration reloaded."));
            return true;
        }
        if (args[0].equalsIgnoreCase("info") && args.length == 2) {
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage(color("&cPlayer must be online."));
                return true;
            }
            int current = grade(target);
            sender.sendMessage(color("&e" + target.getName() + " &7- &f" + current + " " + gradeName(current)));
            return true;
        }
        sender.sendMessage(color("&cUsage: /vrank <list|info <player>|reload>"));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        if (name.equals("agrade") && args.length == 2) return partial(List.of("1","2","3","4","5","6","7","8","9","10","11","12"), args[1]);
        if (name.equals("agrade") || name.equals("makeadmin") || name.equals("re") || (name.equals("vrank") && args.length == 2 && args[0].equalsIgnoreCase("info"))) {
            if (args.length == 1 || (name.equals("vrank") && args.length == 2)) {
                List<String> online = Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
                return partial(online, args[args.length - 1]);
            }
        }
        if (name.equals("vrank") && args.length == 1) return partial(List.of("list","info","reload"), args[0]);
        return Collections.emptyList();
    }

    private List<String> partial(List<String> choices, String input) {
        String lower = input.toLowerCase(Locale.ROOT);
        return choices.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(lower)).toList();
    }

    private record SavedState(Location location, GameMode gameMode, boolean allowFlight, boolean flying) {}
}