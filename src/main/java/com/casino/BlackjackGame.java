package com.casino;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;

/**
 * Blackjack contra el crupier en un menu.
 * Reglas: baraja de 52 nueva en cada mano, el crupier se planta con 17,
 * Blackjack natural paga 3:2 (configurable), se puede doblar con las 2 primeras cartas.
 * Sin dividir ni seguro. Si cierras el menu a mitad de mano, te plantas automaticamente.
 */
public class BlackjackGame implements Listener {

    private static final String[] RANKS = {"A", "2", "3", "4", "5", "6", "7", "8", "9", "10", "J", "Q", "K"};
    private static final String[] SUITS = {"♠", "♥", "♦", "♣"};
    private static final int HIT_SLOT = 38;
    private static final int STAND_SLOT = 40;
    private static final int DOUBLE_SLOT = 42;

    private record Card(int rank, int suit) {      // rank 1..13
        int value() { return Math.min(rank, 10); }
        boolean red() { return suit == 1 || suit == 2; }
        String label() { return (red() ? "&c&l" : "&f&l") + RANKS[rank - 1] + SUITS[suit]; }
    }

    private enum Outcome { WIN, BLACKJACK, PUSH, LOSE }

    private static class Game implements InventoryHolder {
        UUID id;
        int bet;
        boolean busy, over, doubled;
        Inventory inv;
        final List<Card> player = new ArrayList<>();
        final List<Card> dealer = new ArrayList<>();
        final Deque<Card> deck = new ArrayDeque<>();
        @Override public Inventory getInventory() { return inv; }
    }

    private final CasinoPlugin plugin;
    private final Map<UUID, Game> games = new HashMap<>();

    public BlackjackGame(CasinoPlugin plugin) { this.plugin = plugin; }

    // ---------- Reglas ----------
    /** Devuelve {total, esBlando(0/1)}. */
    private int[] eval(List<Card> hand) {
        int sum = 0, aces = 0;
        for (Card c : hand) {
            sum += c.value();
            if (c.rank() == 1) aces++;
        }
        boolean soft = false;
        if (aces > 0 && sum + 10 <= 21) { sum += 10; soft = true; }
        return new int[]{sum, soft ? 1 : 0};
    }

    private boolean isBlackjack(List<Card> hand) {
        return hand.size() == 2 && eval(hand)[0] == 21;
    }

    // ---------- Inicio ----------
    public void play(Player p, CasinoPlugin.Bet bet) {
        UUID id = p.getUniqueId();
        if (games.containsKey(id)) {
            plugin.give(id, bet.amount());
            plugin.msg(p, "&cYa tienes una partida en curso.");
            return;
        }
        Game g = new Game();
        g.id = id;
        g.bet = bet.amount();

        List<Card> all = new ArrayList<>();
        for (int s = 0; s < 4; s++) for (int r = 1; r <= 13; r++) all.add(new Card(r, s));
        Collections.shuffle(all, plugin.getRandom());
        g.deck.addAll(all);
        g.player.add(g.deck.pop());
        g.dealer.add(g.deck.pop());
        g.player.add(g.deck.pop());
        g.dealer.add(g.deck.pop());

        g.inv = Bukkit.createInventory(g, 45, plugin.color("&6&lBlackjack &8- &f" + plugin.fmt(g.bet)));
        games.put(id, g);

        boolean pBJ = isBlackjack(g.player);
        boolean dBJ = isBlackjack(g.dealer);
        if (pBJ || dBJ) {
            g.busy = true;
            render(g, true);
            p.openInventory(g.inv);
            Outcome o = (pBJ && dBJ) ? Outcome.PUSH : (pBJ ? Outcome.BLACKJACK : Outcome.LOSE);
            Bukkit.getScheduler().runTaskLater(plugin, () -> finish(g, o), 30L);
            return;
        }
        render(g, false);
        p.openInventory(g.inv);
    }

    // ---------- Acciones ----------
    private void hit(Game g, Player p) {
        g.player.add(g.deck.pop());
        p.playSound(p.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 1f, 1f);
        int v = eval(g.player)[0];
        if (v > 21) {
            g.busy = true;
            render(g, true);
            finish(g, Outcome.LOSE);
        } else if (v == 21) {
            stand(g);
        } else {
            render(g, false);
        }
    }

    private void stand(Game g) {
        if (g.busy || g.over) return;
        g.busy = true;
        render(g, true);
        new BukkitRunnable() {
            @Override
            public void run() {
                int[] d = eval(g.dealer);
                boolean hitSoft = plugin.getConfig().getBoolean("blackjack.dealer-hits-soft-17", false);
                boolean draw = d[0] < 17 || (d[0] == 17 && d[1] == 1 && hitSoft);
                if (draw) {
                    g.dealer.add(g.deck.pop());
                    render(g, true);
                    Player on = Bukkit.getPlayer(g.id);
                    if (on != null) on.playSound(on.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 1f, 1f);
                } else {
                    cancel();
                    resolve(g);
                }
            }
        }.runTaskTimer(plugin, 12L, 14L);
    }

    private void doubleDown(Game g, Player p) {
        if (g.player.size() != 2 || g.doubled) {
            plugin.msg(p, "&cSolo puedes doblar con tus dos primeras cartas.");
            return;
        }
        if (!plugin.removeChips(p, g.bet)) {
            plugin.msg(p, "&cNo tienes suficientes fichas para doblar.");
            return;
        }
        g.bet *= 2;
        g.doubled = true;
        g.player.add(g.deck.pop());
        p.playSound(p.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 1f, 1f);
        if (eval(g.player)[0] > 21) {
            g.busy = true;
            render(g, true);
            finish(g, Outcome.LOSE);
        } else {
            stand(g);
        }
    }

    private void resolve(Game g) {
        int pv = eval(g.player)[0];
        int dv = eval(g.dealer)[0];
        Outcome o;
        if (dv > 21 || pv > dv) o = Outcome.WIN;
        else if (pv == dv) o = Outcome.PUSH;
        else o = Outcome.LOSE;
        finish(g, o);
    }

    private void finish(Game g, Outcome o) {
        if (g.over) return;
        g.over = true;
        g.busy = true;
        games.remove(g.id);

        int prize = switch (o) {
            case WIN -> g.bet * 2;
            case BLACKJACK -> plugin.payout(g.bet, plugin.getConfig().getDouble("blackjack.blackjack-multiplier", 2.5));
            case PUSH -> g.bet;
            case LOSE -> 0;
        };
        plugin.give(g.id, prize);
        render(g, true);

        int net = prize - g.bet;
        String title;
        Material mat;
        String chat;
        switch (o) {
            case BLACKJACK -> { title = "&6&l¡BLACKJACK! &a+" + net; mat = Material.GOLD_BLOCK;
                chat = "&6¡Blackjack! &aRecibes &e" + plugin.fmt(prize) + " &7(neto +" + net + ")"; }
            case WIN -> { title = "&a&l¡GANASTE! &a+" + net; mat = Material.EMERALD_BLOCK;
                chat = "&a¡Ganas! Recibes &e" + plugin.fmt(prize) + " &7(neto +" + net + ")"; }
            case PUSH -> { title = "&e&lEMPATE &7(recuperas la apuesta)"; mat = Material.IRON_BLOCK;
                chat = "&eEmpate. Recuperas &e" + plugin.fmt(prize) + "&e."; }
            default -> { title = "&c&lPERDISTE &c-" + g.bet; mat = Material.REDSTONE_BLOCK;
                chat = "&cPierdes &e" + plugin.fmt(g.bet) + "&c."; }
        }
        g.inv.setItem(HIT_SLOT, named(Material.BLACK_STAINED_GLASS_PANE, " ", null));
        g.inv.setItem(DOUBLE_SLOT, named(Material.BLACK_STAINED_GLASS_PANE, " ", null));
        g.inv.setItem(STAND_SLOT, named(mat, title, List.of("&7Clic para cerrar")));

        Player on = Bukkit.getPlayer(g.id);
        if (on != null) {
            plugin.msg(on, chat);
            on.playSound(on.getLocation(),
                    (o == Outcome.LOSE) ? Sound.ENTITY_VILLAGER_NO : Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
        }
    }

    // ---------- Pantalla ----------
    private void render(Game g, boolean reveal) {
        Inventory inv = g.inv;
        ItemStack filler = named(Material.BLACK_STAINED_GLASS_PANE, " ", null);
        for (int i = 0; i < 45; i++) inv.setItem(i, filler);

        // Crupier
        inv.setItem(0, named(Material.NAME_TAG, "&e&lCrupier", null));
        for (int i = 0; i < Math.min(7, g.dealer.size()); i++) {
            boolean hidden = !reveal && i == 1;
            inv.setItem(1 + i, hidden ? hiddenCard() : cardItem(g.dealer.get(i)));
        }
        if (reveal) {
            int[] d = eval(g.dealer);
            inv.setItem(8, named(Material.CLOCK, "&eTotal crupier: &f" + d[0] + (d[0] > 21 ? " &c(se pasa)" : ""), null));
        } else {
            int shown = eval(List.of(g.dealer.get(0)))[0];
            inv.setItem(8, named(Material.CLOCK, "&eCrupier muestra: &f" + shown, null));
        }

        // Jugador
        inv.setItem(18, named(Material.NAME_TAG, "&b&lTu mano", null));
        for (int i = 0; i < Math.min(7, g.player.size()); i++) inv.setItem(19 + i, cardItem(g.player.get(i)));
        int[] pv = eval(g.player);
        inv.setItem(26, named(Material.CLOCK, "&eTu total: &f" + pv[0] + (pv[0] > 21 ? " &c(te pasas)" : ""), null));

        // Apuesta
        ItemStack chip = plugin.chipItem(Math.max(1, Math.min(64, g.bet)));
        ItemMeta cm = chip.getItemMeta();
        if (cm != null) {
            cm.setDisplayName(plugin.color("&6Apuesta: &f" + plugin.fmt(g.bet) + (g.doubled ? " &7(doblada)" : "")));
            cm.setLore(null);
            chip.setItemMeta(cm);
        }
        inv.setItem(36, chip);

        // Botones
        if (!g.busy && !g.over) {
            inv.setItem(HIT_SLOT, named(Material.LIME_CONCRETE, "&a&lPEDIR carta", List.of("&7Recibes otra carta")));
            inv.setItem(STAND_SLOT, named(Material.RED_CONCRETE, "&c&lPLANTARSE", List.of("&7Juega el crupier")));
            if (g.player.size() == 2 && !g.doubled)
                inv.setItem(DOUBLE_SLOT, named(Material.GOLD_BLOCK, "&6&lDOBLAR",
                        List.of("&7Pagas otras " + plugin.fmt(g.bet) + ",", "&7recibes 1 carta y te plantas")));
        }
    }

    private ItemStack cardItem(Card c) {
        String val = c.rank() == 1 ? "1 u 11" : String.valueOf(c.value());
        return named(Material.PAPER, c.label(), List.of("&7Valor: " + val));
    }

    private ItemStack hiddenCard() {
        return named(Material.BOOK, "&7&l?", List.of("&7Carta oculta"));
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

    // ---------- Eventos ----------
    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof Game g)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (!p.getUniqueId().equals(g.id)) return;
        int slot = e.getRawSlot();

        if (g.over) {
            if (slot == STAND_SLOT) Bukkit.getScheduler().runTask(plugin, () -> p.closeInventory());
            return;
        }
        if (g.busy) return;

        switch (slot) {
            case HIT_SLOT -> hit(g, p);
            case STAND_SLOT -> stand(g);
            case DOUBLE_SLOT -> doubleDown(g, p);
            default -> { }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof Game) e.setCancelled(true);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getInventory().getHolder() instanceof Game g)) return;
        if (!g.over && !g.busy) Bukkit.getScheduler().runTask(plugin, () -> stand(g)); // cerrar = plantarse
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Game g = games.get(e.getPlayer().getUniqueId());
        if (g != null && !g.over && !g.busy) stand(g);
    }
}
