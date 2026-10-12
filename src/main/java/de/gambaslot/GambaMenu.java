package de.gambaslot;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.UUID;

public final class GambaMenu extends ChestMenu {
    private static final int GUI_SIZE = 54;
    private static final int STATUS_SLOT = 40;
    private static final int BALANCE_SLOT = 45;
    private static final int BET_SLOT = 47;
    private static final int SPIN_SLOT = 49;
    private static final int CLOSE_SLOT = 53;

    private final SimpleContainer display;
    private final UUID ownerId;
    private final long bet;

    public GambaMenu(int syncId, Inventory playerInventory, Player owner, long bet) {
        this(syncId, playerInventory, owner, bet, new SimpleContainer(GUI_SIZE));
    }

    private GambaMenu(int syncId, Inventory playerInventory, Player owner, long bet, SimpleContainer display) {
        super(MenuType.GENERIC_9x6, syncId, playerInventory, display, 6);
        this.display = display;
        this.ownerId = owner.getUUID();
        this.bet = bet;
        buildLayout();
    }

    private void buildLayout() {
        // Blue glass fills every unused slot, giving the native chest screen a consistent theme.
        for (int slot = 0; slot < GUI_SIZE; slot++) {
            display.setItem(slot, named(Items.BLUE_STAINED_GLASS_PANE, " ", ChatFormatting.BLUE));
        }

        display.setItem(4, named(Items.DIAMOND, "GAMBA SLOT", ChatFormatting.AQUA));

        int[] paytableSlots = {10, 11, 12, 13, 14, 15};
        for (int i = 0; i < GambaSlotMod.SYMBOLS.size(); i++) {
            GambaSlotMod.OreSymbol symbol = GambaSlotMod.SYMBOLS.get(i);
            String detail = symbol.label() + " — " + GambaSlotMod.formatMultiplier(symbol.multiplier());
            if (symbol.multiplier() < 1.0) {
                detail += " (Verlust)";
            } else if (symbol.multiplier() >= 25.0) {
                detail += " (JACKPOT)";
            }
            display.setItem(paytableSlots[i], named(symbol.item(), detail, ChatFormatting.AQUA));
        }

        display.setItem(20, named(Items.ARROW, "Walze 1 →", ChatFormatting.BLUE));
        display.setItem(24, named(Items.ARROW, "← Walze 3", ChatFormatting.BLUE));
        setReels(GambaSlotMod.randomSymbol(), GambaSlotMod.randomSymbol(), GambaSlotMod.randomSymbol());
        setStatus("Bereit zum Drehen", ChatFormatting.AQUA);
        updateBalanceDisplay(GambaSlotMod.getBalance(ownerId));
        display.setItem(BET_SLOT, named(Items.GOLD_INGOT, "Einsatz: " + bet + " Coins", ChatFormatting.GOLD));
        setSpinButton(false);
        display.setItem(CLOSE_SLOT, named(Items.BARRIER, "Menü schließen", ChatFormatting.RED));
    }

    public void setReels(GambaSlotMod.OreSymbol first, GambaSlotMod.OreSymbol second, GambaSlotMod.OreSymbol third) {
        display.setItem(21, named(first.item(), first.label(), ChatFormatting.WHITE));
        display.setItem(22, named(second.item(), second.label(), ChatFormatting.WHITE));
        display.setItem(23, named(third.item(), third.label(), ChatFormatting.WHITE));
    }

    public void setReels(GambaSlotMod.OreSymbol[] symbols) {
        setReels(symbols[0], symbols[1], symbols[2]);
    }

    public void setStatus(String text, ChatFormatting color) {
        display.setItem(STATUS_SLOT, named(Items.PAPER, text, color));
    }

    public void updateBalanceDisplay(long balance) {
        display.setItem(BALANCE_SLOT, named(Items.EMERALD, "Kontostand: " + balance + " Coins", ChatFormatting.GREEN));
    }

    public void setSpinButton(boolean spinning) {
        if (spinning) {
            display.setItem(SPIN_SLOT, named(Items.CLOCK, "Walzen drehen ...", ChatFormatting.YELLOW));
        } else {
            display.setItem(SPIN_SLOT, named(Items.LIME_DYE, "DREHEN", ChatFormatting.GREEN));
        }
    }

    private static ItemStack named(Item item, String name, ChatFormatting color) {
        ItemStack stack = new ItemStack(item);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(name).withStyle(color));
        return stack;
    }

    @Override
    public void clicked(int slotId, int button, ClickType clickType, Player player) {
        // The chest contains display-only items. Intercept all top slots to prevent taking them.
        if (slotId >= 0 && slotId < GUI_SIZE) {
            if (clickType == ClickType.PICKUP && button == 0 && player instanceof ServerPlayer serverPlayer) {
                if (slotId == SPIN_SLOT) {
                    GambaSlotMod.startSpin(serverPlayer, this, bet);
                } else if (slotId == CLOSE_SLOT) {
                    serverPlayer.closeContainer();
                }
            }
            return;
        }
        super.clicked(slotId, button, clickType, player);
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }
}
