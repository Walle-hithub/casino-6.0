package com.casino;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;
import java.util.function.IntConsumer;

/** Menu para que cada jugador elija cuantas fichas quiere apostar. */
public class BetMenu implements Listener {

    private final CasinoPlugin plugin;
    private final Map<UUID, Integer> last = new HashMap<>();

    private static class Holder implements InventoryHolder {
        Inventory inv;
        int amount;
        IntConsumer callback;
        boolean used;
        @Override public Inventory getInventory() { return inv; }
    }

    public BetMenu(CasinoPlugin plugin) { this.plugin = plugin; }

    public void open(Player p, String game, IntConsumer onConfirm) {
        Holder h = new Holder();
        h.amount = clamp(last.getOrDefault(p.getUniqueId(), plugin.minBet()));
        h.callback = onConfirm;
        h.inv = Bukkit.createInventory(h, 27, plugin.color("&6&lApuesta &8- &f" + game));
        render(h, p);
        p.openInventory(h.inv);
    }

    private int clamp(int v) { return Math.max(plugin.minBet(), Math.min(plugin.maxBet(), v)); }

    private void render(Holder h, Player p) {
        Inventory inv = h.inv;
        ItemStack filler = item(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 27; i++) inv.setItem(i, filler);

        inv.setItem(4, item(Material.PAPER, "&eTus fichas: &f" + plugin.countChips(p),
                "&7Minimo: &f" + plugin.minBet(), "&7Maximo: &f" + plugin.maxBet()));

        inv.setItem(9, item(Material.RED_CONCRETE, "&c-100"));
        inv.setItem(10, item(Material.RED_CONCRETE, "&c-10"));
        inv.setItem(11, item(Material.ORANGE_CONCRETE, "&6-1"));

        ItemStack chip = plugin.chipItem(Math.max(1, Math.min(64, h.amount)));
        ItemMeta cm = chip.getItemMeta();
        if (cm != null) {
            cm.setDisplayName(plugin.color("&6&lApuesta: &f" + plugin.fmt(h.amount)));
            cm.setLore(List.of(plugin.color("&7Usa los botones para cambiarla")));
            chip.setItemMeta(cm);
        }
        inv.setItem(13, chip);

        inv.setItem(15, item(Material.LIME_CONCRETE, "&a+1"));
        inv.setItem(16, item(Material.GREEN_CONCRETE, "&a+10"));
        inv.setItem(17, item(Material.GREEN_CONCRETE, "&a+100"));

        inv.setItem(20, item(Material.IRON_BLOCK, "&7Apuesta minima"));
        inv.setItem(22, item(Material.EMERALD_BLOCK, "&a&lAPOSTAR " + plugin.fmt(h.amount),
                "&7Clic para confirmar"));
        inv.setItem(24, item(Material.GOLD_BLOCK, "&6Apostar todo lo posible"));
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof Holder h)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        int slot = e.getRawSlot();
        if (slot < 0 || slot >= e.getView().getTopInventory().getSize()) return;

        switch (slot) {
            case 9 -> h.amount = clamp(h.amount - 100);
            case 10 -> h.amount = clamp(h.amount - 10);
            case 11 -> h.amount = clamp(h.amount - 1);
            case 15 -> h.amount = clamp(h.amount + 1);
            case 16 -> h.amount = clamp(h.amount + 10);
            case 17 -> h.amount = clamp(h.amount + 100);
            case 20 -> h.amount = plugin.minBet();
            case 24 -> h.amount = clamp(Math.max(plugin.minBet(), plugin.countChips(p)));
            case 22 -> { confirm(p, h); return; }
            default -> { return; }
        }
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.2f);
        render(h, p);
    }

    private void confirm(Player p, Holder h) {
        if (h.used) return;
        if (plugin.countChips(p) < h.amount) {
            plugin.msg(p, "&cNo tienes suficientes fichas. Cambia minerales en la caja del casino.");
            return;
        }
        h.used = true;
        last.put(p.getUniqueId(), h.amount);
        final int amount = h.amount;
        final IntConsumer cb = h.callback;
        // Un tick despues, para no tocar inventarios dentro del evento de clic
        Bukkit.getScheduler().runTask(plugin, () -> {
            p.closeInventory();
            cb.accept(amount);
        });
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof Holder) e.setCancelled(true);
    }

    private ItemStack item(Material m, String name, String... lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(plugin.color(name));
            if (lore.length > 0) {
                List<String> l = new ArrayList<>();
                for (String s : lore) l.add(plugin.color(s));
                meta.setLore(l);
            }
            it.setItemMeta(meta);
        }
        return it;
    }
}
