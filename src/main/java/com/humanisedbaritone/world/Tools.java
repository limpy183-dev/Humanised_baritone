package com.humanisedbaritone.world;

import com.humanisedbaritone.settings.Settings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.effect.MobEffectUtil;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.tags.FluidTags;

/** Tool choice: how long a block takes to break with each item, and which item is the best weapon. */
public final class Tools {
	private static final Minecraft mc = Minecraft.getInstance();
	public static final double INF = 1e6;

	private Tools() {
	}

	/** Efficiency bonus an enchanted stack would add to the mining speed (vanilla: level squared plus one). */
	public static int efficiencyBonus(ItemStack st) {
		var ench = st.get(DataComponents.ENCHANTMENTS);
		if (ench == null || ench.isEmpty() || mc.level == null) return 0;
		var holder = mc.level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).get(Enchantments.EFFICIENCY);
		if (holder.isEmpty()) return 0;
		int lvl = ench.getLevel(holder.get());
		return lvl > 0 ? lvl * lvl + 1 : 0;
	}

	/** Player dependent mining modifiers, captured on the main thread so worker threads can compute break times. */
	public record Mods(float haste, float fatigue, float breakSpeed, float submerged) {
		public static Mods now() {
			LocalPlayer p = mc.player;
			float haste = MobEffectUtil.hasDigSpeed(p) ? 1.0F + (MobEffectUtil.getDigSpeedAmplification(p) + 1) * 0.2F : 1.0F;
			float fatigue = 1.0F;
			if (p.hasEffect(MobEffects.MINING_FATIGUE)) {
				fatigue = switch (p.getEffect(MobEffects.MINING_FATIGUE).getAmplifier()) {
					case 0 -> 0.3F;
					case 1 -> 0.09F;
					case 2 -> 0.0027F;
					default -> 8.1E-4F;
				};
			}
			float sub = p.isEyeInFluid(FluidTags.WATER) ? (float) p.getAttribute(Attributes.SUBMERGED_MINING_SPEED).getValue() : 1.0F;
			return new Mods(haste, fatigue, (float) p.getAttributeValue(Attributes.BLOCK_BREAK_SPEED), sub);
		}
	}

	/** Ticks needed to break {@code s} with {@code st} while standing on the ground. Pure function: safe on any thread. */
	public static double ticks(BlockState s, ItemStack st, int effBonus, Mods m) {
		float hardness = s.getDestroySpeed(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
		if (hardness < 0) return INF;
		if (hardness == 0) return 1;
		float speed = st.getDestroySpeed(s);
		if (speed > 1.0F) speed += effBonus;
		speed *= m.haste * m.fatigue * m.breakSpeed * m.submerged;
		boolean correct = !s.requiresCorrectToolForDrops() || st.isCorrectToolForDrops(s);
		float progress = speed / hardness / (correct ? 30 : 100);
		if (progress <= 0) return INF;
		return Math.max(1, Math.ceil(1.0 / progress));
	}

	public static double ticks(BlockState s, ItemStack st) {
		return ticks(s, st, efficiencyBonus(st), Mods.now());
	}

	/** Result of a tool search. */
	public record Pick(int slot, double ticks) {
	}

	/** Best inventory slot (0-35) to mine {@code s} with, honouring the durability protection setting. */
	public static Pick best(BlockState s) {
		double emptyHand = ticks(s, ItemStack.EMPTY);
		int bestSlot = -1;
		double bestTicks = INF, bestScore = INF;
		for (int i = 0; i < 36; i++) {
			ItemStack st = Inv.get(i);
			if (st.isEmpty() || !Settings.AUTO_TOOL.on() && i != Inv.selected() || worn(st)) continue;
			double t = ticks(s, st);
			// prefer the hotbar and the held slot on (almost) equal speed so tool swaps stay rare
			double score = t + (i < 9 ? 0 : 0.6) - (i == Inv.selected() ? 0.4 : 0);
			if (score < bestScore) {
				bestScore = score;
				bestTicks = t;
				bestSlot = i;
			}
		}
		if (bestSlot < 0 || bestTicks >= emptyHand) return new Pick(-1, emptyHand);
		return new Pick(bestSlot, bestTicks);
	}

	/** Durability below the "protect worn tools" threshold. */
	public static boolean worn(ItemStack st) {
		if (!st.isDamageableItem()) return false;
		int keep = Settings.TOOL_KEEP.i();
		if (keep <= 0) return false;
		int left = st.getMaxDamage() - st.getDamageValue();
		return left * 100 < st.getMaxDamage() * keep || left <= 2;
	}

	/** Damage per second of an item used as a weapon, including its cooldown. */
	public static double weaponScore(ItemStack st) {
		if (st.isEmpty()) return 1.0 * 4.0 / 4.0;
		ItemAttributeModifiers mods = st.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
		double dmg = mods.compute(Attributes.ATTACK_DAMAGE, 1.0, EquipmentSlot.MAINHAND);
		double spd = mods.compute(Attributes.ATTACK_SPEED, 4.0, EquipmentSlot.MAINHAND);
		if (worn(st)) dmg *= 0.3;
		return dmg * spd;
	}

	/** Best weapon slot (0-35), or -1 when bare hands are best. */
	public static int bestWeapon() {
		int slot = -1;
		double best = weaponScore(ItemStack.EMPTY);
		for (int i = 0; i < 36; i++) {
			ItemStack st = Inv.get(i);
			if (st.isEmpty()) continue;
			double sc = weaponScore(st);
			if (sc > best + 0.05) {
				best = sc;
				slot = i;
			}
		}
		return slot;
	}
}
