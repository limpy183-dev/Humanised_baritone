package com.humanisedbaritone.world;

import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;

/** Inventory helpers. Slot numbers are {@code Inventory} indices: 0-8 hotbar, 9-35 main, 36-39 armor, 40 offhand. */
public final class Inv {
	private static final Minecraft mc = Minecraft.getInstance();

	private Inv() {
	}

	public static ItemStack get(int slot) {
		return mc.player.getInventory().getItem(slot);
	}

	public static int selected() {
		return mc.player.getInventory().getSelectedSlot();
	}

	public static ItemStack held() {
		return mc.player.getInventory().getSelectedItem();
	}

	public static void select(int hotbar) {
		if (hotbar >= 0 && hotbar < 9 && hotbar != selected()) mc.player.getInventory().setSelectedSlot(hotbar);
	}

	/** Slot index in the player's InventoryMenu for an Inventory index. */
	public static int menuSlot(int slot) {
		if (slot < 9) return InventoryMenu.USE_ROW_SLOT_START + slot;
		if (slot < 36) return slot;
		if (slot < 40) return InventoryMenu.ARMOR_SLOT_END - 1 - (slot - 36); // feet 8, legs 7, chest 6, head 5
		return InventoryMenu.SHIELD_SLOT;
	}

	/** First slot 0-35 whose stack matches, or -1. Hotbar is searched first. */
	public static int find(Predicate<ItemStack> p) {
		for (int i = 0; i < 36; i++) {
			ItemStack st = get(i);
			if (!st.isEmpty() && p.test(st)) return i;
		}
		return -1;
	}

	public static int findHotbar(Predicate<ItemStack> p) {
		for (int i = 0; i < 9; i++) {
			ItemStack st = get(i);
			if (!st.isEmpty() && p.test(st)) return i;
		}
		return -1;
	}

	public static int count(Predicate<ItemStack> p) {
		int n = 0;
		for (int i = 0; i < 41; i++) {
			ItemStack st = get(i);
			if (!st.isEmpty() && p.test(st)) n += st.getCount();
		}
		return n;
	}

	/** Puts the item of {@code slot} into hotbar slot {@code hotbar} by swapping, like pressing the number key over it. */
	public static void swapToHotbar(int slot, int hotbar) {
		LocalPlayer p = mc.player;
		mc.gameMode.handleContainerInput(p.inventoryMenu.containerId, menuSlot(slot), hotbar, ContainerInput.SWAP, p);
	}

	/** Makes sure the stack in {@code slot} is in the hotbar. Returns the hotbar index, or -1 if a swap was just issued. */
	public static int toHotbar(int slot) {
		if (slot < 9) return slot;
		int target = leastUsefulHotbar();
		swapToHotbar(slot, target);
		return -1;
	}

	/**
	 * Creative mode: a stack of the item appears in the hotbar (an empty slot, else the least important one), the way the creative
	 * inventory screen does it: in the client's inventory, and by packet in the server's. (Only the packet would leave the client
	 * believing the slot still holds the old item, while the server places the new one.) Returns the hotbar index.
	 */
	public static int give(net.minecraft.world.item.Item it) {
		int slot = leastUsefulHotbar();
		ItemStack st = new ItemStack(it, Math.min(64, it.getDefaultMaxStackSize()));
		mc.player.getInventory().setItem(slot, st);
		mc.gameMode.handleCreativeModeItemAdd(st, 36 + slot);
		return slot;
	}

	/** A hotbar slot that is empty or holds the least important item. */
	private static int leastUsefulHotbar() {
		int best = 8, bestScore = Integer.MAX_VALUE;
		for (int i = 8; i >= 0; i--) {
			ItemStack st = get(i);
			int score = st.isEmpty() ? -1 : st.getCount() > 1 ? 1 : 2;
			if (i == selected()) score += 5;
			if (score < bestScore) {
				bestScore = score;
				best = i;
			}
		}
		return best;
	}
}
