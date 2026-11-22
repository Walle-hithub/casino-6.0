package com.casino;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;

/** Caja de cambio: minerales <-> fichas de poker. */
public class Exchange implements Listener {

    private record Rate(int buy, int sell) {}

    private static class Holder implements InventoryHolder {
        Inventory inv;
        final List<Material> mats = new ArrayList<>();
        @Override public Inventory getInventory() { return inv; }
    }

    private final CasinoPlugin plugin;
    private final Map<Material, Rate> rates = new LinkedHashMap<>();

    public Exchange(CasinoPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        rates.clear();
        ConfigurationSection sec = plugin.getConfig().getConfigurationSection("exchange.rates");
        if (sec != null) {
            for (String key : sec.getKeys(false)) {
                Material m = Material.matchMaterial(key);
                ConfigurationSection s = sec.getConfigurationSection(key);
                if (m == null || s == null || rates.size() >= 14) continue;
                int buy = s.getInt("buy", 0);
                int sell = s.getInt("sell", buy);
                if (buy < 1 || sell < 1) continue;
                rates.put(m, new Rate(buy, sell));
            }
        }
        if (rates.isEmpty()) {
            rates.put(Material.DIAMOND, new Rate(100, 100));
            rates.put(Material.EMERALD, new Rate(50, 50));
            rates.put(Material.GOLD_INGOT, new Rate(10, 10));
            rates.put(Material.IRON_INGOT, new Rate(2, 2));
        }
    }

    public void open(Player p) {
        Holder h = new Holder();
        h.mats.addAll(rates.keySet());
        h.inv = Bukkit.createInventory(h, 27, plugin.color("&6&lCaja de fichas"));
        render(h, p);
        p.openInventory(h.inv);
    }

    private int slotOf(int index) { return index < 7 ? 10 + index : 19 + (index - 7); }

    private void render(Holder h, Player p) {
        Inventory inv = h.inv;
        ItemStack filler = named(Material.BLACK_STAINED_GLASS_PANE, " ", null);
        for (int i = 0; i < 27; i++) inv.setItem(i, filler);

        int chips = plugin.countChips(p);
        ItemStack chip = plugin.chipItem(1);
        ItemMeta cm = chip.getItemMeta();
        if (cm != null) {
            cm.setDisplayName(plugin.color("&eTus fichas: &6" + chips));
            cm.setLore(List.of(plugin.color("&7Clic izquierdo: minerales -> fichas"),
                    plugin.color("&7Clic derecho: fichas -> minerales")));
            chip.setItemMeta(cm);
        }
        inv.setItem(4, chip);

        for (int i = 0; i < h.mats.size(); i++) {
            Material m = h.mats.get(i);
            Rate r = rates.get(m);
            int have = countOre(p, m);
            int canBuy = chips / r.sell();
            List<String> lore = new ArrayList<>();
            lore.add("&7Cambiar 1: &6" + r.buy() + " fichas");
            lore.add("&7Cobrar 1: &6" + r.sell() + " fichas");
            lore.add(" ");
            lore.add("&eClic izq: &fcambiar 1 por fichas");
            lore.add("&eShift+clic izq: &fcambiar los " + have + " que tienes");
            lore.add("&eClic der: &fcobrar 1 (pagas " + r.sell() + " fichas)");
            lore.add("&eShift+clic der: &fcobrar " + canBuy + " con tus fichas");
            inv.setItem(slotOf(i), named(m, "&b" + plugin.pretty(m), lore));
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof Holder h)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        int slot = e.getRawSlot();
        if (slot < 0 || slot >= e.getView().getTopInventory().getSize()) return;

        int index = -1;
        for (int i = 0; i < h.mats.size(); i++) if (slotOf(i) == slot) index = i;
        if (index < 0) return;
        Material m = h.mats.get(index);

        ClickType click = e.getClick();
        switch (click) {
            case LEFT -> buy(p, m, 1);
            case SHIFT_LEFT -> buy(p, m, Integer.MAX_VALUE);
            case RIGHT -> sell(p, m, 1);
            case SHIFT_RIGHT -> sell(p, m, Integer.MAX_VALUE);
            default -> { return; }
        }
        render(h, p);
    }

    /** Mineral -> fichas */
    private void buy(Player p, Material m, int n) {
        Rate r = rates.get(m);
        int amount = Math.min(n, countOre(p, m));
        if (amount <= 0) {
            plugin.msg(p, "&cNo tienes &e" + plugin.pretty(m) + "&c para cambiar.");
            return;
        }
        Map<Integer, ItemStack> left = p.getInventory().removeItem(new ItemStack(m, amount));
        int notRemoved = 0;
        for (ItemStack s : left.values()) notRemoved += s.getAmount();
        amount -= notRemoved;
        if (amount <= 0) return;

        int chips = amount * r.buy();
        plugin.giveChips(p, chips);
        plugin.msg(p, "&aCambiaste &e" + amount + " " + plugin.pretty(m) + " &apor &e" + plugin.fmt(chips) + "&a.");
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.4f);
    }

    /** Fichas -> mineral */
    private void sell(Player p, Material m, int n) {
        Rate r = rates.get(m);
        int possible = plugin.countChips(p) / r.sell();
        int amount = Math.min(n, possible);
        if (amount <= 0) {
            plugin.msg(p, "&cNecesitas al menos &e" + plugin.fmt(r.sell()) + " &cpara cobrar 1 " + plugin.pretty(m) + ".");
            return;
        }
        if (!plugin.removeChips(p, amount * r.sell())) return;
        plugin.giveOre(p, m, amount);
        plugin.msg(p, "&aCobraste &e" + amount + " " + plugin.pretty(m) + " &apor &e" + plugin.fmt(amount * r.sell()) + "&a.");
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.0f);
    }

    private int countOre(Player p, Material m) {
        int total = 0;
        for (ItemStack it : p.getInventory().getStorageContents())
            if (it != null && it.getType() == m && !it.hasItemMeta()) total += it.getAmount();
        return total;
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof Holder) e.setCancelled(true);
    }

    private ItemStack named(Material m, String name, List<String> lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(plugin.color(name));
            if (lore != null) {
                List<String> l = new ArrayList<>();
                for (String s : lore) l.add(plugin.color(s));
                meta.setLore(l);
            }
            it.setItemMeta(meta);
        }
        return it;
    }
}
