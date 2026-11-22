package com.casino;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.FaceAttachable;
import org.bukkit.block.data.type.Switch;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * Maquinas fisicas (bloques + entidades Display):
 *  - tragaperras: cuerpo de bloques, 3 rodillos flotantes y palanca real
 *  - ruleta: mesa con 13 casillas y una bola que gira; apuestas en los bloques rojo/verde/negro
 *  - blackjack: mesa de fieltro; clic para jugar
 *  - cambio: mostrador para cambiar minerales por fichas
 * El jugador elige su apuesta en un menu al interactuar.
 */
public class MachineManager implements Listener {

    private static final int POCKETS = 13;
    private static final double WHEEL_RADIUS = 2.0;

    private static class Machine {
        final String id, type;
        final World world;
        final int x, y, z;
        final BlockFace facing;
        boolean busy;

        Machine(String id, String type, World world, int x, int y, int z, BlockFace facing) {
            this.id = id; this.type = type; this.world = world;
            this.x = x; this.y = y; this.z = z; this.facing = facing;
        }
        boolean isSlots() { return type.equals("slots"); }
        boolean hasReels() { return type.equals("slots") || type.equals("treasure"); }
        boolean isRoulette() { return type.equals("roulette"); }
        int depth() {
            switch (type) {
                case "slots": case "treasure": return 2;
                case "roulette": return 7;
                case "blackjack": return 3;
                default: return 1;
            }
        }
    }

    private record Part(int x, int y, int z, BlockData data, String action) {}
    private record Target(Machine machine, String action) {}

    private final CasinoPlugin plugin;
    private final File file;
    private final Map<String, Machine> machines = new LinkedHashMap<>();
    private final Map<String, Machine> structure = new HashMap<>();
    private final Map<String, Target> targets = new HashMap<>();

    public MachineManager(CasinoPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "machines.yml");
        Bukkit.getScheduler().runTask(plugin, this::load); // un tick despues: mundos ya cargados
    }

    // ================= Geometria =================

    private Block blockAt(Machine m, int lx, int ly, int lz) {
        int fx = m.facing.getModX(), fz = m.facing.getModZ();
        int rx = fz, rz = -fx;
        return m.world.getBlockAt(m.x + lx * rx + lz * fx, m.y + ly, m.z + lx * rz + lz * fz);
    }

    /** dx a la derecha, dz hacia el frente, dy arriba, desde el centro del bloque origen. */
    private Location point(Machine m, double dx, double dy, double dz) {
        int fx = m.facing.getModX(), fz = m.facing.getModZ();
        int rx = fz, rz = -fx;
        return new Location(m.world, m.x + 0.5 + dx * rx + dz * fx, m.y + dy, m.z + 0.5 + dx * rz + dz * fz);
    }

    private Location pocketLoc(Machine m, int i, double y) {
        double a = 2 * Math.PI * i / POCKETS;
        return point(m, WHEEL_RADIUS * Math.cos(a), y, 3 + WHEEL_RADIUS * Math.sin(a));
    }

    private BlockFace faceOf(int x, int z) {
        for (BlockFace f : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST})
            if (f.getModX() == x && f.getModZ() == z) return f;
        return BlockFace.NORTH;
    }

    private String key(Block b) { return b.getWorld().getName() + "," + b.getX() + "," + b.getY() + "," + b.getZ(); }

    private BlockData data(Material mat) { return mat.createBlockData(); }

    private boolean isFree(Block b) {
        Material t = b.getType();
        if (t.isAir()) return true;
        String n = t.name();
        return n.equals("SNOW") || n.endsWith("GRASS") || n.equals("FERN") || n.equals("LARGE_FERN")
                || n.equals("DEAD_BUSH") || n.equals("VINE");
    }

    private BlockFace facingOf(Player p) {
        int i = Math.round(p.getLocation().getYaw() / 90f) & 3;
        switch (i) {
            case 0: return BlockFace.SOUTH;
            case 1: return BlockFace.WEST;
            case 2: return BlockFace.NORTH;
            default: return BlockFace.EAST;
        }
    }

    private List<Part> layout(Machine m) {
        List<Part> l = new ArrayList<>();
        switch (m.type) {
            case "slots" -> {
                for (int x = -1; x <= 1; x++) {
                    for (int z = 0; z <= 1; z++) {
                        l.add(new Part(x, 0, z, data(Material.IRON_BLOCK), null));
                        l.add(new Part(x, 1, z, data(x == 0 && z == 1 ? Material.BLACK_CONCRETE : Material.IRON_BLOCK), null));
                        l.add(new Part(x, 3, z, data(x == 0 ? Material.SEA_LANTERN : Material.GOLD_BLOCK), null));
                    }
                    l.add(new Part(x, 2, 0, data(Material.BLACK_CONCRETE), null));
                }
                int rx = m.facing.getModZ(), rz = -m.facing.getModX();
                Switch lever = (Switch) Material.LEVER.createBlockData();
                lever.setAttachedFace(FaceAttachable.AttachedFace.WALL);
                lever.setFacing(faceOf(rx, rz));
                l.add(new Part(2, 1, 0, lever, "pull"));
            }
            case "treasure" -> {
                for (int x = -1; x <= 1; x++) {
                    for (int z = 0; z <= 1; z++) {
                        l.add(new Part(x, 0, z, data(Material.AMETHYST_BLOCK), null));
                        l.add(new Part(x, 1, z, data(x == 0 && z == 1 ? Material.OBSIDIAN : Material.AMETHYST_BLOCK), null));
                        l.add(new Part(x, 3, z, data(x == 0 ? Material.GLOWSTONE : Material.EMERALD_BLOCK), null));
                    }
                    l.add(new Part(x, 2, 0, data(Material.BLACK_CONCRETE), null));
                }
                int rx = m.facing.getModZ(), rz = -m.facing.getModX();
                Switch lever = (Switch) Material.LEVER.createBlockData();
                lever.setAttachedFace(FaceAttachable.AttachedFace.WALL);
                lever.setFacing(faceOf(rx, rz));
                l.add(new Part(2, 1, 0, lever, "pull"));
                // Boton de la izquierda: abre la lista de premios y probabilidades
                Switch button = (Switch) Material.STONE_BUTTON.createBlockData();
                button.setAttachedFace(FaceAttachable.AttachedFace.WALL);
                button.setFacing(faceOf(-rx, -rz));
                l.add(new Part(-2, 1, 0, button, "prizes"));
            }
            case "roulette" -> {
                for (int x = -3; x <= 3; x++) {
                    for (int z = 0; z <= 6; z++) {
                        boolean rim = Math.abs(x) == 3 || z == 0 || z == 6;
                        BlockData d = data(rim ? Material.DARK_OAK_PLANKS : Material.GREEN_WOOL);
                        String action = null;
                        if (z == 6 && x == -1) { d = data(Material.RED_CONCRETE); action = "rojo"; }
                        else if (z == 6 && x == 0) { d = data(Material.LIME_CONCRETE); action = "verde"; }
                        else if (z == 6 && x == 1) { d = data(Material.BLACK_CONCRETE); action = "negro"; }
                        l.add(new Part(x, 0, z, d, action));
                    }
                }
            }
            case "blackjack" -> {
                for (int x = -2; x <= 2; x++) {
                    for (int z = 0; z <= 2; z++) {
                        boolean rim = Math.abs(x) == 2 || z == 0 || z == 2;
                        l.add(new Part(x, 0, z, data(rim ? Material.DARK_OAK_PLANKS : Material.GREEN_WOOL), "play"));
                    }
                }
            }
            default -> { // cashier
                for (int x = -1; x <= 1; x++) l.add(new Part(x, 0, 0, data(Material.QUARTZ_BLOCK), "open"));
                l.add(new Part(-1, 1, 0, data(Material.GOLD_BLOCK), "open"));
                l.add(new Part(1, 1, 0, data(Material.GOLD_BLOCK), "open"));
            }
        }
        return l;
    }

    private List<int[]> extraClearance(Machine m) {
        List<int[]> l = new ArrayList<>();
        if (m.hasReels()) for (int x = -1; x <= 1; x++) l.add(new int[]{x, 2, 1});
        if (m.type.equals("cashier")) l.add(new int[]{0, 1, 0});
        return l;
    }

    // ================= Registro / persistencia =================

    private void register(Machine m) {
        machines.put(m.id, m);
        for (Part p : layout(m)) {
            Block b = blockAt(m, p.x(), p.y(), p.z());
            structure.put(key(b), m);
            if (p.action() != null) targets.put(key(b), new Target(m, p.action()));
        }
    }

    private void unregister(Machine m) {
        machines.remove(m.id);
        structure.values().removeIf(v -> v == m);
        targets.values().removeIf(t -> t.machine() == m);
    }

    private void load() {
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection sec = cfg.getConfigurationSection("machines");
        if (sec == null) return;
        for (String id : sec.getKeys(false)) {
            ConfigurationSection s = sec.getConfigurationSection(id);
            if (s == null) continue;
            World w = Bukkit.getWorld(s.getString("world", ""));
            BlockFace face;
            try { face = BlockFace.valueOf(s.getString("facing", "NORTH")); }
            catch (IllegalArgumentException e) { continue; }
            if (w == null) { plugin.getLogger().warning("Mundo no cargado para la maquina " + id); continue; }
            register(new Machine(id, s.getString("type", "slots"), w, s.getInt("x"), s.getInt("y"), s.getInt("z"), face));
        }
        plugin.getLogger().info("Maquinas del casino cargadas: " + machines.size());
    }

    private void save() {
        YamlConfiguration cfg = new YamlConfiguration();
        for (Machine m : machines.values()) {
            String p = "machines." + m.id + ".";
            cfg.set(p + "type", m.type);
            cfg.set(p + "world", m.world.getName());
            cfg.set(p + "x", m.x); cfg.set(p + "y", m.y); cfg.set(p + "z", m.z);
            cfg.set(p + "facing", m.facing.name());
        }
        try { cfg.save(file); }
        catch (IOException e) { plugin.getLogger().severe("No se pudo guardar machines.yml: " + e.getMessage()); }
    }

    // ================= Construccion =================

    public void build(Player p, String typeArg) {
        String type;
        switch (typeArg.toLowerCase()) {
            case "tragaperras": case "slots": type = "slots"; break;
            case "ruleta": case "roulette": type = "roulette"; break;
            case "blackjack": case "bj": type = "blackjack"; break;
            case "tesoro": case "treasure": type = "treasure"; break;
            case "cambio": case "caja": case "cashier": type = "cashier"; break;
            default:
                plugin.msg(p, "&cTipo invalido. Usa &etragaperras&c, &eruleta&c, &eblackjack&c, &ecambio &co &etesoro&c.");
                return;
        }

        BlockFace dir = facingOf(p);
        BlockFace facing = dir.getOppositeFace();
        Block pb = p.getLocation().getBlock();
        String id = UUID.randomUUID().toString().substring(0, 8);
        int depth = new Machine(id, type, p.getWorld(), 0, 0, 0, facing).depth();
        Machine m = new Machine(id, type, p.getWorld(),
                pb.getX() + dir.getModX() * depth, pb.getY(), pb.getZ() + dir.getModZ() * depth, facing);

        List<Part> parts = layout(m);
        for (Part part : parts) {
            Block b = blockAt(m, part.x(), part.y(), part.z());
            if (!isFree(b) || structure.containsKey(key(b))) {
                plugin.msg(p, "&cNo hay espacio libre delante de ti. Busca una zona plana y despejada.");
                return;
            }
        }
        for (int[] c : extraClearance(m)) {
            if (!isFree(blockAt(m, c[0], c[1], c[2]))) {
                plugin.msg(p, "&cHay bloques estorbando en la zona de la pantalla.");
                return;
            }
        }

        for (Part part : parts) blockAt(m, part.x(), part.y(), part.z()).setBlockData(part.data(), false);
        register(m);
        spawnDisplays(m);
        save();
        plugin.msg(p, "&aMaquina creada (&e" + typeArg.toLowerCase() + "&a). Rompe cualquiera de sus bloques para eliminarla.");
    }

    private void removeMachine(Machine m) {
        removeDisplays(m);
        for (Part part : layout(m)) blockAt(m, part.x(), part.y(), part.z()).setType(Material.AIR, false);
        unregister(m);
        save();
    }

    // ================= Displays =================

    private String tag(Machine m) { return "casino_" + m.id; }

    private Location centerOf(Machine m) { return point(m, 0, 2, (m.depth() - 1) / 2.0); }

    private void removeDisplays(Machine m) {
        for (Entity e : m.world.getNearbyEntities(centerOf(m), 10, 10, 10))
            if (e instanceof Display && e.getScoreboardTags().contains(tag(m))) e.remove();
    }

    private <T extends Entity> T find(Machine m, String role, Class<T> type) {
        for (Entity e : m.world.getNearbyEntities(centerOf(m), 10, 10, 10))
            if (type.isInstance(e) && e.getScoreboardTags().contains(tag(m) + "_" + role)) return type.cast(e);
        return null;
    }

    private Transformation scale(float sx, float sy, float sz, float tx, float ty, float tz) {
        return new Transformation(new Vector3f(tx, ty, tz), new AxisAngle4f(), new Vector3f(sx, sy, sz), new AxisAngle4f());
    }

    private void spawnItem(Machine m, Location loc, Material mat, String role) {
        loc.getWorld().spawn(loc, ItemDisplay.class, d -> {
            d.setItemStack(new ItemStack(mat));
            d.setBillboard(Display.Billboard.CENTER);
            d.setTransformation(scale(0.75f, 0.75f, 0.75f, 0, 0, 0));
            d.addScoreboardTag(tag(m));
            d.addScoreboardTag(tag(m) + "_" + role);
        });
    }

    private void spawnStack(Machine m, Location loc, ItemStack stack, String role) {
        loc.getWorld().spawn(loc, ItemDisplay.class, d -> {
            d.setItemStack(stack);
            d.setBillboard(Display.Billboard.CENTER);
            d.setTransformation(scale(0.75f, 0.75f, 0.75f, 0, 0, 0));
            d.addScoreboardTag(tag(m));
            d.addScoreboardTag(tag(m) + "_" + role);
        });
    }

    private void spawnBlock(Machine m, Location loc, Material mat, float size, float height, String role) {
        loc.getWorld().spawn(loc, BlockDisplay.class, d -> {
            d.setBlock(mat.createBlockData());
            d.setTransformation(scale(size, height, size, -size / 2, 0, -size / 2));
            d.addScoreboardTag(tag(m));
            d.addScoreboardTag(tag(m) + "_" + role);
        });
    }

    private void spawnText(Machine m, Location loc, String text, String role) {
        loc.getWorld().spawn(loc, TextDisplay.class, d -> {
            d.setText(plugin.color(text));
            d.setBillboard(Display.Billboard.CENTER);
            d.addScoreboardTag(tag(m));
            d.addScoreboardTag(tag(m) + "_" + role);
        });
    }

    private String idleText(Machine m) {
        String range = "&7Apuesta: &f" + plugin.minBet() + " - " + plugin.maxBet() + " fichas";
        switch (m.type) {
            case "slots": return "&6&lTRAGAPERRAS\n" + range + "\n&7Tira de la palanca";
            case "roulette": return "&6&lRULETA\n" + range + "\n&7Elige color en la mesa";
            case "treasure": return "&d&lMÁQUINA DEL TESORO\n&7Cada giro: &f" + plugin.fmt(plugin.getTreasure().cost()) + "\n&7Premios reales. ¡Tira de la palanca!";
            case "blackjack": return "&6&lBLACKJACK\n" + range + "\n&7Clic en la mesa para jugar";
            default: return "&6&lCAMBIO DE FICHAS\n&7Minerales &f<-> &7fichas\n&7Clic en el mostrador";
        }
    }

    private double colorMultiplier() { return plugin.getConfig().getDouble("roulette.color-multiplier", 2.0); }
    private double greenMultiplier() { return plugin.getConfig().getDouble("roulette.green-multiplier", 12.0); }

    private void spawnDisplays(Machine m) {
        removeDisplays(m);
        switch (m.type) {
            case "slots" -> {
                for (int i = 0; i < 3; i++)
                    spawnItem(m, point(m, i - 1, 2.5, 1), plugin.getSlots().randomVisual(), "reel" + i);
                spawnText(m, point(m, 0, 4.7, 1), idleText(m), "text");
            }
            case "treasure" -> {
                for (int i = 0; i < 3; i++)
                    spawnStack(m, point(m, i - 1, 2.5, 1), plugin.getTreasure().isEmpty()
                            ? new ItemStack(Material.CHEST) : plugin.getTreasure().randomVisual(), "reel" + i);
                spawnText(m, point(m, 0, 4.7, 1), idleText(m), "text");
                spawnText(m, point(m, -2, 1.9, 0), "&e&lVER PREMIOS\n&7(botón)", "label");
            }
            case "roulette" -> {
                for (int i = 0; i < POCKETS; i++)
                    spawnBlock(m, pocketLoc(m, i, 1.0), pocketMaterial(i), 0.55f, 0.1f, "pocket");
                spawnBlock(m, point(m, 0, 1.0, 3), Material.GOLD_BLOCK, 0.8f, 0.3f, "hub");
                spawnBlock(m, pocketLoc(m, 0, 1.12), Material.WHITE_CONCRETE, 0.22f, 0.22f, "ball");
                spawnText(m, point(m, 0, 2.8, 3), idleText(m), "text");
                spawnText(m, point(m, -1, 1.6, 6), "&c&lROJO\n&fx" + trim(colorMultiplier()), "label");
                spawnText(m, point(m, 0, 1.6, 6), "&a&lVERDE\n&fx" + trim(greenMultiplier()), "label");
                spawnText(m, point(m, 1, 1.6, 6), "&8&lNEGRO\n&fx" + trim(colorMultiplier()), "label");
            }
            case "blackjack" -> spawnText(m, point(m, 0, 1.9, 1), idleText(m), "text");
            default -> spawnText(m, point(m, 0, 1.7, 0), idleText(m), "text");
        }
    }

    private Material pocketMaterial(int i) {
        if (i == 0) return Material.LIME_CONCRETE;
        return i % 2 == 1 ? Material.RED_CONCRETE : Material.BLACK_CONCRETE;
    }

    private String trim(double d) { return d == Math.floor(d) ? String.valueOf((long) d) : String.valueOf(d); }

    private void setText(Machine m, String text) {
        TextDisplay t = find(m, "text", TextDisplay.class);
        if (t != null) t.setText(plugin.color(text));
    }

    private void resetTextLater(Machine m) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> { if (!m.busy) setText(m, idleText(m)); }, 120L);
    }

    private void ensureDisplays(Machine m) {
        boolean ok;
        if (m.hasReels()) {
            ok = find(m, "reel0", ItemDisplay.class) != null && find(m, "reel1", ItemDisplay.class) != null
                    && find(m, "reel2", ItemDisplay.class) != null && find(m, "text", TextDisplay.class) != null;
        } else {
            ok = find(m, "ball", BlockDisplay.class) != null && find(m, "text", TextDisplay.class) != null;
        }
        if (!ok) spawnDisplays(m); // se recrean si alguien las elimino
    }

    // ================= Interaccion =================

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        final Block b = e.getClickedBlock();
        if (b == null) return;
        Target t = targets.get(key(b));
        if (t == null) return;

        e.setCancelled(true);
        final Player p = e.getPlayer();
        final Machine m = t.machine();
        final String action = t.action();
        if (!p.hasPermission("casino.play")) { plugin.msg(p, "&cNo tienes permiso para jugar."); return; }

        switch (m.type) {
            case "slots" -> {
                if (m.busy) { plugin.msg(p, "&cLa maquina esta en marcha, espera un momento."); return; }
                plugin.getBetMenu().open(p, "Tragaperras", amt -> playSlots(p, m, b, amt));
            }
            case "treasure" -> {
                if (action.equals("prizes")) { plugin.getTreasure().openPrizes(p); return; }
                playTreasure(p, m, b);
            }
            case "roulette" -> {
                if (m.busy) { plugin.msg(p, "&cLa ruleta esta girando, espera a que termine."); return; }
                plugin.getBetMenu().open(p, "Ruleta (" + action + ")", amt -> playRoulette(p, m, action, amt));
            }
            case "blackjack" -> plugin.getBetMenu().open(p, "Blackjack", amt -> {
                CasinoPlugin.Bet bet = plugin.takeBet(p, amt);
                if (bet != null) plugin.getBlackjack().play(p, bet);
            });
            default -> plugin.getExchange().open(p);
        }
    }

    private void setLever(Block b, boolean on) {
        if (b.getBlockData() instanceof Switch sw) {
            sw.setPowered(on);
            b.setBlockData(sw, false);
        }
    }

    // ---------- Tragaperras fisica ----------
    private void playSlots(Player p, Machine m, Block lever, int amount) {
        if (m.busy) { plugin.msg(p, "&cLa maquina esta en marcha, espera un momento."); return; }
        CasinoPlugin.Bet bet = plugin.takeBet(p, amount);
        if (bet == null) return;

        m.busy = true;
        ensureDisplays(m);
        SlotsGame game = plugin.getSlots();
        Material[] result = new Material[3];
        for (int i = 0; i < 3; i++) result[i] = game.randomSymbol();
        double multiplier = game.computeMultiplier(result);
        int prize = plugin.payout(bet.amount(), multiplier);
        UUID id = p.getUniqueId();

        setLever(lever, true);
        setText(m, "&6&lTRAGAPERRAS\n&7Apuesta: &f" + plugin.fmt(bet.amount()) + "\n&7Girando...");
        m.world.playSound(point(m, 0, 2, 1), Sound.BLOCK_PISTON_EXTEND, 1f, 1.2f);

        final int[] stop = {10, 16, 22};
        new BukkitRunnable() {
            int frame = 0;

            @Override
            public void run() {
                for (int i = 0; i < 3; i++) {
                    ItemDisplay d = find(m, "reel" + i, ItemDisplay.class);
                    if (d == null) continue;
                    if (frame < stop[i]) {
                        d.setItemStack(new ItemStack(game.randomVisual()));
                    } else if (frame == stop[i]) {
                        d.setItemStack(new ItemStack(result[i]));
                        m.world.playSound(point(m, i - 1, 2.5, 1), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 0.8f + i * 0.3f);
                    }
                }
                if (frame < stop[2]) m.world.playSound(point(m, 0, 2, 1), Sound.BLOCK_NOTE_BLOCK_HAT, 0.5f, 1.5f);

                frame++;
                if (frame > stop[2]) {
                    cancel();
                    finishSlots(id, m, lever, bet, prize, multiplier);
                }
            }
        }.runTaskTimer(plugin, 0L, 2L);
    }

    private void finishSlots(UUID id, Machine m, Block lever, CasinoPlugin.Bet bet, int prize, double multiplier) {
        Player on = Bukkit.getPlayer(id);
        setLever(lever, false);
        if (prize > 0) {
            plugin.give(id, prize);
            String text = plugin.fmt(prize);
            setText(m, "&a&l¡PREMIO!\n&e" + text + " &7(x" + trim(multiplier) + ")");
            m.world.playSound(point(m, 0, 2, 1), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
            if (on != null) plugin.msg(on, "&a¡Tragaperras! Ganaste &e" + text + " &7(x" + trim(multiplier) + ")");
        } else {
            setText(m, "&c&lSin premio\n&7Suerte para la proxima");
            m.world.playSound(point(m, 0, 2, 1), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
            if (on != null) plugin.msg(on, "&cNo hubo suerte. Perdiste &e" + plugin.fmt(bet.amount()) + "&c.");
        }
        m.busy = false;
        resetTextLater(m);
    }

    // ---------- Maquina del Tesoro (premios en items) ----------
    private void playTreasure(Player p, Machine m, Block lever) {
        if (m.busy) { plugin.msg(p, "&cLa maquina esta en marcha, espera un momento."); return; }
        Treasure t = plugin.getTreasure();
        if (t.isEmpty()) { plugin.msg(p, "&cLa maquina no tiene premios configurados."); return; }
        if (!plugin.charge(p, t.cost())) return;

        m.busy = true;
        ensureDisplays(m);
        Treasure.Reward win = t.pick();
        ItemStack prize = t.build(win);
        UUID id = p.getUniqueId();

        setLever(lever, true);
        setText(m, "&d&lMÁQUINA DEL TESORO\n&7Girando...");
        m.world.playSound(point(m, 0, 2, 1), Sound.BLOCK_PISTON_EXTEND, 1f, 1.0f);

        final int[] stop = {10, 16, 22};
        new BukkitRunnable() {
            int frame = 0;

            @Override
            public void run() {
                for (int i = 0; i < 3; i++) {
                    ItemDisplay d = find(m, "reel" + i, ItemDisplay.class);
                    if (d == null) continue;
                    if (frame < stop[i]) {
                        d.setItemStack(t.randomVisual());
                    } else if (frame == stop[i]) {
                        d.setItemStack(prize.clone());
                        m.world.playSound(point(m, i - 1, 2.5, 1), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 0.8f + i * 0.3f);
                    }
                }
                if (frame < stop[2]) m.world.playSound(point(m, 0, 2, 1), Sound.BLOCK_NOTE_BLOCK_HAT, 0.5f, 1.5f);

                frame++;
                if (frame > stop[2]) {
                    cancel();
                    finishTreasure(id, m, lever, win, prize);
                }
            }
        }.runTaskTimer(plugin, 0L, 2L);
    }

    private void finishTreasure(UUID id, Machine m, Block lever, Treasure.Reward win, ItemStack prize) {
        Treasure t = plugin.getTreasure();
        Player on = Bukkit.getPlayer(id);
        setLever(lever, false);
        plugin.giveStack(id, prize);

        String label = t.label(win);
        setText(m, "&a&l¡PREMIO!\n&e" + label + "\n&7Valor: &f" + t.trim(win.value()) + " fichas");
        m.world.playSound(point(m, 0, 2, 1), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
        if (on != null) {
            plugin.msg(on, "&a¡Premio! Ganaste &e" + label + " &7(valor " + t.trim(win.value()) + " fichas)");
            if (win.value() >= t.broadcastValue()) {
                String prefix = plugin.getConfig().getString("prefix", "&6[Casino] &f");
                Bukkit.broadcastMessage(plugin.color(prefix + "&6" + on.getName() + " &eha ganado &6" + label
                        + " &een la Máquina del Tesoro!"));
            }
        }
        m.busy = false;
        resetTextLater(m);
    }

    // ---------- Ruleta fisica ----------
    private void playRoulette(Player p, Machine m, String color, int amount) {
        if (m.busy) { plugin.msg(p, "&cLa ruleta esta girando, espera a que termine."); return; }
        CasinoPlugin.Bet bet = plugin.takeBet(p, amount);
        if (bet == null) return;

        m.busy = true;
        ensureDisplays(m);
        int r = plugin.getRandom().nextInt(POCKETS);   // casilla ganadora (0 = verde)
        String resultColor = r == 0 ? "verde" : (r % 2 == 1 ? "rojo" : "negro");
        boolean win = resultColor.equals(color);
        double mult = r == 0 ? greenMultiplier() : colorMultiplier();
        int prize = win ? plugin.payout(bet.amount(), mult) : 0;
        UUID id = p.getUniqueId();

        setText(m, "&6&lRULETA\n&7" + plugin.fmt(bet.amount()) + " al &e" + color.toUpperCase() + "\n&7Girando...");
        plugin.msg(p, "&7Apostaste &e" + plugin.fmt(bet.amount()) + " &7al &e" + color + "&7. ¡No va más!");

        final int total = 2 * POCKETS + r;   // dos vueltas y para en la casilla r
        new BukkitRunnable() {
            int step = 0, wait = 0;

            @Override
            public void run() {
                BlockDisplay ball = find(m, "ball", BlockDisplay.class);
                int delay = 1 + (int) (5.0 * step / total);   // va frenando
                if (++wait < delay) return;
                wait = 0;
                step++;
                Location loc = pocketLoc(m, step % POCKETS, 1.12);
                if (ball != null) ball.teleport(loc);
                m.world.playSound(loc, Sound.BLOCK_NOTE_BLOCK_HAT, 0.6f, 1.2f + (float) step / total);

                if (step >= total) {
                    cancel();
                    finishRoulette(id, m, bet, r, resultColor, prize, win);
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    private void finishRoulette(UUID id, Machine m, CasinoPlugin.Bet bet, int number, String resultColor, int prize, boolean win) {
        Player on = Bukkit.getPlayer(id);
        String shown = "&l" + number + " " + resultColor.toUpperCase();
        if (win) {
            plugin.give(id, prize);
            String text = plugin.fmt(prize);
            setText(m, "&a" + shown + "\n&a¡Ganaste " + text + "!");
            m.world.playSound(point(m, 0, 2, 3), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
            if (on != null) plugin.msg(on, "&7Salio el &e" + number + " (" + resultColor + ")&7. &a¡Ganaste " + text + "!");
        } else {
            setText(m, "&c" + shown + "\n&7Perdiste");
            m.world.playSound(point(m, 0, 2, 3), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
            if (on != null) plugin.msg(on, "&7Salio el &e" + number + " (" + resultColor + ")&7. &cPerdiste "
                    + plugin.fmt(bet.amount()) + ".");
        }
        m.busy = false;
        resetTextLater(m);
    }

    // ================= Proteccion =================

    @EventHandler(priority = EventPriority.HIGH)
    public void onBreak(BlockBreakEvent e) {
        Machine m = structure.get(key(e.getBlock()));
        if (m == null) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        if (p.hasPermission("casino.admin")) {
            removeMachine(m);
            plugin.msg(p, "&7Maquina del casino eliminada.");
        } else {
            plugin.msg(p, "&cNo puedes romper las maquinas del casino.");
        }
    }

    @EventHandler
    public void onEntityExplode(EntityExplodeEvent e) {
        e.blockList().removeIf(b -> structure.containsKey(key(b)));
    }

    @EventHandler
    public void onBlockExplode(BlockExplodeEvent e) {
        e.blockList().removeIf(b -> structure.containsKey(key(b)));
    }
}
