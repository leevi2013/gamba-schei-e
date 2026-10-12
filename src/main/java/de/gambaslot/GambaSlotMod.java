package de.gambaslot;

import com.mojang.brigadier.arguments.LongArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public final class GambaSlotMod implements ModInitializer {
    public static final String MOD_ID = "gamba_slot";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static final long START_BALANCE = 1_000L;
    public static final long MAX_BET = 1_000_000_000L;
    private static final long MAX_BALANCE = Long.MAX_VALUE / 4;
    private static final Random RANDOM = new Random();

    public static final List<OreSymbol> SYMBOLS = List.of(
            new OreSymbol(Items.COAL_ORE, "Coal Ore", 0.5),
            new OreSymbol(Items.IRON_ORE, "Iron Ore", 1.2),
            new OreSymbol(Items.GOLD_ORE, "Gold Ore", 2.0),
            new OreSymbol(Items.DIAMOND_ORE, "Diamond Ore", 5.0),
            new OreSymbol(Items.EMERALD_ORE, "Emerald Ore", 10.0),
            new OreSymbol(Items.ANCIENT_DEBRIS, "Ancient Debris / Netherite", 25.0)
    );

    private static final Map<UUID, Long> BALANCES = new HashMap<>();
    private static final Map<UUID, SpinState> ACTIVE_SPINS = new HashMap<>();
    private static Path balanceFile;

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(Commands.literal("gamba")
                    .executes(context -> openMenu(context.getSource().getPlayerOrException(), 10L))
                    .then(Commands.argument("einsatz", LongArgumentType.longArg())
                            .executes(context -> openMenu(
                                    context.getSource().getPlayerOrException(),
                                    LongArgumentType.getLong(context, "einsatz"))))
                    .then(Commands.literal("kontostand")
                            .executes(context -> {
                                ServerPlayer player = context.getSource().getPlayerOrException();
                                long balance = getBalance(player.getUUID());
                                player.sendSystemMessage(Component.literal("Dein Gamba-Kontostand: " + balance + " Coins")
                                        .withStyle(ChatFormatting.AQUA));
                                saveBalances();
                                return 1;
                            }))
                    .then(Commands.literal("hilfe")
                            .executes(context -> {
                                ServerPlayer player = context.getSource().getPlayerOrException();
                                player.sendSystemMessage(Component.literal("/gamba [einsatz] – Slotmaschine öffnen (Standard: 10 Coins)")
                                        .withStyle(ChatFormatting.AQUA));
                                player.sendSystemMessage(Component.literal("/gamba kontostand – Kontostand anzeigen")
                                        .withStyle(ChatFormatting.AQUA));
                                return 1;
                            })));
        });

        ServerLifecycleEvents.SERVER_STARTED.register(GambaSlotMod::loadBalances);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            // A server stop mid-spin refunds all unfinished stakes.
            for (SpinState state : ACTIVE_SPINS.values()) {
                addBalance(state.playerId, state.bet);
            }
            ACTIVE_SPINS.clear();
            saveBalances();
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> tickSpins());

        LOGGER.info("Gamba Slot wurde geladen.");
    }

    private static int openMenu(ServerPlayer player, long bet) {
        // Validation is deliberately repeated here, in addition to the GUI-side check.
        if (bet <= 0) {
            player.sendSystemMessage(Component.literal("Fehler: Der Einsatz muss größer als 0 sein. Drehung blockiert.")
                    .withStyle(ChatFormatting.RED));
            return 0;
        }
        if (bet > MAX_BET) {
            player.sendSystemMessage(Component.literal("Fehler: Der maximale Einsatz beträgt " + MAX_BET + " Coins.")
                    .withStyle(ChatFormatting.RED));
            return 0;
        }

        long balance = getBalance(player.getUUID());
        saveBalances();
        player.openMenu(new SimpleMenuProvider(
                (syncId, inventory, menuPlayer) -> new GambaMenu(syncId, inventory, menuPlayer, bet),
                Component.literal("Gamba Slotmaschine")
        ));
        player.sendSystemMessage(Component.literal("Einsatz gesetzt: " + bet + " Coins. Kontostand: " + balance + " Coins.")
                .withStyle(ChatFormatting.BLUE));
        return 1;
    }

    public static void startSpin(ServerPlayer player, GambaMenu menu, long bet) {
        UUID playerId = player.getUUID();

        // Defense in depth: never allow a zero or negative stake to start a spin.
        if (bet <= 0) {
            player.sendSystemMessage(Component.literal("Fehler: Der Einsatz muss größer als 0 sein. Drehung blockiert.")
                    .withStyle(ChatFormatting.RED));
            menu.setStatus("Ungültiger Einsatz", ChatFormatting.RED);
            return;
        }
        if (bet > MAX_BET) {
            player.sendSystemMessage(Component.literal("Fehler: Einsatz über dem Maximum. Drehung blockiert.")
                    .withStyle(ChatFormatting.RED));
            menu.setStatus("Einsatz zu hoch", ChatFormatting.RED);
            return;
        }
        if (ACTIVE_SPINS.containsKey(playerId)) {
            player.sendSystemMessage(Component.literal("Die Walzen drehen sich bereits!").withStyle(ChatFormatting.YELLOW));
            return;
        }

        long currentBalance = getBalance(playerId);
        if (currentBalance < bet) {
            player.sendSystemMessage(Component.literal("Nicht genug Coins! Dein Kontostand beträgt " + currentBalance + ".")
                    .withStyle(ChatFormatting.RED));
            menu.setStatus("Zu wenig Coins", ChatFormatting.RED);
            return;
        }

        setBalance(playerId, currentBalance - bet);
        menu.updateBalanceDisplay(currentBalance - bet);
        menu.setStatus("Die Walzen drehen sich ...", ChatFormatting.YELLOW);
        menu.setSpinButton(true);

        long now = System.currentTimeMillis();
        long duration = ThreadLocalRandom.current().nextLong(2_000L, 4_001L); // 2–4 seconds, inclusive
        ACTIVE_SPINS.put(playerId, new SpinState(player, playerId, menu, bet, now + duration, now));
    }

    private static void tickSpins() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, SpinState>> iterator = ACTIVE_SPINS.entrySet().iterator();

        while (iterator.hasNext()) {
            SpinState state = iterator.next().getValue();
            boolean menuIsOpen = state.player.containerMenu == state.menu;

            if (menuIsOpen && now >= state.nextAnimationAt) {
                state.menu.setReels(randomSymbols());
                state.nextAnimationAt = now + 100L;
            }

            if (now < state.finishAt) {
                continue;
            }

            // Determine the final outcome only after the random 2–4 second animation has elapsed.
            state.result = randomSymbols();
            state.menu.setReels(state.result);
            boolean match = state.result[0] == state.result[1] && state.result[1] == state.result[2];
            long payout = match ? (long) Math.floor(state.bet * state.result[0].multiplier()) : 0L;
            if (payout > 0L) {
                addBalance(state.playerId, payout);
            }
            long newBalance = getBalance(state.playerId);
            state.menu.updateBalanceDisplay(newBalance);
            state.menu.setSpinButton(false);

            if (match) {
                OreSymbol winningSymbol = state.result[0];
                state.menu.setStatus("Ergebnis: " + winningSymbol.label(),
                        winningSymbol.multiplier() < 1.0 ? ChatFormatting.RED : ChatFormatting.GREEN);
                String message;
                if (winningSymbol.multiplier() < 1.0) {
                    message = "Drei " + winningSymbol.label() + " – " + formatMultiplier(winningSymbol.multiplier())
                            + ". Rückzahlung: " + payout + " Coins. Du hast einen Teil des Einsatzes verloren.";
                } else {
                    message = "GEWINN! Drei " + winningSymbol.label() + " – " + formatMultiplier(winningSymbol.multiplier())
                            + ". Auszahlung: " + payout + " Coins.";
                }
                state.player.sendSystemMessage(Component.literal(message).withStyle(
                        winningSymbol.multiplier() < 1.0 ? ChatFormatting.RED : ChatFormatting.GREEN));
            } else {
                state.menu.setStatus("Keine 3 gleichen Symbole", ChatFormatting.RED);
                state.player.sendSystemMessage(Component.literal("Kein Gewinn – die drei Symbole stimmen nicht überein.")
                        .withStyle(ChatFormatting.RED));
            }

            state.player.sendSystemMessage(Component.literal("Neuer Kontostand: " + newBalance + " Coins.")
                    .withStyle(ChatFormatting.AQUA));
            iterator.remove();
        }
    }

    public static OreSymbol randomSymbol() {
        return SYMBOLS.get(RANDOM.nextInt(SYMBOLS.size()));
    }

    private static OreSymbol[] randomSymbols() {
        return new OreSymbol[]{randomSymbol(), randomSymbol(), randomSymbol()};
    }

    public static String formatMultiplier(double value) {
        return String.format(java.util.Locale.ROOT, "%.1fx", value);
    }

    public static long getBalance(UUID playerId) {
        return BALANCES.computeIfAbsent(playerId, ignored -> START_BALANCE);
    }

    private static void setBalance(UUID playerId, long newBalance) {
        BALANCES.put(playerId, Math.max(0L, Math.min(MAX_BALANCE, newBalance)));
        saveBalances();
    }

    private static void addBalance(UUID playerId, long amount) {
        long current = getBalance(playerId);
        long safeAmount = Math.max(0L, amount);
        long next = safeAmount > MAX_BALANCE - current ? MAX_BALANCE : current + safeAmount;
        setBalance(playerId, next);
    }

    private static void loadBalances(MinecraftServer server) {
        BALANCES.clear();
        balanceFile = server.getWorldPath(LevelResource.ROOT).resolve("gamba-balances.properties");
        if (!Files.exists(balanceFile)) {
            LOGGER.info("Noch keine Gamba-Konten gefunden; neue Spieler starten mit {} Coins.", START_BALANCE);
            return;
        }

        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(balanceFile)) {
            properties.load(input);
            for (String key : properties.stringPropertyNames()) {
                try {
                    UUID playerId = UUID.fromString(key);
                    long balance = Long.parseLong(properties.getProperty(key));
                    BALANCES.put(playerId, Math.max(0L, Math.min(MAX_BALANCE, balance)));
                } catch (IllegalArgumentException ignored) {
                    LOGGER.warn("Ungültiger Gamba-Kontoeintrag wurde übersprungen: {}", key);
                }
            }
            LOGGER.info("{} Gamba-Konten geladen.", BALANCES.size());
        } catch (IOException exception) {
            LOGGER.error("Gamba-Konten konnten nicht geladen werden.", exception);
        }
    }

    private static void saveBalances() {
        if (balanceFile == null) {
            return;
        }
        Properties properties = new Properties();
        BALANCES.forEach((uuid, balance) -> properties.setProperty(uuid.toString(), Long.toString(balance)));
        try {
            Files.createDirectories(balanceFile.getParent());
            try (OutputStream output = Files.newOutputStream(balanceFile)) {
                properties.store(output, "Gamba Slot balances - virtual coins, not real money");
            }
        } catch (IOException exception) {
            LOGGER.error("Gamba-Konten konnten nicht gespeichert werden.", exception);
        }
    }

    public record OreSymbol(Item item, String label, double multiplier) {
    }

    private static final class SpinState {
        private final ServerPlayer player;
        private final UUID playerId;
        private final GambaMenu menu;
        private final long bet;
        private OreSymbol[] result;
        private final long finishAt;
        private long nextAnimationAt;

        private SpinState(ServerPlayer player, UUID playerId, GambaMenu menu, long bet,
                          long finishAt, long nextAnimationAt) {
            this.player = player;
            this.playerId = playerId;
            this.menu = menu;
            this.bet = bet;
            this.finishAt = finishAt;
            this.nextAnimationAt = nextAnimationAt;
        }
    }
}
