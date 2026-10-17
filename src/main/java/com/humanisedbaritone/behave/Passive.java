package com.humanisedbaritone.behave;

import com.humanisedbaritone.Bot;
import com.humanisedbaritone.human.Rng;
import com.humanisedbaritone.settings.Settings;
import com.humanisedbaritone.task.CollectTask;
import com.humanisedbaritone.task.Tasks;
import com.humanisedbaritone.world.Ent;
import com.humanisedbaritone.world.Inv;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.equipment.Equippable;

/**
 * Background chores while nothing more urgent happens: wear better armor, keep a totem in the off-hand, walk over to
 * items lying around. Inventory changes are done click by click with human pauses in between.
 */
public final class Passive {
	private static final Minecraft mc = Minecraft.getInstance();
	private static final EquipmentSlot[] ARMOR = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
	private static final int[] MENU = {5, 6, 7, 8};
	private static int cooldown = 100, step, pendingSlot = -1, pendingArmor = -1, delay;
	private static boolean working;

	private Passive() {
	}

	public static boolean tick() {
		LocalPlayer p = mc.player;
		if (!Settings.PASSIVE.on() || !p.isAlive() || p.isSpectator()) return false;
		if (working) return gearStep();
		if (--cooldown > 0) return false;
		cooldown = 40 + Rng.range(0, 40);
		if (!Combat.fighting() && !Eating.active() && mc.gui.screen() == null && startGear()) return true;
		if (Settings.JUNK.on() && mc.gui.screen() == null && dropJunk()) return true;
		if (Settings.PICKUP.on() && Tasks.current() == null && !Ent.items(Settings.PICKUP_RADIUS.d()).isEmpty()) {
			Tasks.start(new CollectTask(Settings.PICKUP_RADIUS.d(), true, false));
			return false;
		}
		return false;
	}

	/** With the inventory nearly full, throws one stack of junk away the way a player would with ctrl+Q over the slot. */
	private static boolean dropJunk() {
		int free = 0;
		for (int i = 0; i < 36; i++) if (Inv.get(i).isEmpty()) free++;
		if (free > 2) return false;
		for (int i = 35; i >= 9; i--) {
			ItemStack st = Inv.get(i);
			if (st.isEmpty()) continue;
			var k = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(st.getItem());
			if (k != null && Settings.JUNK_ITEMS.has(k.toString())) {
				mc.gameMode.handleContainerInput(mc.player.inventoryMenu.containerId, Inv.menuSlot(i), 1, ContainerInput.THROW, mc.player);
				return true;
			}
		}
		return false;
	}

	// ------------------------------------------------------------------ gear

	private static double armorScore(ItemStack st, EquipmentSlot slot) {
		if (st.isEmpty()) return 0;
		ItemAttributeModifiers m = st.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
		double a = m.compute(Attributes.ARMOR, 0, slot) + 0.5 * m.compute(Attributes.ARMOR_TOUGHNESS, 0, slot);
		if (st.isDamageableItem()) a *= 0.7 + 0.3 * (st.getMaxDamage() - st.getDamageValue()) / (double) st.getMaxDamage();
		return a;
	}

	private static boolean startGear() {
		LocalPlayer p = mc.player;
		if (Settings.TOTEM.on() && !p.getOffhandItem().is(Items.TOTEM_OF_UNDYING)) {
			int t = Inv.find(s -> s.is(Items.TOTEM_OF_UNDYING));
			if (t >= 0) {
				pendingSlot = t;
				pendingArmor = -2;
				step = 0;
				delay = Rng.delayTicks(Settings.ACT_DELAY.d());
				working = true;
				return true;
			}
		}
		if (!Settings.ARMOR.on()) return false;
		for (int a = 0; a < 4; a++) {
			if (ARMOR[a] == EquipmentSlot.CHEST && !ElytraJump.glider(p).isEmpty()) continue; // an elytra is worn on purpose: a chestplate is not an upgrade, and putting one on in mid-air would end the flight
			double cur = armorScore(p.getItemBySlot(ARMOR[a]), ARMOR[a]);
			int best = -1;
			double bestScore = cur + 0.25;
			for (int i = 0; i < 36; i++) {
				ItemStack st = Inv.get(i);
				Equippable eq = st.isEmpty() ? null : st.get(DataComponents.EQUIPPABLE);
				if (eq == null || eq.slot() != ARMOR[a]) continue;
				double sc = armorScore(st, ARMOR[a]);
				if (sc > bestScore) {
					bestScore = sc;
					best = i;
				}
			}
			if (best >= 0) {
				pendingSlot = best;
				pendingArmor = a;
				step = 0;
				delay = Rng.delayTicks(Settings.ACT_DELAY.d());
				working = true;
				return true;
			}
		}
		return false;
	}

	private static boolean gearStep() {
		LocalPlayer p = mc.player;
		if (Combat.fighting() || mc.gui.screen() != null) {
			working = false;
			return false;
		}
		if (delay-- > 0) return true;
		var gm = mc.gameMode;
		int cid = p.inventoryMenu.containerId;
		if (pendingArmor == -2) { // totem into the off-hand
			gm.handleContainerInput(cid, Inv.menuSlot(pendingSlot), 40, ContainerInput.SWAP, p);
			working = false;
			return true;
		}
		int armorSlot = MENU[pendingArmor];
		switch (step) {
			case 0 -> gm.handleContainerInput(cid, Inv.menuSlot(pendingSlot), 0, ContainerInput.PICKUP, p);
			case 1 -> gm.handleContainerInput(cid, armorSlot, 0, ContainerInput.PICKUP, p);
			case 2 -> {
				if (!p.containerMenu.getCarried().isEmpty()) gm.handleContainerInput(cid, Inv.menuSlot(pendingSlot), 0, ContainerInput.PICKUP, p);
				working = false;
				return true;
			}
			default -> working = false;
		}
		step++;
		delay = Rng.delayTicks(170);
		return true;
	}
}
