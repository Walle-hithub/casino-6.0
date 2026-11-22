package com.casino;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/**
 * Carteles del casino. Escribe en un cartel (requiere casino.admin):
 *   linea 1: [Casino]
 *   linea 2: slots | moneda | dados | blackjack | cambio
 * Los jugadores hacen clic derecho y eligen su apuesta en un menu.
 */
public class Machines implements Listener {

    private static final int COIN_HEADS = 2;
    private static final int COIN_TAILS = 6;

    private final CasinoPlugin plugin;
    private final NamespacedKey gameKey;

    private static class CoinHolder implements InventoryHolder {
        Inventory inv;
        int amount;
        @Override public Inventory getInventory() { return inv; }
    }

    public Machines(CasinoPlugin plugin) {
        this.plugin = plugin;
        this.gameKey = new NamespacedKey(plugin, "game");
    }

    // ---------- Crear cartel ----------
    @EventHandler
    public void onSignChange(SignChangeEvent e) {
        if (e.getSide() != Side.FRONT) return;
        String first = e.getLine(0);
        if (first == null || !first.trim().equalsIgnoreCase("[casino]")) return;

        Player p = e.getPlayer();
        if (!p.hasPermission("casino.admin")) {
            plugin.msg(p, "&cNo tienes permiso para crear carteles del casino.");
            e.setCancelled(true);
            return;
        }
        String game = parseGame(e.getLine(1));
        if (game == null) {
            plugin.msg(p, "&cLinea 2 invalida. Usa: &eslots&c, &emoneda&c, &edados&c, &eblackjack &co &ecambio&c.");
            e.setCancelled(true);
            return;
        }

        e.setLine(0, plugin.color("&6&l[Casino]"));
        e.setLine(1, plugin.color(gameLabel(game)));
        e.setLine(2, plugin.color("&7Clic derecho"));
        e.setLine(3, plugin.color(game.equals("exchange") ? "&7para cambiar" : "&7para jugar"));

        final Block block = e.getBlock();
        final String g = game;
        // El estado del cartel solo se puede modificar un tick despues
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!(block.getState() instanceof Sign sign)) return;
            sign.getPersistentDataContainer().set(gameKey, PersistentDataType.STRING, g);
            sign.setWaxed(true); // evita que otros jugadores editen el cartel
            sign.update();
            plugin.msg(p, "&aCartel creado. Los jugadores solo tienen que hacer clic derecho.");
        });
    }

    private String parseGame(String raw) {
        if (raw == null) return null;
        switch (raw.trim().toLowerCase()) {
            case "slots": case "tragaperras": return "slots";
            case "moneda": case "coinflip": return "coinflip";
            case "dados": case "dice": return "dice";
            case "blackjack": case "bj": return "blackjack";
            case "cambio": case "caja": return "exchange";
            default: return null;
        }
    }

    private String gameLabel(String game) {
        switch (game) {
            case "slots": return "&eTragaperras";
            case "coinflip": return "&eCara o Cruz";
            case "dice": return "&eDados";
            case "blackjack": return "&eBlackjack";
            default: return "&eCambio de fichas";
        }
    }

    // ---------- Usar cartel ----------
    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block b = e.getClickedBlock();
        if (b == null || !Tag.SIGNS.isTagged(b.getType())) return;
        if (!(b.getState() instanceof Sign sign)) return;

        PersistentDataContainer pdc = sign.getPersistentDataContainer();
        String game = pdc.get(gameKey, PersistentDataType.STRING);
        if (game == null) return;

        e.setCancelled(true);
        Player p = e.getPlayer();
        if (!p.hasPermission("casino.play")) {
            plugin.msg(p, "&cNo tienes permiso para jugar.");
            return;
        }

        switch (game) {
            case "slots" -> plugin.getBetMenu().open(p, "Tragaperras", amt -> {
                CasinoPlugin.Bet bet = plugin.takeBet(p, amt);
                if (bet != null) plugin.getSlots().play(p, bet);
            });
            case "dice" -> plugin.getBetMenu().open(p, "Dados", amt -> {
                CasinoPlugin.Bet bet = plugin.takeBet(p, amt);
                if (bet != null) plugin.dice(p, bet);
            });
            case "blackjack" -> plugin.getBetMenu().open(p, "Blackjack", amt -> {
                CasinoPlugin.Bet bet = plugin.takeBet(p, amt);
                if (bet != null) plugin.getBlackjack().play(p, bet);
            });
            case "coinflip" -> plugin.getBetMenu().open(p, "Cara o Cruz", amt -> openCoinMenu(p, amt));
            case "exchange" -> plugin.getExchange().open(p);
            default -> { }
        }
    }

    private void openCoinMenu(Player p, int amount) {
        CoinHolder holder = new CoinHolder();
        holder.amount = amount;
        Inventory inv = Bukkit.createInventory(holder, 9, plugin.color("&6&lCara o Cruz"));
        holder.inv = inv;

        ItemStack filler = named(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 9; i++) inv.setItem(i, filler);
        inv.setItem(COIN_HEADS, named(Material.GOLD_NUGGET, plugin.color("&6&lCARA")));
        inv.setItem(COIN_TAILS, named(Material.IRON_NUGGET, plugin.color("&7&lCRUZ")));
        inv.setItem(4, named(Material.PAPER, plugin.color("&eApuesta: &f" + plugin.fmt(amount))));
        p.openInventory(inv);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof CoinHolder holder)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        int slot = e.getRawSlot();
        if (slot != COIN_HEADS && slot != COIN_TAILS) return;
        String side = slot == COIN_HEADS ? "cara" : "cruz";

        // Un tick despues para no tocar el inventario dentro del evento
        Bukkit.getScheduler().runTask(plugin, () -> {
            p.closeInventory();
            CasinoPlugin.Bet bet = plugin.takeBet(p, holder.amount);
            if (bet != null) plugin.coinflip(p, bet, side);
        });
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof CoinHolder) e.setCancelled(true);
    }

    // ---------- Proteccion ----------
    @EventHandler
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        if (!Tag.SIGNS.isTagged(b.getType())) return;
        if (!(b.getState() instanceof Sign sign)) return;
        if (!sign.getPersistentDataContainer().has(gameKey, PersistentDataType.STRING)) return;

        if (!e.getPlayer().hasPermission("casino.admin")) {
            e.setCancelled(true);
            plugin.msg(e.getPlayer(), "&cNo puedes romper los carteles del casino.");
        } else {
            plugin.msg(e.getPlayer(), "&7Cartel del casino eliminado.");
        }
    }

    private ItemStack named(Material m, String name) {
        ItemStack item = new ItemStack(m);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) { meta.setDisplayName(name); item.setItemMeta(meta); }
        return item;
    }
}
