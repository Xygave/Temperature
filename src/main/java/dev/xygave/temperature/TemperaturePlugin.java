package dev.xygave.temperature;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class TemperaturePlugin extends JavaPlugin implements Listener {
    private static final class State {
        String biome = "";
        int coldSeconds = 0, heatSeconds = 0, displaySeconds = 0, reminderSeconds = 0;
        long coldProtectedUntil = 0, heatProtectedUntil = 0;
        boolean ownsSlow = false, ownsFrost = false;
    }
    private final Map<UUID, State> states = new HashMap<>();
    private int clock = 0;

    @Override public void onEnable() {
        saveDefaultConfig();
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().runTaskTimer(this, this::tick, 20L, 20L);
        getLogger().info("Temperature enabled");
    }

    @Override public void onDisable() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            State s = states.get(p.getUniqueId());
            if (s != null) clearOwnEffects(p, s);
        }
        states.clear();
    }

    @EventHandler public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        State s = new State();
        s.biome = biome(p);
        if (getConfig().getBoolean("display.show-on-join", true)) s.displaySeconds = displayDuration();
        states.put(p.getUniqueId(), s);
    }

    @EventHandler public void onQuit(PlayerQuitEvent event) {
        State s = states.remove(event.getPlayer().getUniqueId());
        if (s != null) clearOwnEffects(event.getPlayer(), s);
    }

    @EventHandler public void onConsume(PlayerItemConsumeEvent event) {
        Material m = event.getItem().getType();
        Player p = event.getPlayer();
        State s = states.computeIfAbsent(p.getUniqueId(), id -> new State());
        long now = System.currentTimeMillis();
        // Любое ванильное рагу: грибное, кроличье, свекольное, подозрительное.
        if (m == Material.MUSHROOM_STEW || m == Material.RABBIT_STEW
                || m == Material.BEETROOT_SOUP || m == Material.SUSPICIOUS_STEW) {
            s.coldProtectedUntil = now + seconds("cold.stew-immunity-seconds", 180) * 1000L;
            s.coldSeconds = 0;
            clearCold(p, s);
        }
        // Все обычные и необычные зелья, включая бутылку воды, имеют тип POTION.
        if (m == Material.POTION) {
            s.heatProtectedUntil = now + seconds("heat.drink-immunity-seconds", 180) * 1000L;
            s.heatSeconds = 0;
            clearHeat(p, s);
        }
    }

    private void tick() {
        clock++;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!p.isOnline()) continue;
            State s = states.computeIfAbsent(p.getUniqueId(), id -> new State());
            if (s.biome.isEmpty()) s.biome = biome(p);
            String current = biome(p);
            if (!current.equals(s.biome)) {
                s.biome = current;
                s.coldSeconds = s.heatSeconds = 0;
                clearOwnEffects(p, s);
                if (getConfig().getBoolean("display.show-on-biome-change", true)) s.displaySeconds = displayDuration();
            }
            int reminder = seconds("display.reminder-seconds", 300);
            if (++s.reminderSeconds >= reminder) {
                s.reminderSeconds = 0;
                s.displaySeconds = displayDuration();
            }
            if (s.displaySeconds > 0) {
                String temp = String.valueOf(temperature(p, current));
                p.sendActionBar(Component.text("Температура: " + temp + "°C | " + pretty(current)));
                s.displaySeconds--;
            }
            // Креатив, наблюдатель и мертвые игроки не испытывают воздействие погоды.
            if (p.isDead() || p.getGameMode() == org.bukkit.GameMode.CREATIVE
                    || p.getGameMode() == org.bukkit.GameMode.SPECTATOR) {
                s.coldSeconds = s.heatSeconds = 0;
                clearOwnEffects(p, s);
                continue;
            }
            boolean night = isNight(p.getWorld());
            String type = getConfig().getString("biomes." + current + ".type", "normal");
            boolean coldBiome = p.getWorld().getEnvironment() == World.Environment.NORMAL && type.equalsIgnoreCase("cold");
            boolean hotBiome = p.getWorld().getEnvironment() == World.Environment.NORMAL && type.equalsIgnoreCase("hot") && !night;
            boolean nether = p.getWorld().getEnvironment() == World.Environment.NETHER;
            // Проверяем окружающие блоки только раз в 2 секунды; результат нужен каждый тик.
            boolean coldSource = false, hotSource = false;
            if (clock % 2 == 0 || !cached.containsKey(p.getUniqueId())) {
                SourceResult result = scanSources(p);
                cached.put(p.getUniqueId(), result);
            }
            SourceResult nearby = cached.get(p.getUniqueId());
            if (nearby != null) { coldSource = nearby.warm; hotSource = nearby.hot; }
            if (p.isInWater()) {
                s.heatProtectedUntil = System.currentTimeMillis() + seconds("heat.bathing-immunity-seconds", 30) * 1000L;
                s.heatSeconds = 0;
            }
            boolean protectedCold = System.currentTimeMillis() < s.coldProtectedUntil || fullLeather(p) || coldSource;
            if (coldBiome && !protectedCold) {
                s.coldSeconds++;
                int warn = seconds("cold.warning-" + (night ? "night" : "day") + "-seconds", night ? 30 : 60);
                int slow = seconds("cold.slow-" + (night ? "night" : "day") + "-seconds", night ? 60 : 120);
                int damage = seconds("cold.damage-" + (night ? "night" : "day") + "-seconds", night ? 90 : 180);
                double factor = 1.0 + (Math.max(1.0, getConfig().getDouble("cold.full-netherite-time-multiplier", 5.0)) - 1.0) * netheriteCount(p) / 4.0;
                if (s.coldSeconds >= Math.round(warn * factor)) setFrost(p, s);
                else clearFrost(p, s);
                if (s.coldSeconds >= Math.round(slow * factor)) applySlow(p, s);
                else clearSlow(p, s);
                if (s.coldSeconds >= Math.round(damage * factor)) {
                    int period = seconds("cold.damage-every-seconds", 5);
                    if (clock % period == 0) p.damage(Math.max(0, getConfig().getDouble("cold.damage-amount", 1.0)));
                }
            } else {
                s.coldSeconds = 0;
                clearCold(p, s);
            }
            boolean hot = nether || hotBiome || (p.getWorld().getEnvironment() == World.Environment.NORMAL && hotSource);
            boolean protectedHeat = System.currentTimeMillis() < s.heatProtectedUntil;
            if (hot && !protectedHeat) {
                s.heatSeconds++;
                int delay = nether ? seconds("heat.nether-delay-seconds", 60)
                        : hotSource ? seconds("heat.source-delay-seconds", 180)
                        : seconds("heat.desert-delay-seconds", 180);
                if (s.heatSeconds >= delay) applySlow(p, s);
            } else {
                s.heatSeconds = 0;
                if (s.coldSeconds == 0) clearSlow(p, s);
            }
        }
        cached.keySet().retainAll(states.keySet());
    }

    private record SourceResult(boolean warm, boolean hot) {}
    private final Map<UUID, SourceResult> cached = new HashMap<>();

    private SourceResult scanSources(Player p) {
        int coldRadius = Math.max(0, Math.min(8, getConfig().getInt("cold.heat-source-radius", 4)));
        int torchRadius = Math.max(0, Math.min(8, getConfig().getInt("cold.torch-radius", 2)));
        int hotRadius = Math.max(0, Math.min(8, getConfig().getInt("heat.source-radius", 3)));
        int radius = Math.max(Math.max(coldRadius, torchRadius), hotRadius);
        Block origin = p.getLocation().getBlock();
        boolean warm = false, hot = false;
        int r2cold = coldRadius * coldRadius, r2torch = torchRadius * torchRadius, r2hot = hotRadius * hotRadius;
        for (int x = -radius; x <= radius; x++) for (int y = -radius; y <= radius; y++) for (int z = -radius; z <= radius; z++) {
            int d2 = x*x + y*y + z*z;
            if (d2 > radius * radius) continue;
            Material m = origin.getRelative(x, y, z).getType();
            boolean fire = m == Material.FIRE || m == Material.SOUL_FIRE;
            boolean lava = m == Material.LAVA;
            boolean torch = m == Material.TORCH || m == Material.WALL_TORCH
                    || m == Material.SOUL_TORCH || m == Material.SOUL_WALL_TORCH;
            boolean campfire = m == Material.CAMPFIRE || m == Material.SOUL_CAMPFIRE;
            if ((fire || lava || campfire) && d2 <= r2cold || torch && d2 <= r2torch) warm = true;
            if ((fire || lava) && d2 <= r2hot) hot = true;
            if (warm && hot) return new SourceResult(true, true);
        }
        return new SourceResult(warm, hot);
    }

    private void setFrost(Player p, State s) {
        if (p.getLocation().getBlock().getType() == Material.POWDER_SNOW) return;
        int max = p.getMaxFreezeTicks();
        int target = Math.max(1, Math.min(max - 25, (int) (max * Math.max(0.1, Math.min(0.9, getConfig().getDouble("cold.frost-screen-strength", 0.75))))));
        if (max > 30 && p.getFreezeTicks() < target) { p.setFreezeTicks(target); s.ownsFrost = true; }
    }
    private void clearFrost(Player p, State s) {
        if (s.ownsFrost && p.getLocation().getBlock().getType() != Material.POWDER_SNOW) p.setFreezeTicks(0);
        s.ownsFrost = false;
    }
    private void applySlow(Player p, State s) {
        PotionEffect existing = p.getPotionEffect(PotionEffectType.SLOWNESS);
        // Не трогаем чужой более сильный/длительный эффект.
        if (existing != null && !s.ownsSlow && (existing.getAmplifier() > 0 || existing.getDuration() > 60)) return;
        p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 45, 0, true, false, false));
        s.ownsSlow = true;
    }
    private void clearSlow(Player p, State s) {
        if (s.ownsSlow) {
            PotionEffect effect = p.getPotionEffect(PotionEffectType.SLOWNESS);
            if (effect != null && effect.getAmplifier() == 0 && effect.getDuration() <= 45) p.removePotionEffect(PotionEffectType.SLOWNESS);
        }
        s.ownsSlow = false;
    }
    private void clearCold(Player p, State s) { clearFrost(p, s); if (s.heatSeconds == 0) clearSlow(p, s); }
    private void clearHeat(Player p, State s) { if (s.coldSeconds == 0) clearSlow(p, s); }
    private void clearOwnEffects(Player p, State s) { clearFrost(p, s); clearSlow(p, s); }

    private boolean fullLeather(Player p) {
        if (!getConfig().getBoolean("cold.full-leather-immunity", true)) return false;
        ItemStack[] a = p.getInventory().getArmorContents();
        return a[0] != null && a[0].getType() == Material.LEATHER_BOOTS
            && a[1] != null && a[1].getType() == Material.LEATHER_LEGGINGS
            && a[2] != null && a[2].getType() == Material.LEATHER_CHESTPLATE
            && a[3] != null && a[3].getType() == Material.LEATHER_HELMET;
    }
    private int netheriteCount(Player p) {
        int count = 0;
        for (ItemStack a : p.getInventory().getArmorContents()) if (a != null && a.getType().name().startsWith("NETHERITE_")) count++;
        return count;
    }
    private String biome(Player p) { return p.getLocation().getBlock().getBiome().getKey().getKey().toUpperCase(Locale.ROOT); }
    private boolean isNight(World w) { long time = w.getTime(); return time >= 12000 && time < 24000; }
    private int temperature(Player p, String biome) {
        World.Environment e = p.getWorld().getEnvironment();
        if (e == World.Environment.NETHER) return getConfig().getInt("biomes.nether-temperature", 55);
        if (e == World.Environment.THE_END) return getConfig().getInt("biomes.end-temperature", 15);
        String time = isNight(p.getWorld()) ? "night" : "day";
        return getConfig().getInt("biomes." + biome + "." + time + "", getConfig().getInt("biomes.default-" + time + "-temperature", 20));
    }
    private String pretty(String biome) { return biome.toLowerCase(Locale.ROOT).replace('_', ' '); }
    private int seconds(String path, int fallback) { return Math.max(1, getConfig().getInt(path, fallback)); }
    private int displayDuration() { return seconds("display.duration-seconds", 5); }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("temperature.admin")) { sender.sendMessage("§cНет прав."); return true; }
            reloadConfig(); cached.clear(); sender.sendMessage("§aTemperature: настройки перезагружены."); return true;
        }
        if (!(sender instanceof Player p)) { sender.sendMessage("Использование: /temperature reload"); return true; }
        State s = states.get(p.getUniqueId());
        sender.sendMessage("§bБиом: §f" + biome(p) + " §bТемпература: §f" + temperature(p, biome(p)) + "°C");
        if (s != null) sender.sendMessage("§7Холод: " + s.coldSeconds + " с | Жара: " + s.heatSeconds + " с");
        return true;
    }
}
