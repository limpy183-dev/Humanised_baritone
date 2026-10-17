package com.humanisedbaritone.behave;

import com.humanisedbaritone.Bot;
import com.humanisedbaritone.Log;
import com.humanisedbaritone.human.Rng;
import com.humanisedbaritone.settings.Settings;
import com.humanisedbaritone.world.Ent;
import com.humanisedbaritone.world.Inv;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Eats when hungry or hurt: picks sensible food, stops, eats at a human pace and puts the previous item back in hand. */
public final class Eating {
	private enum S { IDLE, SWAP, EAT, RESTORE }

	private static final Minecraft mc = Minecraft.getInstance();
	private static S state = S.IDLE;
	/** cooldown: pause after a meal or when there was nothing to eat from; poll: pause between routine checks for something to eat. */
	private static int slot = -1, prev, delay, ticks, cooldown, poll;
	private static boolean movedToHotbar, wasEmergency;

	private Eating() {
	}

	public static boolean active() {
		return state != S.IDLE;
	}

	/** A new life starts from a clean slate: a meal that was under way, or a pause left over, must not carry into it. */
	public static void reset() {
		state = S.IDLE;
		cooldown = poll = 0;
		wasEmergency = false;
	}

	/**
	 * Health is critical even counting the absorption buffer, and nothing is healing the player already. Right after a golden apple
	 * that is not the case any more: its regeneration is running, and standing still to eat the next one costs more hits than it gives.
	 */
	public static boolean emergency(LocalPlayer p) {
		return Settings.EAT_EMERGENCY.on() && p.getHealth() + p.getAbsorptionAmount() <= Settings.PANIC_HP.d() && !p.hasEffect(MobEffects.REGENERATION);
	}

	private static String key(ItemStack st) {
		var k = BuiltInRegistries.ITEM.getKey(st.getItem());
		return k == null ? "" : k.toString();
	}

	/** True if there is anything in the inventory that would heal or feed. */
	public static boolean canHeal() {
		return Inv.find(s -> s.has(DataComponents.FOOD)) >= 0 || Inv.count(s -> s.is(Items.TOTEM_OF_UNDYING)) > 0;
	}

	/** Inventory slot of the best food right now, or -1 when there is no reason to eat or nothing to eat. */
	private static int choose() {
		LocalPlayer p = mc.player;
		int food = p.getFoodData().getFoodLevel();
		boolean emergency = emergency(p);
		boolean hungry = food <= Settings.EAT_FOOD.i();
		boolean heal = Settings.EAT_HEAL.on() && p.getHealth() <= p.getMaxHealth() - 3 && food <= 17;
		if (!hungry && !heal && !emergency) return -1;
		if (Settings.EAT_SAFE.on() && !emergency && (Combat.fighting() || !Ent.living(9, e -> Ent.isTarget(e)).isEmpty())) return -1;
		int missing = 20 - food;
		int best = -1;
		double bestScore = -1e9;
		for (int i = 0; i < 36; i++) {
			ItemStack st = Inv.get(i);
			FoodProperties f = st.isEmpty() ? null : st.get(DataComponents.FOOD);
			if (f == null) continue;
			if (!p.canEat(f.canAlwaysEat()) && !emergency) continue;
			String k = key(st);
			boolean avoid = Settings.EAT_AVOID.has(k);
			boolean golden = k.contains("golden_apple");
			double score;
			if (emergency) {
				score = golden ? 1000 + f.saturation() : f.nutrition() + f.saturation();
			} else {
				if (avoid && food > 6) continue;
				score = Settings.EAT_PREFER.has(k) ? 500 : 0;
				score += f.nutrition() <= missing ? 100 + f.saturation() * 4 : -f.nutrition() * 2;
				if (avoid) score -= 300;
			}
			score += i < 9 ? 1 : 0;
			if (score > bestScore) {
				bestScore = score;
				best = i;
			}
		}
		return best;
	}

	/** Hotbar slot holding the same kind of food that was chosen from the main inventory. */
	private static int hotbarFoodLike(ItemStack wanted) {
		return Inv.findHotbar(st -> st.getItem() == wanted.getItem());
	}

	public static boolean tick() {
		LocalPlayer p = mc.player;
		if (!Settings.EAT.on() || !p.isAlive() || p.isCreative() || p.isSpectator()) {
			state = S.IDLE;
			return false;
		}
		boolean emergency = emergency(p);
		if (emergency && !wasEmergency) cooldown = poll = 0; // a fresh emergency does not wait for a pause that belonged to an earlier meal or check
		wasEmergency = emergency;
		switch (state) {
			case IDLE -> {
				if (cooldown > 0) {
					cooldown--;
					return false;
				}
				// the routine check is throttled, but not when health is critical: every tick waited is a hit taken
				if (poll > 0 && !emergency) {
					poll--;
					return false;
				}
				int want = choose();
				if (want < 0) {
					poll = 25;
					return false;
				}
				slot = want;
				prev = Inv.selected();
				movedToHotbar = false;
				delay = Rng.delayTicks(Settings.ACT_DELAY.d());
				state = S.SWAP;
				if (Settings.DEBUG.on()) Log.debug(String.format("eating %s at %.1f hp, food %d (tick %d)", key(Inv.get(want)), p.getHealth(), p.getFoodData().getFoodLevel(), p.tickCount));
				return true;
			}
			case SWAP -> {
				if (delay-- > 0) return true;
				if (slot >= 9 && !movedToHotbar) {
					ItemStack wanted = Inv.get(slot).copy();
					Inv.toHotbar(slot);
					movedToHotbar = true;
					delay = Rng.delayTicks(Settings.ACT_SWAP.d()) + 1;
					slot = hotbarFoodLike(wanted) >= 0 ? hotbarFoodLike(wanted) : -1;
					return true;
				}
				if (slot < 0) {
					int s = Inv.findHotbar(st -> st.has(DataComponents.FOOD));
					if (s < 0) {
						state = S.IDLE;
						cooldown = 60;
						return false;
					}
					slot = s;
				}
				Inv.select(slot);
				ticks = 0;
				delay = Rng.delayTicks(Settings.ACT_SWAP.d() * 0.6);
				state = S.EAT;
				return true;
			}
			case EAT -> {
				if (delay-- > 0) return true;
				ticks++;
				if (ticks > 3 && !p.isUsingItem() || ticks > 90) {
					delay = Rng.delayTicks(Settings.ACT_SWAP.d());
					state = S.RESTORE;
					return true;
				}
				Bot.ctl.use = true;
				if (Settings.EAT_SAFE.on() && !Settings.EAT_EMERGENCY.on() && Combat.fighting()) state = S.RESTORE;
				return true;
			}
			case RESTORE -> {
				if (delay-- > 0) return true;
				Inv.select(prev);
				cooldown = 30;
				state = S.IDLE;
				return false;
			}
		}
		return false;
	}
}
