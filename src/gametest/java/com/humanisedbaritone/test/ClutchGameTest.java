package com.humanisedbaritone.test;

import com.humanisedbaritone.Bot;
import com.humanisedbaritone.Commands;
import com.humanisedbaritone.behave.Clutch;
import com.humanisedbaritone.path.Nav;
import com.humanisedbaritone.settings.Settings;
import com.humanisedbaritone.world.Inv;
import java.util.Locale;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Items;

/**
 * The water bucket clutch has to work on every fall of a session, not only the first one after joining the world: several
 * falls in a row without a respawn, from different heights, accidental (a teleport into the air) and planned (a route that
 * steps off a tower). Each one must cost no health and leave the bucket full again.
 *
 * <p>{@code planned}: besides that the camera must not turn while the player falls towards the clutch. The route code used to chase the
 * cell it was dropping into, which is behind the player once it has walked off the edge, so the camera swung half way round in the air and
 * the clutch placed the water, and later looked for it, with a random heading: it took the water back 19-31 ticks late in 8 rounds of 14, and
 * took the fall in 1 of 14. {@code HB_REPEAT=n} runs n rounds, {@code HB_DIR=all} walks off towards every side in turn, {@code HB_TOWER=h} sets
 * the height, {@code HB_FPS=n} the frame limit, {@code HB_TRACE=2} prints a line per tick with the view, the requested aim, the keys and the water.
 *
 * <p>{@code edge}: the water goes where the crosshair, pointing down from the middle of the player, meets a block, so a fall that ends
 * on the rim of one (middle beyond the edge) cannot be cushioned. With the landing correction on the bot must drift in the air to
 * the middle of the block and clutch; with it off it must not move sideways at all.
 */
public class ClutchGameTest extends BotTest {
	private static final double X = 200.5, Z = 200.5;
	/** A 1x1 pillar and a 3x3 platform, both 25 blocks tall: the tops are at y = -35. */
	private static final int PILLAR = 230, PLATFORM = 245, TOP = -35;

	@Override
	public void runTest(ClientGameTestContext ctx) {
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			ctx.getInput().resizeWindow(1280, 720);
			ctx.waitTicks(30);
			cmds(sp, "gamerule advance_time false", "time set day", "difficulty peaceful");
			revive(ctx, sp);
			load(ctx, sp, 190, 190, 280, 280);
			cmd(sp, "fill 258 -60 258 264 -51 264 air");
			cmd(sp, "tp @a " + X + " -60 " + Z);
			ctx.waitTicks(20);
			botOn(ctx);
			ctx.runOnClient(mc -> Settings.VAR_FIDGET.set(0.0)); // an idle hop in the wrong tick would read as a landing
			if (TRACE_ALL) com.humanisedbaritone.human.LookController.probe = a -> frames++; // rendered frames per tick, for the trace
			// HB_FPS=n: the frame limit. After a minute without input the game drops to 30 fps (10 after ten minutes), and the camera must not depend on it
			String fps = System.getenv("HB_FPS");
			if (fps != null) ctx.runOnClient(mc -> mc.getFramerateLimitTracker().setFramerateLimit(Integer.parseInt(fps)));
			// never a respawn in between: a new life starts the clutch afresh, which is exactly what hid the bug
			int[] heights = {12, 20, 20, 45, 90, 20};
			if (only("fall")) for (int i = 0; i < heights.length; i++) fall(ctx, sp, i + 1, heights[i]);
			if (only("planned")) planned(ctx, sp, 1);
			if (only("fall") && only("planned")) fall(ctx, sp, heights.length + 1, 15);
			if (only("planned")) planned(ctx, sp, 2);
			// HB_REPEAT=n: n rounds of the planned drop in all (the first two above), for judging a failure that comes and goes
			for (int i = 3; i <= Integer.parseInt(System.getenv().getOrDefault("HB_REPEAT", "0")); i++) if (only("planned")) planned(ctx, sp, i);
			if (only("edge")) edges(ctx, sp);
			if (only("current")) currents(ctx, sp);
			finish();
		}
	}

	/**
	 * The reason the clutch crouches while the water is out: left standing in the source block in an edge cell of a platform the player is carried
	 * off by the current, a crouching one stays put. Plain game mechanics, with the bot switched off and the key held by hand.
	 */
	private void currents(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		cmd(sp, "fill " + PLATFORM + " -60 " + PLATFORM + " " + (PLATFORM + 2) + " " + (TOP - 1) + " " + (PLATFORM + 2) + " stone");
		ctx.runOnClient(mc -> Bot.setEnabled(false));
		for (boolean sneak : new boolean[]{false, true}) {
			cmds(sp, "gamemode survival @a", "effect give @a minecraft:resistance 100 4 true", "tp @a " + (PLATFORM + 2.5) + " " + TOP + " " + (PLATFORM + 1.5) + " 0 0");
			ctx.waitTicks(10);
			cmd(sp, "setblock " + (PLATFORM + 2) + " " + TOP + " " + (PLATFORM + 1) + " minecraft:water");
			ctx.runOnClient(mc -> mc.options.keyShift.setDown(sneak));
			ctx.waitTicks(60);
			double[] pos = ctx.computeOnClient(mc -> new double[]{mc.player.getX(), mc.player.getY(), mc.player.getZ()});
			boolean stayed = pos[1] > TOP - 0.1;
			report(sneak ? "current-crouching-stays" : "current-standing-is-carried-off", stayed == sneak, String.format(Locale.ROOT, "ended at %.2f %.2f %.2f", pos[0], pos[1], pos[2]));
			ctx.runOnClient(mc -> mc.options.keyShift.setDown(false));
			cmd(sp, "fill " + (PLATFORM - 2) + " -60 " + (PLATFORM - 2) + " " + (PLATFORM + 6) + " " + TOP + " " + (PLATFORM + 6) + " minecraft:air replace minecraft:water");
		}
		ctx.runOnClient(mc -> Bot.setEnabled(true));
	}

	/** Falls that end on the rim of a pillar and of a platform, from every side, with the landing correction on, then once with it off. */
	private void edges(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		cmds(sp, "fill " + PILLAR + " -60 " + PILLAR + " " + PILLAR + " " + (TOP - 1) + " " + PILLAR + " stone",
			"fill " + PLATFORM + " -60 " + PLATFORM + " " + (PLATFORM + 2) + " " + (TOP - 1) + " " + (PLATFORM + 2) + " stone");
		ctx.waitTicks(10);
		double c = PILLAR + 0.5, m = PLATFORM + 1.5;
		// the middle of the player is 0.2 beyond the edge: the box overlaps the rim by a tenth of a block. The landing has to be within 0.25 of the middle of the pillar
		rim(ctx, sp, "edge-pillar-east", c + 0.7, c, 0, c, c, 0.25, 0, 0);
		rim(ctx, sp, "edge-pillar-west", c - 0.7, c, 90, c, c, 0.25, 0, 0);
		rim(ctx, sp, "edge-pillar-north", c, c - 0.7, 180, c, c, 0.25, 0, 0);
		rim(ctx, sp, "edge-pillar-south", c, c + 0.7, -90, c, c, 0.25, 0, 0);
		rim(ctx, sp, "edge-pillar-corner", c + 0.62, c - 0.62, 45, c, c, 0.3, 0, 0);
		// running off a ledge: a sideways speed of 0.15 blocks a tick carries the player 1.47 blocks while it falls, so from here it would come down on the east rim; the bot has to brake
		rim(ctx, sp, "edge-pillar-drift", c - 0.767, c, 0, c, c, 0.25, 0.15, 0);
		// HB_REPEAT=n: the pick-up delay is random, so a few rounds say more than one
		for (int r = Integer.parseInt(System.getenv().getOrDefault("HB_REPEAT", "1")); r > 0; r--) {
			// a platform: the middle is 0.15 beyond the rim, and has to end up at least 0.3 inside it
			rim(ctx, sp, "edge-platform-side", PLATFORM + 3.15, m, 135, m, m, 1.2, 0, 0);
			rim(ctx, sp, "edge-platform-corner", PLATFORM - 0.15, PLATFORM + 3.15, -135, m, m, 1.2, 0, 0);
			// the water put into a cell at the edge of a platform flows off it, and the current carries the player along: the bucket has to be back before that gets far
			rim(ctx, sp, "edge-cell-side", PLATFORM + 2.5, m, 0, m, m, 1.2, 0, 0);
			rim(ctx, sp, "edge-cell-corner", PLATFORM + 2.5, PLATFORM + 2.5, 90, m, m, 1.2, 0, 0);
		}
		ctx.runOnClient(mc -> Settings.CLUTCH_STEER.set(false));
		rimOff(ctx, sp, c + 0.7, c);
		ctx.runOnClient(mc -> Settings.CLUTCH_STEER.set(true));
	}

	/**
	 * Drops the player 18 blocks above a surface at (x, z), with a sideways speed (blocks per tick) once it is in the air, and checks the fall:
	 * cushioned, the bucket back, still on top of the block at the end, and the landing within {@code lim} of the middle (cx, cz).
	 */
	private void rim(ClientGameTestContext ctx, TestSingleplayerContext sp, String name, double x, double z, int yaw, double cx, double cz, double lim, double vx, double vz) {
		prepare(ctx, sp);
		dropAt(ctx, sp, x, TOP + 18, z, yaw);
		if (vx != 0 || vz != 0) ctx.runOnClient(mc -> mc.player.setDeltaMovement(vx, mc.player.getDeltaMovement().y, vz));
		boolean landed = land(ctx);
		double[] pos = ctx.computeOnClient(mc -> new double[]{mc.player.getX(), mc.player.getY(), mc.player.getZ()});
		double off = Math.max(Math.abs(landX - cx), Math.abs(landZ - cz));
		// still on top of the block at the end: swept off, the player would be down on the floor 25 blocks below
		report(name, landed && intact(ctx) && off <= lim && Math.abs(landY - TOP) < 0.05 && pos[1] > TOP - 0.1 && pos[1] < TOP + 2, bucketState(ctx)
			+ String.format(Locale.ROOT, " dropped at %.2f %.2f, touched down at %.2f %.2f height %.2f (%.2f off the middle), ended at %.2f %.2f %.2f", x, z, landX, landZ, landY, off, pos[0], pos[1], pos[2]));
	}

	/** With the correction off nothing moves the player sideways: the fall ends where it began. */
	private void rimOff(ClientGameTestContext ctx, TestSingleplayerContext sp, double x, double z) {
		prepare(ctx, sp);
		dropAt(ctx, sp, x, TOP + 18, z, 0);
		boolean landed = land(ctx);
		double moved = Math.hypot(landX - x, landZ - z);
		report("edge-off-does-not-steer", landed && moved < 0.05, String.format(Locale.ROOT, "minHp=%.1f dropped at %.2f %.2f, touched down at %.2f %.2f (moved %.2f)", minHp, x, z, landX, landZ, moved));
	}

	/** Teleports the player into the air at (x, y, z) facing {@code yaw} and returns once it is falling, with the measurements started. */
	private void dropAt(ClientGameTestContext ctx, TestSingleplayerContext sp, double x, int y, double z, int yaw) {
		cmd(sp, String.format(Locale.ROOT, "tp @a %.3f %d %.3f %d 0", x, y, z, yaw));
		until(ctx, "arriving", mc -> mc.player.getY() > y - 1, 60); // the old position still counts as standing on the ground until the teleport has come through
		begin();
		until(ctx, "in the air", mc -> !mc.player.onGround(), 60);
	}

	/** Gets the player to full health and one full water bucket, standing still on the ground. */
	private void prepare(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		if (!client(ctx, mc -> mc.player.isAlive())) revive(ctx, sp);
		cmds(sp, "gamemode survival @a", "effect clear @a", "effect give @a minecraft:instant_health 1 10", "clear @a", "give @a water_bucket");
		ctx.waitTicks(10);
	}

	/** Lowest health seen since the fall began, and whether the player was ever in water (the clutch really fired). */
	private float minHp;
	private boolean wet, touched, wasBusy, airborne;
	/** Where, and at which tick, the player first stood on something after the fall began; and the tick the clutch let go again (the water was picked up). */
	private double landX, landY, landZ;
	private int touchTick, doneTick;
	/** The heading when the player left the ground, and when the clutch put the water out; the middle of the area the water map of the trace shows. */
	private Float airYaw, placeYaw;
	private int mapX = 261, mapZ = 264;

	private static final boolean TRACE = System.getenv("HB_TRACE") != null, TRACE_ALL = "2".equals(System.getenv("HB_TRACE"));

	private void sample(net.minecraft.client.Minecraft mc) {
		var p = mc.player;
		minHp = Math.min(minHp, p.getHealth());
		wet |= p.isInWater();
		if (!p.onGround()) {
			airborne = true;
			if (airYaw == null) airYaw = p.getYRot();
		}
		if (Clutch.busy() && placeYaw == null) placeYaw = p.getYRot();
		if (!touched && airborne && p.onGround()) {
			touched = true;
			touchTick = p.tickCount;
			landX = p.getX();
			landY = p.getY();
			landZ = p.getZ();
		}
		if (Clutch.busy()) wasBusy = true;
		else if (wasBusy && touched && doneTick < 0) doneTick = p.tickCount;
		if (TRACE && (TRACE_ALL || p.tickCount % 3 == 0)) {
			var dm = p.getDeltaMovement();
			System.out.println(String.format("[HBTEST]    t=%d pos=%.2f %.2f %.2f v=%.3f %.3f %.3f ground=%b water=%b fall=%.1f busy=%b held=%s nav=%s", p.tickCount, p.getX(), p.getY(), p.getZ(), dm.x, dm.y, dm.z, p.onGround(),
				p.isInWater(), p.fallDistance, Clutch.busy(), p.getMainHandItem().getItem(), Bot.nav.state()));
			if (TRACE_ALL && (Clutch.busy() || !p.onGround())) System.out.println("[HBTEST]    cam " + camera(mc));
		}
	}

	private static Object field(Object o, Class<?> c, String name) {
		try {
			var f = c.getDeclaredField(name);
			f.setAccessible(true);
			return f.get(o);
		} catch (ReflectiveOperationException e) {
			return null;
		}
	}

	private long lastNs;
	private static volatile int frames;

	/** HB_TRACE=2: what the camera and the keys do this tick (view and requested direction, the key states, the cell the crosshair is on) and the water around the landing. */
	private String camera(net.minecraft.client.Minecraft mc) {
		var p = mc.player;
		var c = Bot.ctl;
		long now = System.nanoTime();
		String keys = (c.forward ? "W" : "-") + (c.left ? "A" : "-") + (c.back ? "S" : "-") + (c.right ? "D" : "-") + (c.jump ? "J" : "-") + (c.sneak ? "C" : "-") + (c.sprint ? "R" : "-");
		var h = com.humanisedbaritone.Interact.crosshair();
		var eye = p.getEyePosition();
		var src = mc.level.clip(new net.minecraft.world.level.ClipContext(eye, eye.add(p.getLookAngle().scale(com.humanisedbaritone.Interact.reach())), net.minecraft.world.level.ClipContext.Block.OUTLINE, net.minecraft.world.level.ClipContext.Fluid.SOURCE_ONLY, p));
		StringBuilder w = new StringBuilder();
		for (int x = mapX - 2; x <= mapX + 2; x++) {
			for (int z = mapZ - 2; z <= mapZ + 2; z++) {
				var fs = mc.level.getFluidState(new net.minecraft.core.BlockPos(x, -60, z));
				w.append(fs.isEmpty() ? '.' : fs.isSource() ? 'S' : (char) ('0' + fs.getAmount()));
			}
			if (x < mapX + 2) w.append('/');
		}
		String s = String.format(Locale.ROOT, "yaw=%.1f pitch=%.1f err=%.1f req=%.1f/%.1f keys=%s ms=%d frames=%d fps=%d wait=%s water=%s crosshair=%s src=%s fluids(rows x-2..x+2, columns z-2..z+2 round the landing)=%s",
			p.getYRot(), p.getXRot(), Bot.look.error(), field(Bot.look, com.humanisedbaritone.human.LookController.class, "reqYaw"), field(Bot.look, com.humanisedbaritone.human.LookController.class, "reqPitch"), keys,
			lastNs == 0 ? 0 : (now - lastNs) / 1_000_000, frames, mc.getFps(), field(null, Clutch.class, "wait"), field(null, Clutch.class, "water"), h == null ? "none" : h.getBlockPos().toShortString() + " " + h.getDirection(),
			src.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK ? src.getBlockPos().toShortString() : "none", w);
		lastNs = now;
		frames = 0;
		return s;
	}

	private void begin() {
		minHp = 20;
		wet = false;
		touched = wasBusy = airborne = false;
		doneTick = -1;
		airYaw = placeYaw = null;
	}

	/** Waits until the player is down and the clutch is over (water placed and picked up again); false if that never happened. */
	private boolean land(ClientGameTestContext ctx) {
		boolean landed = until(ctx, "landing and the bucket coming back", mc -> {
			sample(mc);
			return mc.player.onGround() && !mc.player.isInWater() && !Clutch.busy();
		}, 400);
		// the damage of a fall that was not cushioned reaches the client a tick after the landing: keep looking, and let the pick-up show in the inventory
		int[] n = {0};
		until(ctx, "settling", mc -> {
			sample(mc);
			return ++n[0] >= 20;
		}, 60);
		return landed;
	}

	private String bucketState(ClientGameTestContext ctx) {
		int full = client(ctx, mc -> Inv.count(s -> s.is(Items.WATER_BUCKET)));
		int empty = client(ctx, mc -> Inv.count(s -> s.is(Items.BUCKET)));
		return String.format("minHp=%.1f clutched=%b waterBuckets=%d emptyBuckets=%d pickup=%s", minHp, wet, full, empty, doneTick < 0 ? "never" : (doneTick - touchTick) + " ticks after landing");
	}

	/** No damage, the water placed, the bucket back and full: and back soon (it took 15 to 22 ticks, or never, while the camera chased a heading that does not exist). */
	private boolean intact(ClientGameTestContext ctx) {
		return minHp > 19.5 && wet && doneTick >= 0 && doneTick - touchTick <= 12 && client(ctx, mc -> Inv.count(s -> s.is(Items.WATER_BUCKET))) == 1 && client(ctx, mc -> Inv.count(s -> s.is(Items.BUCKET))) == 0;
	}

	private void fall(ClientGameTestContext ctx, TestSingleplayerContext sp, int n, int height) {
		prepare(ctx, sp);
		// a different heading every time: a person falls facing wherever they were going, and the pick-up must not depend on it
		dropAt(ctx, sp, X, -60 + height, Z, -150 + 55 * n);
		boolean landed = land(ctx);
		// open floor all round: there is no rim to avoid, so the correction must leave the fall alone
		double moved = Math.hypot(landX - X, landZ - Z);
		report("fall" + n + "-" + height + "blocks", landed && intact(ctx) && moved < 0.05, bucketState(ctx) + String.format(Locale.ROOT, " moved=%.2f", moved));
	}

	/** The sides of the tower a planned drop can walk off towards: south, as always, unless HB_DIR=all, which takes them in turn. */
	private static final int[][] SIDES = {{0, 1}, {1, 0}, {0, -1}, {-1, 0}};
	private static final String[] SIDE_NAMES = {"south", "east", "north", "west"};

	/** A route that steps off a tower (10 blocks, HB_TOWER=h): the executor equips the bucket and the clutch does the rest. */
	private void planned(ClientGameTestContext ctx, TestSingleplayerContext sp, int n) {
		int h = Integer.parseInt(System.getenv().getOrDefault("HB_TOWER", "10")), top = -60 + h - 1, side = "all".equals(System.getenv("HB_DIR")) ? (n - 1) % 4 : 0;
		int gx = 261 + 5 * SIDES[side][0], gz = 261 + 5 * SIDES[side][1];
		mapX = 261 + 3 * SIDES[side][0];
		mapZ = 261 + 3 * SIDES[side][1];
		prepare(ctx, sp);
		cmds(sp, "fill 260 -60 260 262 " + top + " 262 stone", "tp @a 261.5 " + (top + 1) + " 261.5");
		ctx.waitTicks(25);
		begin();
		ctx.runOnClient(mc -> Commands.run("goto " + gx + " -60 " + gz));
		until(ctx, "route finished", mc -> {
			sample(mc);
			return Bot.nav.state() == Nav.State.ARRIVED || Bot.nav.state() == Nav.State.FAILED || !Bot.busy();
		}, 800);
		boolean landed = land(ctx);
		double[] pos = ctx.computeOnClient(mc -> new double[]{mc.player.getX(), mc.player.getY(), mc.player.getZ()});
		double d = Math.hypot(pos[0] - (gx + 0.5), pos[2] - (gz + 0.5));
		boolean there = d < 1.5 && Math.abs(pos[1] + 60) < 1.0;
		// the camera keeps its heading from the moment the player leaves the edge until the water is out: what it still turns while it settles is 15 degrees at most, the half turn of the old code 40 to 130
		double swing = airYaw == null || placeYaw == null ? 999 : Math.abs(Mth.wrapDegrees(placeYaw - airYaw));
		report("planned" + n, landed && there && intact(ctx) && swing < 30, bucketState(ctx) + String.format(Locale.ROOT, " camera turned %.0f deg in the air, pos=%.1f %.1f %.1f state=%s side=%s tower=%d", swing, pos[0], pos[1], pos[2], Bot.nav.state(), SIDE_NAMES[side], h));
		ctx.runOnClient(mc -> Commands.run("stop"));
		cmd(sp, "fill 260 -60 260 262 " + top + " 262 air");
	}
}
