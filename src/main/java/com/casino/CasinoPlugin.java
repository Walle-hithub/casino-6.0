package com.casino;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

public class CasinoPlugin extends JavaPlugin implements CommandExecutor, TabCompleter, Listener {

    /** Apuesta ya cobrada, en fichas. */
    public record Bet(int amount) {}

    private final Random random = new Random();
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private NamespacedKey chipKey;
    private SlotsGame slots;
    private BetMenu betMenu;
    private BlackjackGame blackjack;
    private Exchange exchange;
    private Treasure treasure;
    private MachineManager machineManager;
    private File pendingFile;
    private YamlConfiguration pending;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        chipKey = new NamespacedKey(this, "chip");
        pendingFile = new File(getDataFolder(), "pending.yml");
        pending = YamlConfiguration.loadConfiguration(pendingFile);

        slots = new SlotsGame(this);
        betMenu = new BetMenu(this);
        blackjack = new BlackjackGame(this);
        exchange = new Exchange(this);
        treasure = new Treasure(this);

        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(slots, this);
        getServer().getPluginManager().registerEvents(betMenu, this);
        getServer().getPluginManager().registerEvents(blackjack, this);
        getServer().getPluginManager().registerEvents(exchange, this);
        getServer().getPluginManager().registerEvents(treasure, this);
        getServer().getPluginManager().registerEvents(new Machines(this), this);
        machineManager = new MachineManager(this);
        getServer().getPluginManager().registerEvents(machineManager, this);

        getCommand("casino").setExecutor(this);
        getCommand("casino").setTabCompleter(this);
    }

    // ---------- Getters ----------
    public Random getRandom() { return random; }
    public SlotsGame getSlots() { return slots; }
    public BetMenu getBetMenu() { return betMenu; }
    public BlackjackGame getBlackjack() { return blackjack; }
    public Exchange getExchange() { return exchange; }
    public Treasure getTreasure() { return treasure; }

    // ---------- Utilidades de texto ----------
    public String color(String s) { return ChatColor.translateAlternateColorCodes('&', s); }

    public void msg(Player p, String text) {
        p.sendMessage(color(getConfig().getString("prefix", "&6[Casino] &f")) + color(text));
    }

    public String fmt(int n) { return n + (n == 1 ? " ficha" : " fichas"); }

    public String pretty(Material m) {
        String n = m.name().toLowerCase().replace('_', ' ');
        return Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }

    public int minBet() { return Math.max(1, getConfig().getInt("bet.min", 1)); }
    public int maxBet() { return Math.max(minBet(), getConfig().getInt("bet.max", 500)); }

    /** Premio = apuesta * multiplicador; los decimales se redondean al azar para no alterar el retorno medio. */
    public int payout(int bet, double multiplier) {
        double raw = bet * multiplier;
        int base = (int) Math.floor(raw);
        double frac = raw - base;
        if (frac > 0 && random.nextDouble() < frac) base++;
        return base;
    }

    // ---------- Fichas (item) ----------
    private Material chipMaterial() {
        Material m = Material.matchMaterial(getConfig().getString("chips.material", "GOLD_NUGGET"));
        return m == null ? Material.GOLD_NUGGET : m;
    }

    public ItemStack chipItem(int amount) {
        ItemStack it = new ItemStack(chipMaterial(), amount);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(color(getConfig().getString("chips.name", "&6&lFicha de Poker")));
            List<String> lore = new ArrayList<>();
            for (String l : getConfig().getStringList("chips.lore")) lore.add(color(l));
            meta.setLore(lore);
            meta.getPersistentDataContainer().set(chipKey, PersistentDataType.BYTE, (byte) 1);
            it.setItemMeta(meta);
        }
        return it;
    }

    public boolean isChip(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return false;
        ItemMeta meta = it.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(chipKey, PersistentDataType.BYTE);
    }

    public int countChips(Player p) {
        int total = 0;
        for (ItemStack it : p.getInventory().getStorageContents())
            if (isChip(it)) total += it.getAmount();
        return total;
    }

    public boolean removeChips(Player p, int amount) {
        if (amount <= 0) return true;
        if (countChips(p) < amount) return false;
        ItemStack[] contents = p.getInventory().getStorageContents();
        int remaining = amount;
        for (int i = 0; i < contents.length && remaining > 0; i++) {
            ItemStack it = contents[i];
            if (!isChip(it)) continue;
            int take = Math.min(remaining, it.getAmount());
            if (take == it.getAmount()) contents[i] = null;
            else it.setAmount(it.getAmount() - take);
            remaining -= take;
        }
        p.getInventory().setStorageContents(contents);
        return true;
    }

    public void giveChips(Player p, int amount) {
        giveItem(p, amount, true, null);
    }

    public void giveOre(Player p, Material m, int amount) {
        giveItem(p, amount, false, m);
    }

    private void giveItem(Player p, int amount, boolean chips, Material m) {
        int remaining = amount;
        int stackMax = chips ? chipMaterial().getMaxStackSize() : m.getMaxStackSize();
        while (remaining > 0) {
            int n = Math.min(remaining, stackMax);
            ItemStack stack = chips ? chipItem(n) : new ItemStack(m, n);
            Map<Integer, ItemStack> left = p.getInventory().addItem(stack);
            for (ItemStack s : left.values()) p.getWorld().dropItemNaturally(p.getLocation(), s);
            remaining -= n;
        }
    }

    /** Entrega fichas; si el jugador esta desconectado se guardan hasta que entre. */
    public void give(UUID id, int amount) {
        if (amount <= 0) return;
        Player p = Bukkit.getPlayer(id);
        if (p != null) {
            giveChips(p, amount);
        } else {
            String path = id + ".chips";
            pending.set(path, pending.getInt(path, 0) + amount);
            savePending();
        }
    }

    private void savePending() {
        try { pending.save(pendingFile); }
        catch (IOException e) { getLogger().severe("No se pudo guardar pending.yml: " + e.getMessage()); }
    }

    /** Entrega un item (premio); si el jugador esta desconectado se guarda hasta que entre. */
    public void giveStack(UUID id, ItemStack stack) {
        Player p = Bukkit.getPlayer(id);
        if (p != null) {
            deliverStack(p, stack);
            return;
        }
        List<ItemStack> list = new ArrayList<>();
        List<?> current = pending.getList(id + ".items");
        if (current != null) for (Object o : current) if (o instanceof ItemStack st) list.add(st);
        list.add(stack);
        pending.set(id + ".items", list);
        savePending();
    }

    private void deliverStack(Player p, ItemStack stack) {
        int remaining = stack.getAmount();
        int max = Math.max(1, stack.getMaxStackSize());
        while (remaining > 0) {
            int n = Math.min(remaining, max);
            ItemStack part = stack.clone();
            part.setAmount(n);
            Map<Integer, ItemStack> left = p.getInventory().addItem(part);
            for (ItemStack st : left.values()) p.getWorld().dropItemNaturally(p.getLocation(), st);
            remaining -= n;
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        if (!pending.contains(id.toString())) return;
        Bukkit.getScheduler().runTaskLater(this, () -> {
            Player p = Bukkit.getPlayer(id);
            if (p == null) return;
            int chips = pending.getInt(id + ".chips", 0);
            List<?> items = pending.getList(id + ".items");
            if (chips > 0) {
                giveChips(p, chips);
                msg(p, "&aRecibes tus premios pendientes: &e" + fmt(chips));
            }
            if (items != null && !items.isEmpty()) {
                for (Object o : items) if (o instanceof ItemStack st) deliverStack(p, st);
                msg(p, "&aRecibes tus premios pendientes de la Maquina del Tesoro.");
            }
            pending.set(id.toString(), null);
            savePending();
        }, 20L);
    }

    /** Evita convertir fichas en otros items (por ejemplo pepitas en lingotes). */
    @EventHandler
    public void onPrepareCraft(PrepareItemCraftEvent e) {
        for (ItemStack it : e.getInventory().getMatrix()) {
            if (isChip(it)) { e.getInventory().setResult(null); return; }
        }
    }

    // ---------- Apuestas ----------
    /** Valida y cobra la apuesta. Devuelve null si no se pudo. */
    public Bet takeBet(Player p, int amount) {
        long now = System.currentTimeMillis();
        long cd = getConfig().getLong("bet.cooldown-seconds", 2) * 1000L;
        Long last = cooldowns.get(p.getUniqueId());
        if (last != null && now - last < cd) {
            msg(p, "&cEspera un momento antes de volver a apostar.");
            return null;
        }
        if (amount < minBet() || amount > maxBet()) {
            msg(p, "&cLa apuesta debe estar entre &e" + fmt(minBet()) + " &cy &e" + fmt(maxBet()) + "&c.");
            return null;
        }
        if (!removeChips(p, amount)) {
            msg(p, "&cNo tienes suficientes fichas. Cambia minerales en la caja del casino.");
            return null;
        }
        cooldowns.put(p.getUniqueId(), now);
        return new Bet(amount);
    }

    /** Cobra un precio fijo (maquina del tesoro): respeta el tiempo de espera pero no los limites de apuesta. */
    public boolean charge(Player p, int amount) {
        long now = System.currentTimeMillis();
        long cd = getConfig().getLong("bet.cooldown-seconds", 2) * 1000L;
        Long last = cooldowns.get(p.getUniqueId());
        if (last != null && now - last < cd) {
            msg(p, "&cEspera un momento antes de volver a jugar.");
            return false;
        }
        if (!removeChips(p, amount)) {
            msg(p, "&cNecesitas &e" + fmt(amount) + "&c. Cambia minerales en la caja del casino.");
            return false;
        }
        cooldowns.put(p.getUniqueId(), now);
        return true;
    }

    /** Si hay cantidad en el comando la usa; si no, abre el menu para elegirla. */
    private void bet(Player p, String[] args, int idx, String title, Consumer<Bet> game) {
        IntConsumer run = amount -> {
            Bet b = takeBet(p, amount);
            if (b != null) game.accept(b);
        };
        if (args.length > idx) {
            int amount;
            try { amount = Integer.parseInt(args[idx]); }
            catch (NumberFormatException e) { msg(p, "&cCantidad invalida."); return; }
            run.accept(amount);
        } else {
            betMenu.open(p, title, run);
        }
    }

    // ---------- Comandos ----------
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase() : "";

        if (sub.equals("reload")) {
            if (!sender.hasPermission("casino.admin")) { sender.sendMessage(ChatColor.RED + "No tienes permiso."); return true; }
            reloadConfig();
            slots.reload();
            exchange.reload();
            treasure.reload();
            sender.sendMessage(ChatColor.GREEN + "Casino recargado.");
            return true;
        }

        if (sub.equals("dar") || sub.equals("give")) {
            if (!sender.hasPermission("casino.admin")) { sender.sendMessage(ChatColor.RED + "No tienes permiso."); return true; }
            if (args.length < 3) { sender.sendMessage(ChatColor.YELLOW + "Uso: /casino dar <jugador> <cantidad>"); return true; }
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) { sender.sendMessage(ChatColor.RED + "Jugador no conectado."); return true; }
            int n;
            try { n = Integer.parseInt(args[2]); } catch (NumberFormatException e) { n = -1; }
            if (n < 1) { sender.sendMessage(ChatColor.RED + "Cantidad invalida."); return true; }
            giveChips(target, n);
            msg(target, "&aHas recibido &e" + fmt(n) + "&a.");
            sender.sendMessage(ChatColor.GREEN + "Entregadas " + fmt(n) + " a " + target.getName() + ".");
            return true;
        }

        if (!(sender instanceof Player p)) { sender.sendMessage("Solo jugadores."); return true; }
        if (!p.hasPermission("casino.play")) { msg(p, "&cNo tienes permiso para jugar."); return true; }

        switch (sub) {
            case "slots", "tragaperras" -> bet(p, args, 1, "Tragaperras", b -> slots.play(p, b));
            case "dados", "dice" -> bet(p, args, 1, "Dados", b -> dice(p, b));
            case "blackjack", "bj" -> bet(p, args, 1, "Blackjack", b -> blackjack.play(p, b));
            case "coinflip", "moneda" -> {
                if (args.length < 3) { msg(p, "&eUso: /casino coinflip <cantidad> <cara|cruz>"); return true; }
                String side = args[2].toLowerCase();
                if (!side.equals("cara") && !side.equals("cruz")) { msg(p, "&cElige &ecara &co &ecruz&c."); return true; }
                int amount;
                try { amount = Integer.parseInt(args[1]); }
                catch (NumberFormatException e) { msg(p, "&cCantidad invalida."); return true; }
                Bet b = takeBet(p, amount);
                if (b != null) coinflip(p, b, side);
            }
            case "cambio", "exchange" -> {
                if (!getConfig().getBoolean("exchange.command-enabled", true) && !p.hasPermission("casino.admin")) {
                    msg(p, "&cLa caja de cambio solo se usa en el casino.");
                    return true;
                }
                exchange.open(p);
            }
            case "premios", "tesoro" -> treasure.openPrizes(p);
            case "fichas", "chips" -> msg(p, "&7Tienes &e" + fmt(countChips(p)) + "&7.");
            case "construir", "build" -> {
                if (!p.hasPermission("casino.admin")) { msg(p, "&cNo tienes permiso."); return true; }
                if (args.length < 2) {
                    msg(p, "&eUso: /casino construir <tragaperras|ruleta|blackjack|cambio|tesoro>");
                    return true;
                }
                machineManager.build(p, args[1]);
            }
            default -> help(p);
        }
        return true;
    }

    private void help(Player p) {
        msg(p, "&6&lCasino &7- Juega con fichas de poker:");
        p.sendMessage(color("&e/casino cambio &7- Cambia minerales por fichas"));
        p.sendMessage(color("&e/casino fichas &7- Mira cuantas fichas tienes"));
        p.sendMessage(color("&e/casino premios &7- Premios y probabilidades de la Maquina del Tesoro"));
        p.sendMessage(color("&e/casino slots|dados|blackjack [cantidad]"));
        p.sendMessage(color("&e/casino coinflip <cantidad> <cara|cruz>"));
        if (p.hasPermission("casino.admin")) {
            p.sendMessage(color("&e/casino construir <tragaperras|ruleta|blackjack|cambio|tesoro>"));
            p.sendMessage(color("&e/casino dar <jugador> <cantidad>"));
        }
        p.sendMessage(color("&7Tambien puedes jugar en las maquinas y carteles del casino."));
    }

    // ---------- Juegos rapidos con animacion ----------
    public void coinflip(Player p, Bet bet, String chosen) {
        boolean win = random.nextDouble() < getConfig().getDouble("coinflip.win-chance", 0.48);
        String result = win ? chosen : (chosen.equals("cara") ? "cruz" : "cara");
        UUID id = p.getUniqueId();
        msg(p, "&7Lanzas la moneda al aire...");

        new BukkitRunnable() {
            int frame = 0;

            @Override
            public void run() {
                Player on = Bukkit.getPlayer(id);
                if (frame < 10) {
                    if (on != null) {
                        on.sendTitle(color(frame % 2 == 0 ? "&6CARA" : "&7CRUZ"), "", 0, 8, 0);
                        on.playSound(on.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.7f, 1.6f);
                    }
                    frame++;
                    return;
                }
                cancel();
                if (win) {
                    int prize = bet.amount() * 2;
                    give(id, prize);
                    if (on != null) {
                        on.sendTitle(color("&a¡" + result.toUpperCase() + "!"), color("&eGanaste " + fmt(prize)), 0, 40, 10);
                        msg(on, "&7Salio &e" + result + "&7. &a¡Ganaste " + fmt(prize) + "!");
                        on.playSound(on.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f);
                    }
                } else if (on != null) {
                    on.sendTitle(color("&c" + result.toUpperCase()), color("&7Perdiste " + fmt(bet.amount())), 0, 40, 10);
                    msg(on, "&7Salio &e" + result + "&7. &cPerdiste " + fmt(bet.amount()) + ".");
                    on.playSound(on.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
                }
            }
        }.runTaskTimer(this, 0L, 3L);
    }

    public void dice(Player p, Bet bet) {
        int mine = random.nextInt(6) + 1 + random.nextInt(6) + 1;
        int house = random.nextInt(6) + 1 + random.nextInt(6) + 1;
        UUID id = p.getUniqueId();
        msg(p, "&7Tiras los dados...");

        new BukkitRunnable() {
            int frame = 0;

            @Override
            public void run() {
                Player on = Bukkit.getPlayer(id);
                if (frame < 8) {
                    if (on != null) {
                        on.sendTitle(color("&e" + (random.nextInt(11) + 2)), color("&7Casa: &e?"), 0, 8, 0);
                        on.playSound(on.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.7f, 1.4f);
                    }
                    frame++;
                    return;
                }
                cancel();
                String info = "&7Tu: &e" + mine + " &7| Casa: &e" + house;
                if (mine > house) {
                    int prize = payout(bet.amount(), getConfig().getDouble("dice.win-multiplier", 2.0));
                    give(id, prize);
                    if (on != null) {
                        on.sendTitle(color("&a" + mine + " vs " + house), color("&eGanaste " + fmt(prize)), 0, 40, 10);
                        msg(on, info + "&7. &a¡Ganaste " + fmt(prize) + "!");
                        on.playSound(on.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f);
                    }
                } else if (on != null) {
                    on.sendTitle(color("&c" + mine + " vs " + house),
                            color(mine == house ? "&7Empate: gana la casa" : "&7Perdiste " + fmt(bet.amount())), 0, 40, 10);
                    msg(on, info + "&7. " + (mine == house ? "&cEmpate: gana la casa. " : "&cPerdiste. ")
                            + "&c-" + fmt(bet.amount()));
                    on.playSound(on.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
                }
            }
        }.runTaskTimer(this, 0L, 3L);
    }

    // ---------- Tab ----------
    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            List<String> subs = new ArrayList<>(List.of("slots", "dados", "blackjack", "coinflip", "cambio", "fichas", "premios"));
            if (sender.hasPermission("casino.admin")) subs.addAll(List.of("construir", "dar", "reload"));
            for (String s : subs) if (s.startsWith(args[0].toLowerCase())) out.add(s);
        } else if (args.length == 2 && args[0].equalsIgnoreCase("construir")) {
            for (String s : List.of("tragaperras", "ruleta", "blackjack", "cambio", "tesoro"))
                if (s.startsWith(args[1].toLowerCase())) out.add(s);
        } else if (args.length == 3 && args[0].equalsIgnoreCase("coinflip")) {
            for (String s : List.of("cara", "cruz"))
                if (s.startsWith(args[2].toLowerCase())) out.add(s);
        }
        return out;
    }
}
