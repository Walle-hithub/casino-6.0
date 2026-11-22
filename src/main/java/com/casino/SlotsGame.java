package com.casino;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;

/** Logica de la tragaperras (simbolos con peso) y version en menu (comandos y carteles). */
public class SlotsGame implements Listener {

    private static final int[] REEL_SLOTS = {11, 13, 15};
    private static final int RESULT_SLOT = 22;

    private final CasinoPlugin plugin;
    private final List<Material> symbols = new ArrayList<>();
    private final Map<Material, Integer> weights = new HashMap<>();
    private final Map<Material, Double> triple = new HashMap<>();
    private final Map<Material, Double> pair = new HashMap<>();
    private final Set<UUID> playing = new HashSet<>();
    private int totalWeight;

    private static class SlotsHolder implements InventoryHolder {
        private Inventory inv;
        @Override public Inventory getInventory() { return inv; }
    }

    public SlotsGame(CasinoPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    private void addSymbol(Material m, int weight, double tr, double pr) {
        symbols.add(m);
        weights.put(m, weight);
        triple.put(m, tr);
        pair.put(m, pr);
        totalWeight += weight;
    }

    public void reload() {
        symbols.clear(); weights.clear(); triple.clear(); pair.clear();
        totalWeight = 0;
        ConfigurationSection sec = plugin.getConfig().getConfigurationSection("slots.symbols");
        if (sec != null) {
            for (String key : sec.getKeys(false)) {
                Material m = Material.matchMaterial(key);
                ConfigurationSection s = sec.getConfigurationSection(key);
                if (m == null || s == null) continue;
                int w = Math.max(1, s.getInt("weight", 1));
                addSymbol(m, w, s.getDouble("triple", 0), s.getDouble("pair", 0));
            }
        }
        if (symbols.size() < 2) {
            plugin.getLogger().warning("Config de slots.symbols invalida o antigua: usando valores por defecto. Borra config.yml para regenerarla.");
            symbols.clear(); weights.clear(); triple.clear(); pair.clear();
            totalWeight = 0;
            addSymbol(Material.DIAMOND, 1, 100, 3);
            addSymbol(Material.EMERALD, 2, 40, 2);
            addSymbol(Material.GOLD_INGOT, 3, 15, 1.5);
            addSymbol(Material.REDSTONE, 4, 8, 0);
            addSymbol(Material.COAL, 5, 6, 0);
        }
    }

    public List<Material> getSymbols() { return symbols; }

    /** Simbolo segun su probabilidad (para el resultado real). */
    public Material randomSymbol() {
        int r = plugin.getRandom().nextInt(totalWeight);
        for (Material m : symbols) {
            r -= weights.get(m);
            if (r < 0) return m;
        }
        return symbols.get(0);
    }

    /** Simbolo al azar sin pesos (solo para la animacion). */
    public Material randomVisual() {
        return symbols.get(plugin.getRandom().nextInt(symbols.size()));
    }

    public double computeMultiplier(Material[] r) {
        if (r[0] == r[1] && r[1] == r[2]) return triple.getOrDefault(r[0], 0.0);
        if (r[0] == r[1] || r[0] == r[2]) return pair.getOrDefault(r[0], 0.0);
        if (r[1] == r[2]) return pair.getOrDefault(r[1], 0.0);
        return 0;
    }

    public String trim(double d) {
        return d == Math.floor(d) ? String.valueOf((long) d) : String.valueOf(d);
    }

    public void play(Player p, CasinoPlugin.Bet bet) {
        if (!playing.add(p.getUniqueId())) {
            plugin.give(p.getUniqueId(), bet.amount());
            plugin.msg(p, "&cYa estas jugando.");
            return;
        }

        Material[] result = new Material[3];
        for (int i = 0; i < 3; i++) result[i] = randomSymbol();
        double multiplier = computeMultiplier(result);
        int prize = plugin.payout(bet.amount(), multiplier);

        SlotsHolder holder = new SlotsHolder();
        Inventory inv = Bukkit.createInventory(holder, 27, plugin.color("&6&lTragaperras"));
        holder.inv = inv;

        ItemStack filler = named(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 27; i++) inv.setItem(i, filler);
        inv.setItem(4, named(Material.HOPPER, plugin.color("&eApuesta: &f" + plugin.fmt(bet.amount()))));
        for (int s : REEL_SLOTS) inv.setItem(s, named(randomVisual(), " "));
        p.openInventory(inv);

        final int[] stopFrames = {10, 16, 22};
        final UUID id = p.getUniqueId();

        new BukkitRunnable() {
            int frame = 0;

            @Override
            public void run() {
                Player online = Bukkit.getPlayer(id);
                for (int i = 0; i < 3; i++) {
                    if (frame < stopFrames[i]) {
                        inv.setItem(REEL_SLOTS[i], named(randomVisual(), " "));
                    } else if (frame == stopFrames[i]) {
                        inv.setItem(REEL_SLOTS[i], named(result[i], plugin.color("&f" + plugin.pretty(result[i]))));
                        if (online != null) online.playSound(online.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 0.8f + i * 0.3f);
                    }
                }
                if (online != null && frame < stopFrames[2])
                    online.playSound(online.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.6f, 1.5f);

                frame++;
                if (frame > stopFrames[2]) {
                    cancel();
                    finish(id, inv, bet, prize, multiplier);
                }
            }
        }.runTaskTimer(plugin, 0L, 2L);
    }

    private void finish(UUID id, Inventory inv, CasinoPlugin.Bet bet, int prize, double multiplier) {
        Player p = Bukkit.getPlayer(id);
        if (prize > 0) {
            plugin.give(id, prize);
            inv.setItem(RESULT_SLOT, named(Material.LIME_STAINED_GLASS_PANE,
                    plugin.color("&a¡Premio: " + plugin.fmt(prize) + "! (x" + trim(multiplier) + ")")));
            if (p != null) {
                plugin.msg(p, "&a¡Tragaperras! Ganaste &e" + plugin.fmt(prize) + " &7(x" + trim(multiplier) + ")");
                p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
            }
        } else {
            inv.setItem(RESULT_SLOT, named(Material.RED_STAINED_GLASS_PANE, plugin.color("&cSin premio")));
            if (p != null) {
                plugin.msg(p, "&cNo hubo suerte. Perdiste &e" + plugin.fmt(bet.amount()) + "&c.");
                p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
            }
        }
        playing.remove(id);
    }

    private ItemStack named(Material m, String name) {
        ItemStack item = new ItemStack(m);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) { meta.setDisplayName(name); item.setItemMeta(meta); }
        return item;
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof SlotsHolder) e.setCancelled(true);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof SlotsHolder) e.setCancelled(true);
    }
}
