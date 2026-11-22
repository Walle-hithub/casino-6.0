package com.casino;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;

/**
 * Tabla de premios de la Maquina del Tesoro (items reales).
 * Cada premio tiene un valor (en fichas). Si no se indica "weight", la probabilidad
 * se calcula inversamente proporcional al valor: lo valioso sale menos.
 */
public class Treasure implements Listener {

    public record Reward(Material material, int amount, double value, double weight, Map<String, Integer> enchants) {}

    private static class PrizesHolder implements InventoryHolder {
        Inventory inv;
        @Override public Inventory getInventory() { return inv; }
    }

    private final CasinoPlugin plugin;
    private final List<Reward> rewards = new ArrayList<>();
    private double totalWeight;

    public Treasure(CasinoPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    private double num(Object o, double def) { return o instanceof Number n ? n.doubleValue() : def; }

    public void reload() {
        rewards.clear();
        totalWeight = 0;
        for (Map<?, ?> raw : plugin.getConfig().getMapList("treasure.rewards")) {
            Object itemObj = raw.get("item");
            Material mat = itemObj == null ? null : Material.matchMaterial(itemObj.toString());
            if (mat == null) {
                plugin.getLogger().warning("Premio con item invalido en treasure.rewards: " + itemObj);
                continue;
            }
            int amount = Math.max(1, (int) num(raw.get("amount"), 1));
            double value = num(raw.get("value"), 0);
            if (value <= 0) {
                plugin.getLogger().warning("Premio sin 'value' valido: " + mat);
                continue;
            }
            double weight = num(raw.get("weight"), 1000.0 / value);
            Map<String, Integer> enchants = new LinkedHashMap<>();
            if (raw.get("enchants") instanceof Map<?, ?> em) {
                for (Map.Entry<?, ?> en : em.entrySet())
                    enchants.put(en.getKey().toString().toLowerCase(), (int) num(en.getValue(), 1));
            }
            rewards.add(new Reward(mat, amount, value, weight, enchants));
            totalWeight += weight;
        }
        if (!rewards.isEmpty())
            plugin.getLogger().info(String.format("Maquina del Tesoro: %d premios, coste %d fichas, retorno medio %.1f%%",
                    rewards.size(), cost(), rtp()));
    }

    public boolean isEmpty() { return rewards.isEmpty(); }
    public int cost() { return Math.max(1, plugin.getConfig().getInt("treasure.cost", 65)); }
    public double broadcastValue() { return plugin.getConfig().getDouble("treasure.broadcast-value", 300); }

    public double chance(Reward r) { return r.weight() / totalWeight * 100.0; }

    public double averageValue() {
        double sum = 0;
        for (Reward r : rewards) sum += r.weight() * r.value();
        return sum / totalWeight;
    }

    public double rtp() { return averageValue() / cost() * 100.0; }

    public Reward pick() {
        double x = plugin.getRandom().nextDouble() * totalWeight;
        for (Reward r : rewards) {
            x -= r.weight();
            if (x < 0) return r;
        }
        return rewards.get(rewards.size() - 1);
    }

    /** Premio al azar sin pesos (solo para la animacion). */
    public ItemStack randomVisual() {
        return build(rewards.get(plugin.getRandom().nextInt(rewards.size())));
    }

    public ItemStack build(Reward r) {
        ItemStack it = new ItemStack(r.material(), r.amount());
        if (r.enchants().isEmpty()) return it;
        ItemMeta meta = it.getItemMeta();
        if (meta == null) return it;
        for (Map.Entry<String, Integer> en : r.enchants().entrySet()) {
            Enchantment e = Registry.ENCHANTMENT.get(NamespacedKey.minecraft(en.getKey()));
            if (e == null) {
                plugin.getLogger().warning("Encantamiento desconocido: " + en.getKey());
                continue;
            }
            if (meta instanceof EnchantmentStorageMeta esm) esm.addStoredEnchant(e, en.getValue(), true);
            else meta.addEnchant(e, en.getValue(), true);
        }
        it.setItemMeta(meta);
        return it;
    }

    public String label(Reward r) {
        StringBuilder sb = new StringBuilder();
        if (r.amount() > 1) sb.append(r.amount()).append("x ");
        sb.append(plugin.pretty(r.material()));
        if (!r.enchants().isEmpty()) {
            StringJoiner j = new StringJoiner(", ", " (", ")");
            for (Map.Entry<String, Integer> en : r.enchants().entrySet()) j.add(en.getKey().replace('_', ' ') + " " + en.getValue());
            sb.append(j);
        }
        return sb.toString();
    }

    public String trim(double d) { return d == Math.floor(d) ? String.valueOf((long) d) : String.valueOf(d); }

    // ---------- Menu con los premios y sus probabilidades ----------
    public void openPrizes(Player p) {
        if (rewards.isEmpty()) { plugin.msg(p, "&cNo hay premios configurados."); return; }
        PrizesHolder h = new PrizesHolder();
        h.inv = Bukkit.createInventory(h, 54, plugin.color("&d&lPremios del Tesoro"));

        ItemStack filler = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta fm = filler.getItemMeta();
        if (fm != null) { fm.setDisplayName(" "); filler.setItemMeta(fm); }
        for (int i = 45; i < 54; i++) h.inv.setItem(i, filler);

        List<Reward> sorted = new ArrayList<>(rewards);
        sorted.sort((a, b) -> Double.compare(b.value(), a.value()));
        int idx = 0;
        for (Reward r : sorted) {
            if (idx >= 45) break;
            ItemStack it = build(r);
            it.setAmount(Math.min(r.amount(), it.getMaxStackSize()));
            ItemMeta meta = it.getItemMeta();
            if (meta != null) {
                List<String> lore = new ArrayList<>();
                lore.add(plugin.color("&7Cantidad: &fx" + r.amount()));
                lore.add(plugin.color("&7Valor: &6" + trim(r.value()) + " fichas"));
                lore.add(plugin.color("&7Probabilidad: &a" + String.format("%.2f", chance(r)) + "%"));
                meta.setLore(lore);
                it.setItemMeta(meta);
            }
            h.inv.setItem(idx++, it);
        }

        ItemStack info = new ItemStack(Material.NETHER_STAR);
        ItemMeta im = info.getItemMeta();
        if (im != null) {
            im.setDisplayName(plugin.color("&e&lCada giro: &f" + plugin.fmt(cost())));
            im.setLore(List.of(
                    plugin.color("&7Valor medio del premio: &6" + String.format("%.1f", averageValue()) + " fichas"),
                    plugin.color("&7Retorno medio: &a" + String.format("%.1f", rtp()) + "%"),
                    plugin.color("&7Cuanto mas valioso, menos probable.")));
            info.setItemMeta(im);
        }
        h.inv.setItem(49, info);
        p.openInventory(h.inv);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof PrizesHolder) e.setCancelled(true);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof PrizesHolder) e.setCancelled(true);
    }
}
