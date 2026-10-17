package com.humanisedbaritone.task;

import com.humanisedbaritone.Bot;
import com.humanisedbaritone.Interact;
import com.humanisedbaritone.Log;
import com.humanisedbaritone.human.Rng;
import com.humanisedbaritone.path.Goal;
import com.humanisedbaritone.path.Nav;
import com.humanisedbaritone.settings.Settings;
import com.humanisedbaritone.world.BlockScan;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Puts items into the nearest chest, or takes some out. Works through the real container menu: opens the chest with a
 * right click, moves stacks one at a time with human pauses in between and closes it again.
 */
public final class StashTask extends Task {
	private enum Ph { FIND, GOTO, OPEN, MOVE, CLOSE }

	private static final Minecraft mc = Minecraft.getInstance();
	private final boolean deposit;
	private final String pattern;
	private final int count;
	private Ph ph = Ph.FIND;
	private BlockPos chest;
	private Interact.UseOp open;
	private int ticks, wait, cursor, moved;

	/** @param pattern item name fragment; for deposits empty means "everything that is not essential" */
	public StashTask(boolean deposit, String pattern, int count) {
		this.deposit = deposit;
		this.pattern = pattern.toLowerCase(Locale.ROOT);
		this.count = count;
	}

	@Override public String name() {
		return deposit ? "Storing items in a chest" : "Fetching " + pattern + " from a chest";
	}

	@Override public String tag() {
		return "STASH";
	}

	@Override public void start() {
		ph = Ph.FIND;
		chest = null;
		open = null;
		ticks = 0;
		wait = 0;
		cursor = 0;
	}

	@Override public void stop() {
		Bot.nav.cancel();
		Bot.containerOk = false;
		if (mc.player != null && mc.player.containerMenu != mc.player.inventoryMenu) mc.player.closeContainer();
	}

	@Override public String status() {
		return moved + " stacks moved";
	}

	private static boolean isContainer(BlockState s) {
		return s.is(Blocks.CHEST) || s.is(Blocks.TRAPPED_CHEST) || s.is(Blocks.BARREL) || s.is(BlockTags.SHULKER_BOXES);
	}

	private static boolean essential(ItemStack st) {
		return st.has(DataComponents.TOOL) || st.has(DataComponents.FOOD) || st.has(DataComponents.EQUIPPABLE) || st.is(Items.WATER_BUCKET) || st.is(Items.TOTEM_OF_UNDYING)
			|| st.is(Items.ENDER_PEARL) || st.is(Items.TORCH) || st.is(Items.BOW) || st.is(Items.ARROW) || st.is(Items.SHIELD) || st.is(Items.FISHING_ROD)
			|| Settings.PLACEABLE.has(String.valueOf(BuiltInRegistries.ITEM.getKey(st.getItem())));
	}

	private boolean matches(ItemStack st) {
		if (st.isEmpty()) return false;
		String key = String.valueOf(BuiltInRegistries.ITEM.getKey(st.getItem()));
		if (!pattern.isEmpty()) return key.contains(pattern);
		return deposit && !essential(st);
	}

	@Override public boolean tick() {
		LocalPlayer p = mc.player;
		switch (ph) {
			case FIND -> {
				List<BlockPos> found = BlockScan.find(p.blockPosition(), 40, StashTask::isContainer, 10);
				if (found.isEmpty()) {
					Log.warn("No chest within 40 blocks.");
					return true;
				}
				chest = found.get(0);
				Bot.nav.setGoal(new Goal.Reach(chest, 2.8));
				ph = Ph.GOTO;
				ticks = 0;
			}
			case GOTO -> {
				ticks++;
				Nav.State s = Bot.nav.tick();
				if (s == Nav.State.ARRIVED || Interact.pickAim(chest, null) != null && ticks > 3) {
					Bot.nav.cancel();
					Bot.containerOk = true;
					open = new Interact.UseOp(chest, () -> p.containerMenu != p.inventoryMenu);
					ph = Ph.OPEN;
					ticks = 0;
				} else if (s == Nav.State.FAILED || ticks > 1200) {
					Log.warn("Cannot reach the chest.");
					return true;
				}
			}
			case OPEN -> {
				Interact.R r = open.tick();
				if (r == Interact.R.DONE) {
					ph = Ph.MOVE;
					wait = Rng.delayTicks(350);
					cursor = 0;
				} else if (r == Interact.R.FAIL || ++ticks > 200) {
					Log.warn("Could not open the chest.");
					Bot.containerOk = false;
					return true;
				}
			}
			case MOVE -> {
				if (p.containerMenu == p.inventoryMenu) {
					ph = Ph.FIND;
					return false;
				}
				if (wait-- > 0) return false;
				AbstractContainerMenu m = p.containerMenu;
				int total = m.slots.size(), top = total - 36;
				// deposits scan the player's part of the menu (hotbar included: new items land there first), withdrawals the chest's own slots
				int from = deposit ? top : 0, to = deposit ? total : top;
				boolean did = false;
				while (cursor < to - from && !did) {
					int idx = from + cursor++;
					ItemStack st = m.getSlot(idx).getItem();
					if (!matches(st)) continue;
					if (!deposit && count > 0 && moved >= count) continue;
					mc.gameMode.handleContainerInput(m.containerId, idx, 0, ContainerInput.QUICK_MOVE, p);
					moved++;
					did = true;
				}
				wait = Rng.delayTicks(220);
				if (!did) {
					ph = Ph.CLOSE;
					wait = Rng.delayTicks(300);
				}
			}
			case CLOSE -> {
				if (wait-- > 0) return false;
				p.closeContainer();
				Bot.containerOk = false;
				Log.info(deposit ? "Stored " + moved + " stacks." : "Took " + moved + " stacks.");
				return true;
			}
		}
		return false;
	}
}
