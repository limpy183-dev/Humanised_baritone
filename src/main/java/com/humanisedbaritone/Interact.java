package com.humanisedbaritone;

import com.humanisedbaritone.human.Rng;
import com.humanisedbaritone.settings.Settings;
import com.humanisedbaritone.world.Blk;
import com.humanisedbaritone.world.Inv;
import com.humanisedbaritone.world.Orient;
import com.humanisedbaritone.world.Tools;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Breaking, placing and using blocks the way a person does it: choose where on the block to look, turn the camera
 * there, wait until the crosshair really rests on it, and only then press the button. Every decision uses the same
 * ray the game itself uses, so nothing is ever clicked that a human could not have clicked.
 */
public final class Interact {
	private static final Minecraft mc = Minecraft.getInstance();

	private Interact() {
	}

	public enum R { WORKING, DONE, FAIL, NO_AIM }

	public static double reach() {
		return Math.min(Settings.REACH.d(), mc.player.blockInteractionRange());
	}

	// ============================================================ aiming helpers

	/** The block's real hitbox: smaller than the cube for beds, slabs, chests, torches, plants and so on. */
	private static AABB outline(BlockPos b) {
		var shape = mc.level.getBlockState(b).getShape(mc.level, b);
		return shape.isEmpty() ? new AABB(b) : shape.bounds().move(b);
	}

	/** A point on face {@code f} of the block's hitbox; u and v run from 0 to 1 across the face. */
	private static Vec3 facePoint(BlockPos b, Direction f, double u, double v) {
		return facePoint(outline(b), f, u, v);
	}

	private static Vec3 facePoint(AABB o, Direction f, double u, double v) {
		return switch (f.getAxis()) {
			case Y -> new Vec3(Mth.lerp(u, o.minX, o.maxX), f == Direction.UP ? o.maxY : o.minY, Mth.lerp(v, o.minZ, o.maxZ));
			case X -> new Vec3(f == Direction.EAST ? o.maxX : o.minX, Mth.lerp(u, o.minY, o.maxY), Mth.lerp(v, o.minZ, o.maxZ));
			case Z -> new Vec3(Mth.lerp(u, o.minX, o.maxX), Mth.lerp(v, o.minY, o.maxY), f == Direction.SOUTH ? o.maxZ : o.minZ);
		};
	}

	/** Point of the face rectangle closest to where the crosshair currently points (so the camera has to move least). */
	private static double[] closestUv(BlockPos b, Direction f, Vec3 eye, Vec3 look, double m) {
		AABB o = outline(b);
		Vec3 c = facePoint(b, f, 0.5, 0.5);
		Vec3 n = new Vec3(f.getStepX(), f.getStepY(), f.getStepZ());
		double denom = look.dot(n);
		double u = 0.5, v = 0.5;
		if (Math.abs(denom) > 1e-4) {
			double t = c.subtract(eye).dot(n) / denom;
			if (t > 0) {
				Vec3 p = eye.add(look.scale(t));
				switch (f.getAxis()) {
					case Y -> {
						u = (p.x - o.minX) / Math.max(1e-6, o.maxX - o.minX);
						v = (p.z - o.minZ) / Math.max(1e-6, o.maxZ - o.minZ);
					}
					case X -> {
						u = (p.y - o.minY) / Math.max(1e-6, o.maxY - o.minY);
						v = (p.z - o.minZ) / Math.max(1e-6, o.maxZ - o.minZ);
					}
					case Z -> {
						u = (p.x - o.minX) / Math.max(1e-6, o.maxX - o.minX);
						v = (p.y - o.minY) / Math.max(1e-6, o.maxY - o.minY);
					}
				}
			}
		}
		return new double[]{Mth.clamp(u, m, 1 - m), Mth.clamp(v, m, 1 - m)};
	}

	private static double angleTo(Vec3 eye, Vec3 look, Vec3 p) {
		Vec3 d = p.subtract(eye).normalize();
		return Math.toDegrees(Math.acos(Mth.clamp(look.dot(d), -1, 1)));
	}

	/** True if a ray from the eye to {@code p} first hits {@code block} on {@code face}. */
	private static boolean sees(Vec3 eye, Vec3 p, BlockPos block, Direction face) {
		Vec3 dir = p.subtract(eye);
		double len = dir.length();
		if (len < 1e-3) return false;
		Vec3 to = eye.add(dir.scale((len + 0.25) / len));
		BlockHitResult h = mc.level.clip(new ClipContext(eye, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
		return h.getType() == HitResult.Type.BLOCK && h.getBlockPos().equals(block) && (face == null || h.getDirection() == face);
	}

	/**
	 * Like {@link #sees} but the line must also stay clear a little to every side of the aim point: the camera settles within a degree
	 * or so of where it is sent, and a line that only just clears the edge of a rail or a slab ends up on it.
	 */
	private static boolean seesRobust(Vec3 eye, Vec3 p, BlockPos block, Direction face) {
		if (!sees(eye, p, block, face)) return false;
		Vec3 d = p.subtract(eye);
		double len = d.length();
		Vec3 ref = Math.abs(d.y) > 0.9 * len ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0);
		Vec3 a = d.cross(ref).normalize(), c = d.cross(a).normalize();
		double r = Math.min(0.1, len * 0.03);
		for (Vec3 o : new Vec3[]{a.scale(r), a.scale(-r), c.scale(r), c.scale(-r)}) if (!sees(eye, p.add(o), block, null)) return false; // another face of the same block is fine (a hopper's bowl has walls), another block is not
		return true;
	}

	/**
	 * How much lower the eye sits once the player sneaks. A click on a chest or a hopper has to be made sneaking (else it opens them),
	 * so it must be planned from the lowered eye: from there a rail or a slab beside the line may be in the way that was not before.
	 */
	private static double sneakDrop() {
		LocalPlayer p = mc.player;
		return p.isShiftKeyDown() ? 0 : p.getEyeHeight(net.minecraft.world.entity.Pose.STANDING) - p.getEyeHeight(net.minecraft.world.entity.Pose.CROUCHING);
	}

	public record Aim(Vec3 point, Direction face) {
	}

	/** Test hook: receives every hit point chosen for a block, relative to the block's corner. */
	public static java.util.function.Consumer<double[]> aimProbe;

	/**
	 * Picks a hit point on {@code block}: among the faces that are open to the player, random points spread around the
	 * spot nearest to the crosshair, preferring the one needing the smallest camera movement.
	 *
	 * @param onlyFace restrict to one face (placing needs a specific face), or null
	 */
	public static Aim pickAim(BlockPos block, Direction onlyFace) {
		Aim a = pickAim(block, onlyFace, mc.player.getEyePosition());
		if (a != null && aimProbe != null) aimProbe.accept(new double[]{a.point().x - block.getX(), a.point().y - block.getY(), a.point().z - block.getZ(), a.face().ordinal()});
		return a;
	}

	/** Same as above for a hypothetical eye position, e.g. to check whether some standing spot would work. */
	public static Aim pickAim(BlockPos block, Direction onlyFace, Vec3 eye) {
		return pickAim(block, onlyFace, eye, false);
	}

	/** @param robust the line of sight must also clear obstacles by a margin, see {@link #seesRobust} */
	static Aim pickAim(BlockPos block, Direction onlyFace, Vec3 eye, boolean robust) {
		LocalPlayer p = mc.player;
		Vec3 look = p.getViewVector(1.0F);
		double reach = reach();
		double margin = Settings.AIM_MARGIN.d();
		double spread = Settings.AIM_SPREAD.d();
		Aim best = null;
		double bestScore = Double.MAX_VALUE;
		for (Direction f : onlyFace != null ? new Direction[]{onlyFace} : Direction.values()) {
			if (onlyFace == null) {
				BlockState nb = mc.level.getBlockState(block.relative(f));
				if ((Blk.f(nb) & Blk.FULL) != 0) continue; // hidden face
			}
			double[] uv = closestUv(block, f, eye, look, margin);
			for (int i = 0; i < 6; i++) {
				double u = i == 0 ? uv[0] : Mth.clamp(uv[0] + Rng.gauss() * 0.22 * spread, margin, 1 - margin);
				double v = i == 0 ? uv[1] : Mth.clamp(uv[1] + Rng.gauss() * 0.22 * spread, margin, 1 - margin);
				if (i > 0 && Rng.chance(0.25 * spread)) { // sometimes somewhere else entirely on the face
					u = Rng.range(margin, 1 - margin);
					v = Rng.range(margin, 1 - margin);
				}
				Vec3 pt = facePoint(block, f, u, v);
				if (pt.distanceTo(eye) > reach) continue;
				if (!(robust ? seesRobust(eye, pt, block, f) : sees(eye, pt, block, f))) continue;
				double score = angleTo(eye, look, pt) + Rng.range(0, 6 * spread) + (i == 0 ? 0 : 1.5);
				if (score < bestScore) {
					bestScore = score;
					best = new Aim(pt, f);
				}
			}
			for (Vec3 pt : surface(block, f)) { // blocks that are not cubes: points on what is really there
				if (pt.distanceTo(eye) > reach || !(robust ? seesRobust(eye, pt, block, f) : sees(eye, pt, block, f))) continue;
				double score = angleTo(eye, look, pt) + Rng.range(0, 6 * spread) + 3;
				if (score < bestScore) {
					bestScore = score;
					best = new Aim(pt, f);
				}
			}
		}
		return best;
	}

	/**
	 * Points on the real surface that faces {@code f}: the middle and the quarter points of every part of the block's shape that
	 * looks that way (a hopper's rim and the floor of its bowl, the treads of a stair, a slab's half). A point on the box around
	 * such a block hangs in the air, and a line aimed at it ends somewhere else. For a full cube these are the usual probes.
	 */
	private static List<Vec3> surface(BlockPos b, Direction f) {
		BlockState st = mc.level.getBlockState(b);
		var shape = st.getShape(mc.level, b);
		List<AABB> boxes = shape.isEmpty() ? List.of(new AABB(0, 0, 0, 1, 1, 1)) : shape.toAabbs();
		List<Vec3> out = new ArrayList<>();
		for (AABB bx : boxes) {
			double x0 = b.getX() + bx.minX, x1 = b.getX() + bx.maxX, y0 = b.getY() + bx.minY, y1 = b.getY() + bx.maxY, z0 = b.getZ() + bx.minZ, z1 = b.getZ() + bx.maxZ;
			for (double[] uv : PROBES) {
				double u = uv[0], v = uv[1];
				out.add(switch (f) {
					case UP -> new Vec3(Mth.lerp(u, x0, x1), y1, Mth.lerp(v, z0, z1));
					case DOWN -> new Vec3(Mth.lerp(u, x0, x1), y0, Mth.lerp(v, z0, z1));
					case EAST -> new Vec3(x1, Mth.lerp(u, y0, y1), Mth.lerp(v, z0, z1));
					case WEST -> new Vec3(x0, Mth.lerp(u, y0, y1), Mth.lerp(v, z0, z1));
					case SOUTH -> new Vec3(Mth.lerp(u, x0, x1), Mth.lerp(v, y0, y1), z1);
					case NORTH -> new Vec3(Mth.lerp(u, x0, x1), Mth.lerp(v, y0, y1), z0);
				});
			}
		}
		return out;
	}

	/** Sample points on a face: the middle, and one in each quarter so that upper and lower halves are both covered. */
	private static final double[][] PROBES = {{0.5, 0.5}, {0.3, 0.3}, {0.7, 0.7}, {0.3, 0.7}, {0.7, 0.3}};

	/** Grass, ferns and the like: the game replaces them when a block is placed on them, but their hitbox blocks the view of what lies behind. */
	private static boolean softBlock(BlockPos p) {
		BlockState s = mc.level.getBlockState(p);
		int f = Blk.f(s);
		return (f & Blk.REPLACEABLE) != 0 && (f & (Blk.AIR | Blk.LIQUID)) == 0 && !s.getShape(mc.level, p).isEmpty();
	}

	/** How closely a click on {@code face} of {@code support} at {@code pt}, looking from {@code eye}, would produce the wanted state (0..1). */
	private static double matchFrom(ItemStack sample, Vec3 eye, Vec3 pt, Direction face, BlockPos support, BlockState wanted) {
		Vec3 d = pt.subtract(eye);
		float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
		float pitch = (float) -Math.toDegrees(Math.atan2(d.y, Math.hypot(d.x, d.z)));
		BlockHitResult hit = new BlockHitResult(pt, face, support, false);
		// the camera is never exactly on the aim point when the button goes down: the result has to hold for a few degrees either way
		double m = 1;
		for (float off : new float[]{0f, -9f, 9f}) m = Math.min(m, Orient.match(Orient.predict(sample, hit, yaw + off, pitch, virtualClick), wanted));
		return m;
	}

	/**
	 * Like {@link #placeMatch} but for a player who may end up anywhere in their cell: the result must hold for positions up to
	 * 0.3 blocks off the given eye, so that a spot on the borderline between two facings does not count as good.
	 */
	public static double placeMatchRobust(Vec3 eye, BlockPos target, BlockState wanted) {
		double q = placeMatch(eye, target, wanted);
		if (q < 0 || !Orient.sensitive(wanted)) return q;
		for (double[] o : STAND_OFFSETS) q = Math.min(q, Math.max(0, placeMatch(eye.add(o[0], 0, o[1]), target, wanted)));
		return q;
	}

	/** Where in its cell the player may really stand when the path says "this cell": the middle is not where an arrival ends up. */
	private static final double[][] STAND_OFFSETS = {{0.4, 0}, {-0.4, 0}, {0, 0.4}, {0, -0.4}, {0.35, 0.35}, {-0.35, 0.35}, {0.35, -0.35}, {-0.35, -0.35}};

	/** Nothing in the way of a line from the eye to a point on {@code face}, and the eye is on the open side of that face. For a block that is not there yet. */
	private static boolean clearTo(Vec3 eye, Vec3 p, Direction face) {
		Vec3 n = new Vec3(face.getStepX(), face.getStepY(), face.getStepZ());
		Vec3 d = p.subtract(eye);
		double len = d.length();
		if (len < 0.1 || d.dot(n) > -0.05) return false; // the eye must be on the side the face looks to
		Vec3 ref = Math.abs(d.y) > 0.9 * len ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0);
		Vec3 a = d.cross(ref).normalize(), c = d.cross(a).normalize();
		double r = Math.min(0.1, len * 0.03);
		for (Vec3 o : new Vec3[]{Vec3.ZERO, a.scale(r), a.scale(-r), c.scale(r), c.scale(-r)}) {
			Vec3 q = p.add(o);
			Vec3 dir = q.subtract(eye);
			double l = dir.length();
			Vec3 to = eye.add(dir.scale((l - 0.03) / l));
			if (mc.level.clip(new ClipContext(eye, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player)).getType() != HitResult.Type.MISS) return false;
		}
		return true;
	}

	/**
	 * What {@link #placeMatch} would be if {@code support}, an empty cell next to the target, held a full block: the best a click on its face
	 * towards the target gives from this eye (1 = exactly the wanted state), or -1 when no point of that face can be seen. Used to find out
	 * where a temporary block would have to go to make a floating block placeable.
	 */
	public static double placeMatchVirtual(Vec3 eye, BlockPos target, BlockState wanted, BlockPos support) {
		Direction d = null;
		for (Direction x : Direction.values()) if (target.relative(x).equals(support)) d = x;
		if (d == null) return -1;
		Direction face = d.getOpposite();
		ItemStack sample = sampleOf(wanted);
		double reach = reach(), best = -1;
		AABB cube = new AABB(support);
		virtualClick = true;
		try {
			for (double[] uv : PROBES) {
				Vec3 pt = facePoint(cube, face, uv[0], uv[1]);
				if (pt.distanceTo(eye) > reach || !clearTo(eye, pt, face)) continue;
				best = Math.max(best, sample.isEmpty() ? 1 : matchRobust(sample, eye, pt, face, support, wanted));
				if (best >= 1) return 1;
			}
		} finally {
			virtualClick = false;
		}
		return best;
	}

	/** While true, clicks are predicted as if they landed on a solid block at the clicked cell (which is empty in the world): see {@link #placeMatchVirtual}. */
	private static boolean virtualClick;

	/** The robust version of {@link #placeMatchVirtual}: it has to hold anywhere in the standing cell. */
	public static double placeMatchVirtualRobust(Vec3 eye, BlockPos target, BlockState wanted, BlockPos support) {
		double q = placeMatchVirtual(eye, target, wanted, support);
		if (q < 0 || !Orient.sensitive(wanted)) return q;
		for (double[] o : STAND_OFFSETS) q = Math.min(q, Math.max(0, placeMatchVirtual(eye.add(o[0], 0, o[1]), target, wanted, support)));
		return q;
	}

	/** How far beside the aim point the crosshair may end up and the click still has to give the wanted state: the camera settles within a degree or so of the aim, with noise on top. */
	private static final double AIM_SLOP = 0.12;

	/**
	 * Like {@link #matchFrom} but the result must also hold when the real hit lands a little beside the aim point. A point just inside
	 * the upper half of a face is not a way to get a top slab: the camera settles near it, not exactly on it.
	 */
	private static double matchRobust(ItemStack sample, Vec3 eye, Vec3 pt, Direction face, BlockPos support, BlockState wanted) {
		double m = matchFrom(sample, eye, pt, face, support, wanted);
		if (m < 1 - 1e-9 || !Orient.sensitive(wanted)) return m;
		for (Direction.Axis ax : Direction.Axis.values()) {
			if (ax == face.getAxis()) continue;
			for (int s = -1; s <= 1; s += 2) {
				Vec3 off = new Vec3(ax == Direction.Axis.X ? s * AIM_SLOP : 0, ax == Direction.Axis.Y ? s * AIM_SLOP : 0, ax == Direction.Axis.Z ? s * AIM_SLOP : 0);
				m = Math.min(m, matchFrom(sample, eye, pt.add(off), face, support, wanted));
			}
		}
		return m;
	}

	private static ItemStack sampleOf(BlockState wanted) {
		net.minecraft.world.item.Item it = wanted.getBlock().asItem();
		return it == net.minecraft.world.item.Items.AIR ? ItemStack.EMPTY : new ItemStack(it);
	}

	/**
	 * What a click from an eye at {@code eye} could achieve at {@code target}: the best {@link Orient#match} among the states
	 * the visible clicks would produce (1 = exactly as wanted), or -1 when nothing can be clicked from there.
	 *
	 * @param wanted the state to place, or null when any block will do
	 */
	public static double placeMatch(Vec3 eye, BlockPos target, BlockState wanted) {
		ItemStack sample = wanted == null ? ItemStack.EMPTY : sampleOf(wanted);
		double reach = reach(), best = -1;
		if (softBlock(target)) { // clicking the plant itself replaces it
			Aim a = pickAim(target, null, eye);
			if (a != null) best = sample.isEmpty() ? 1 : matchFrom(sample, eye, a.point(), a.face(), target, wanted);
		}
		for (Direction d : Direction.values()) {
			BlockPos sp = target.relative(d);
			int f = Blk.f(mc.level.getBlockState(sp));
			if ((f & (Blk.AIR | Blk.LIQUID | Blk.REPLACEABLE)) != 0) continue;
			Direction face = d.getOpposite();
			Vec3 e = (f & Blk.INTERACT) != 0 ? eye.add(0, -sneakDrop(), 0) : eye;
			for (Vec3 pt : surface(sp, face)) {
				if (pt.distanceTo(e) > reach || !seesRobust(e, pt, sp, face)) continue;
				best = Math.max(best, sample.isEmpty() ? 1 : matchFrom(sample, e, pt, face, sp, wanted));
				if (best >= 1) return 1;
			}
		}
		return best;
	}

	/** Test aid: for a placement at {@code target} seen from {@code eye}, one line per candidate support saying why it works or not. */
	public static String explain(Vec3 eye, BlockPos target) {
		StringBuilder sb = new StringBuilder();
		for (Direction d : Direction.values()) {
			BlockPos sp = target.relative(d);
			BlockState ss = mc.level.getBlockState(sp);
			int f = Blk.f(ss);
			if ((f & (Blk.AIR | Blk.LIQUID | Blk.REPLACEABLE)) != 0) continue;
			Direction face = d.getOpposite();
			Vec3 e = (f & Blk.INTERACT) != 0 ? eye.add(0, -sneakDrop(), 0) : eye;
			sb.append(' ').append(sp.toShortString()).append(' ').append(face).append(' ').append(ss.getBlock().getDescriptionId().replaceAll("^.*\\.", "")).append('[');
			for (Vec3 pt : surface(sp, face)) sb.append(String.format("d%.1f%s%s ", pt.distanceTo(e), sees(e, pt, sp, face) ? "S" : "-", seesRobust(e, pt, sp, face) ? "R" : "-"));
			sb.append(']');
		}
		return sb.toString();
	}

	/** What the crosshair rests on right now. */
	public static BlockHitResult crosshair() {
		HitResult h = mc.player.raycastHitResult(1.0F, mc.player);
		return h instanceof BlockHitResult b && b.getType() == HitResult.Type.BLOCK ? b : null;
	}

	private static boolean onTarget(BlockPos block, Direction face) {
		BlockHitResult h = crosshair();
		return h != null && h.getBlockPos().equals(block) && (face == null || h.getDirection() == face);
	}

	// ================================================================== tools

	/** Makes the best tool for the state the selected item; false while a swap or the reaction delay is pending. */
	public static final class ToolChoice {
		private int delay = -1;
		private int slot = -2;

		public boolean ready(BlockState state) {
			if (delay > 0) {
				delay--;
				return false;
			}
			Tools.Pick pick = Tools.best(state);
			int want = pick.slot();
			if (!Settings.AUTO_TOOL.on()) return true;
			if (want < 0 || want == Inv.selected()) return true;
			if (want < 9) {
				Inv.select(want);
				delay = Rng.delayTicks(Settings.ACT_SWAP.d());
				return delay <= 0;
			}
			Inv.toHotbar(want);
			delay = Rng.delayTicks(Settings.ACT_SWAP.d()) + 1;
			return false;
		}
	}

	// ================================================================== break

	public static final class BreakOp {
		public final BlockPos pos;
		private final BlockState initial;
		private final ToolChoice tool = new ToolChoice();
		private Aim aim;
		private int ticks, aimTicks, holdTicks, cleared;
		/** Takes something small out of the line of sight first, e.g. the grass around the feet. */
		private BreakOp clearing;

		public BreakOp(BlockPos pos) {
			this.pos = pos;
			this.initial = mc.level.getBlockState(pos);
		}

		/** @param firstAim a point known to be on the block, e.g. where the line of sight hit it */
		BreakOp(BlockPos pos, Aim firstAim) {
			this(pos);
			this.aim = firstAim;
		}

		/** A block without collision, such as grass, that the crosshair would hit before {@code pos}; null if nothing like that is in the way. */
		private BreakOp softObstacle() {
			BlockHitResult h = mc.level.clip(new ClipContext(mc.player.getEyePosition(), Vec3.atCenterOf(pos), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
			if (h.getType() != HitResult.Type.BLOCK || h.getBlockPos().equals(pos)) return null;
			int f = Blk.f(mc.level.getBlockState(h.getBlockPos()));
			if ((f & Blk.PASS) == 0 || (f & (Blk.AIR | Blk.LIQUID | Blk.LAVA)) != 0) return null;
			return new BreakOp(h.getBlockPos(), new Aim(h.getLocation(), h.getDirection())); // the plant is hit exactly where the line of sight enters it
		}

		public R tick() {
			LocalPlayer p = mc.player;
			BlockState st = mc.level.getBlockState(pos);
			if (st.isAir() || st.getBlock() != initial.getBlock()) return R.DONE;
			if (clearing != null) {
				R r = clearing.tick();
				if (r == R.WORKING || r == R.NO_AIM) return R.WORKING; // it gives up by itself if it cannot aim
				clearing = null; // cleared, or not: carry on with the real block
				aim = null;
				return R.WORKING;
			}
			ticks++;
			if (!tool.ready(st)) return R.WORKING;
			if (Vec3.atCenterOf(pos).distanceTo(p.getEyePosition()) > reach() + 1.0) return R.FAIL;
			if (aim == null || aimTicks > 40 || !stillGood()) {
				aim = pickAim(pos, null);
				aimTicks = 0;
				if (aim == null) {
					BreakOp soft = cleared < 4 ? softObstacle() : null;
					if (soft != null) {
						cleared++;
						clearing = soft;
						return R.WORKING;
					}
					return ticks > 30 ? R.FAIL : R.NO_AIM;
				}
			}
			aimTicks++;
			Bot.look.aimAt(aim.point(), 0.35, 1.2);
			boolean on = onTarget(pos, null);
			if (on && Bot.look.onTicks >= Settings.ACT_SETTLE.ticks() || holdTicks > 0 && on) {
				Bot.ctl.attack = true;
				holdTicks++;
			} else {
				holdTicks = 0;
			}
			if (ticks > 40 + Tools.best(st).ticks() * 2.5 + 160) return R.FAIL;
			return R.WORKING;
		}

		private boolean stillGood() {
			Vec3 eye = mc.player.getEyePosition();
			return aim.point().distanceTo(eye) <= reach() + 0.3 && sees(eye, aim.point(), pos, aim.face());
		}
	}

	// ================================================================== place

	public static final class PlaceOp {
		public final BlockPos target;
		private final java.util.function.Predicate<ItemStack> item;
		private final Direction onlySupport;
		/** The exact state wanted at the target (orientation included), or null for "any block will do". */
		private final BlockState wanted;
		private Aim aim;
		private BlockPos support;
		private int ticks, aimTicks, swapDelay = -1, clicks, since;
		/** Another one of the same on top of the block that stands there (sea pickles, candles): the click goes on the block itself and the job is done when the state is as wanted. */
		private boolean stacking;
		public boolean sneakNeeded;
		/** The chosen aim point was predicted to give the wanted orientation even with the crosshair a little off it; and how many ticks a click was held back because the real hit would not. */
		private boolean strict;
		private int heldBack;

		/** Places one of the cheap building blocks from the settings. */
		public PlaceOp(BlockPos target) {
			this(target, null, null);
		}

		/**
		 * @param item        which item to place (null = any building block from the settings)
		 * @param onlySupport only click the block in this direction from the target (e.g. DOWN to plant on farmland), or null
		 */
		public PlaceOp(BlockPos target, java.util.function.Predicate<ItemStack> item, Direction onlySupport) {
			this.target = target;
			this.item = item;
			this.onlySupport = onlySupport;
			this.wanted = null;
		}

		/** Adds one more of {@code wanted} to the block of its kind that stands at {@code target} (a sea pickle, a candle): a click with the item on the block itself. */
		public static PlaceOp stacking(BlockPos target, BlockState wanted) {
			PlaceOp op = new PlaceOp(target, wanted);
			op.stacking = true;
			return op;
		}

		/** Places the block of {@code wanted}, choosing the click that produces that state. */
		public PlaceOp(BlockPos target, BlockState wanted) {
			this.target = target;
			this.wanted = wanted;
			net.minecraft.world.item.Item it = wanted.getBlock().asItem();
			this.item = st -> st.getItem() == it;
			this.onlySupport = null;
		}

		private boolean isPlaceable(ItemStack st) {
			if (item != null) return item.test(st);
			return st.getItem() instanceof BlockItem bi && com.humanisedbaritone.settings.Settings.PLACEABLE.has(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(bi.getBlock()).toString());
		}

		/** How many times the button went down: none means the spot gave no usable aim. */
		public int clicks() {
			return clicks;
		}

		public R tick() {
			LocalPlayer p = mc.player;
			BlockState cur = mc.level.getBlockState(target);
			if (stacking) {
				if (!Orient.shortCount(cur, wanted)) return R.DONE;
			} else {
				if ((Blk.f(cur) & (Blk.AIR | Blk.REPLACEABLE)) == 0) return R.DONE;
				if (wanted != null && cur.getBlock() == wanted.getBlock()) return R.DONE;
			}
			ticks++;
			if (ticks > 120) {
				var ch = crosshair();
				Log.debug("place failed: timed out at " + target + " (clicks " + clicks + ", aim " + (aim == null ? "none" : aim.point() + " " + aim.face()) + ", support " + support + "), crosshair on "
					+ (ch == null ? "nothing" : ch.getBlockPos() + " " + ch.getDirection() + " at " + ch.getLocation()) + ", camera off by " + String.format("%.1f", Bot.look.error()) + " deg, eye " + mc.player.getEyePosition());
				return R.FAIL;
			}
			// block in hand
			if (!isPlaceable(Inv.held())) {
				if (swapDelay > 0) {
					swapDelay--;
					return R.WORKING;
				}
				int slot = pickBlockSlot();
				if (slot < 0) return R.FAIL;
				if (slot < 9) Inv.select(slot);
				else Inv.toHotbar(slot);
				swapDelay = Rng.delayTicks(Settings.ACT_SWAP.d()) + 1;
				return R.WORKING;
			}
			if (!stacking && solidTarget() && p.getBoundingBox().intersects(new AABB(target).deflate(0.001))) {
				if (ticks % 40 == 0) Log.debug("place waiting: player is in the way at " + target);
				// shuffle back a little, sneaking so that an edge cannot be fallen off
				Vec3 c = Vec3.atCenterOf(target);
				double ax = p.getX() - c.x, az = p.getZ() - c.z;
				if (Math.abs(ax) > 1e-3 || Math.abs(az) > 1e-3) {
					Bot.ctl.walkTowards(Math.toDegrees(Math.atan2(-ax, az)), p.getYRot());
					Bot.ctl.sneak = true;
				}
				return R.WORKING;
			}
			if (aim == null || aimTicks > 30) {
				aim = stacking ? pickSelf() : pickSupport();
				aimTicks = 0;
				if (aim == null) return R.NO_AIM;
				if (wanted != null && !strict && support != target && Orient.sensitive(wanted)) { // every click that can be made from here gives another orientation: no block is better than a wrong one
					aim = null;
					return R.NO_AIM;
				}
			}
			aimTicks++;
			Bot.look.aimAt(aim.point(), 0.4, 1.0);
			if (sneakNeeded) Bot.ctl.sneak = true;
			boolean on = onTarget(support, aim.face());
			since++;
			if (on && Bot.look.onTicks >= Settings.ACT_SETTLE.ticks() && since >= 2) {
				if (!holdsOrientation()) { // the crosshair rests where this click would give another orientation: wait for it to move on
					heldBack++;
					return R.WORKING;
				}
				if (wanted != null && Settings.DEBUG.on() && clicks == 0) {
					BlockHitResult hit = crosshair();
					Log.debug("place " + target + " want " + wanted + " from " + p.blockPosition() + " yaw " + (int) p.getYRot() + " pitch " + (int) p.getXRot() + " clicking " + support + " " + aim.face()
						+ (hit == null ? "" : String.format(" at %.2f %.2f %.2f", hit.getLocation().x - target.getX(), hit.getLocation().y - target.getY(), hit.getLocation().z - target.getZ())) + (heldBack > 0 ? " (held back " + heldBack + " ticks)" : ""));
				}
				Bot.ctl.clickUse();
				clicks++;
				since = -Rng.range(1, 3);
				if (clicks > 6) {
					var ch = crosshair();
					Log.debug("place failed: " + clicks + " clicks at " + target + " did nothing; crosshair on " + (ch == null ? "nothing" : ch.getBlockPos() + " " + ch.getDirection())
						+ "; now " + mc.level.getBlockState(target));
					return R.FAIL;
				}
			}
			return R.WORKING;
		}

		/**
		 * The click has to give the planned orientation from where the crosshair really rests, not only from the point that was aimed at:
		 * the camera settles within a degree or so of it, and a slab's half or a stair's facing can flip inside that.
		 */
		private boolean holdsOrientation() {
			if (!strict || wanted == null || !Orient.sensitive(wanted)) return true;
			BlockHitResult h = crosshair();
			if (h == null) return false;
			LocalPlayer p = mc.player;
			for (float off : new float[]{0f, -4f, 4f}) {
				if (Orient.match(Orient.predict(Inv.held(), h, p.getYRot() + off, p.getXRot()), wanted) < 1 - 1e-9) return false;
			}
			return true;
		}

		/** Only a block with a collision box cannot go where the player stands; crops, torches and flowers can, as in the vanilla game. */
		private boolean solidTarget() {
			BlockState s = wanted != null ? wanted : Inv.held().getItem() instanceof BlockItem bi ? bi.getBlock().defaultBlockState() : null;
			return s == null || !s.getCollisionShape(mc.level, target).isEmpty();
		}

		private int pickBlockSlot() {
			int held = Inv.selected();
			if (isPlaceable(Inv.get(held))) return held;
			int best = -1, count = 0;
			for (int i = 0; i < 36; i++) {
				ItemStack st = Inv.get(i);
				if (st.isEmpty() || !isPlaceable(st)) continue;
				int sc = st.getCount() + (i < 9 ? 100 : 0);
				if (sc > count) {
					count = sc;
					best = i;
				}
			}
			return best;
		}

		private Aim pickSelf() {
			support = target;
			sneakNeeded = false;
			strict = true;
			return pickAim(target, null, mc.player.getEyePosition());
		}

		private Aim pickSupport() {
			Vec3 eye = mc.player.getEyePosition();
			Vec3 look = mc.player.getViewVector(1.0F);
			double margin = Settings.AIM_MARGIN.d(), spread = Settings.AIM_SPREAD.d();
			strict = false;
			Aim best = null;
			double bestScore = Double.MAX_VALUE;
			ItemStack sample = wanted == null ? ItemStack.EMPTY : sampleOf(wanted);
			for (Direction d : onlySupport != null ? new Direction[]{onlySupport} : Direction.values()) {
				BlockPos sp = target.relative(d);
				BlockState ss = mc.level.getBlockState(sp);
				int f = Blk.f(ss);
				if ((f & (Blk.AIR | Blk.LIQUID | Blk.REPLACEABLE)) != 0) continue;
				Direction face = d.getOpposite();
				Vec3 e = (f & Blk.INTERACT) != 0 ? eye.add(0, -sneakDrop(), 0) : eye; // such a click is made sneaking
				double[] uv = closestUv(sp, face, e, look, margin);
				// directional blocks also try fixed spots, so that both halves of a face are represented
				List<Vec3> pts = new ArrayList<>(); // spread around the spot nearest to the crosshair, and fixed spots on the real surface, so that both halves of a face are represented
				for (int i = 0; i < 5; i++) {
					double u = i == 0 ? uv[0] : Mth.clamp(uv[0] + Rng.gauss() * 0.2 * spread, margin, 1 - margin);
					double v = i == 0 ? uv[1] : Mth.clamp(uv[1] + Rng.gauss() * 0.2 * spread, margin, 1 - margin);
					pts.add(facePoint(sp, face, u, v));
				}
				if (!sample.isEmpty() || (f & Blk.FULL) == 0) pts.addAll(surface(sp, face));
				for (Vec3 pt : pts) {
					if (pt.distanceTo(e) > reach() || !seesRobust(e, pt, sp, face)) continue;
					double q = sample.isEmpty() ? 1 : matchRobust(sample, e, pt, face, sp, wanted);
					double score = angleTo(e, look, pt) + Rng.range(0, 5 * spread) + 400 * (1 - q); // the right orientation matters more than a little camera movement
					if (score < bestScore) {
						bestScore = score;
						best = new Aim(pt, face);
						support = sp;
						sneakNeeded = (f & Blk.INTERACT) != 0;
						strict = q >= 1 - 1e-9;
					}
				}
			}
			if (best != null && !sample.isEmpty() && Settings.DEBUG.on()) {
				Vec3 d = best.point().subtract(eye);
				float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z)), pitch = (float) -Math.toDegrees(Math.atan2(d.y, Math.hypot(d.x, d.z)));
				Log.debug(String.format("  aim %s %s from eye %.2f %.2f %.2f: yaw %.0f pitch %.0f predicts %s", support.toShortString(), best.face(), eye.x, eye.y, eye.z, yaw, pitch,
					Orient.predict(sample, new BlockHitResult(best.point(), best.face(), support, false), yaw, pitch)));
			}
			if (best == null && onlySupport == null && softBlock(target)) { // grass in the way of the view: click it, the game replaces it
				best = pickAim(target, null, eye);
				support = target;
				sneakNeeded = false;
			}
			return best;
		}
	}

	// ================================================================== bucket

	/**
	 * Water from a bucket: a source into an empty cell (PLACE: the click goes on a solid block beside the cell, the water appears next to
	 * the face that was hit) or water into a block that can hold it, such as a slab, a chest or a sea pickle (WATERLOG: the click goes on
	 * the block itself). Sneaks, so that a chest or a trapdoor does not take the click.
	 */
	public static final class BucketOp {
		public enum Mode { PLACE, WATERLOG }

		public final BlockPos target;
		private final Mode mode;
		private Aim aim;
		private BlockPos support;
		private int ticks, aimTicks, swapDelay = -1, clicks, since;

		public BucketOp(BlockPos target, Mode mode) {
			this.target = target;
			this.mode = mode;
		}

		private boolean wet() {
			var f = mc.level.getFluidState(target);
			return f.isSource() && f.is(net.minecraft.tags.FluidTags.WATER);
		}

		public R tick() {
			if (wet()) return R.DONE;
			ticks++;
			if (ticks > 160) {
				Log.debug("bucket failed: timed out at " + target + " (clicks " + clicks + ", aim " + (aim != null) + ", support " + support + ")");
				return R.FAIL;
			}
			if (!Inv.held().is(net.minecraft.world.item.Items.WATER_BUCKET)) {
				if (swapDelay > 0) {
					swapDelay--;
					return R.WORKING;
				}
				int slot = Inv.find(st -> st.is(net.minecraft.world.item.Items.WATER_BUCKET));
				if (slot < 0) return R.FAIL;
				if (slot < 9) Inv.select(slot);
				else Inv.toHotbar(slot);
				swapDelay = Rng.delayTicks(Settings.ACT_SWAP.d()) + 1;
				return R.WORKING;
			}
			if (aim == null || aimTicks > 30) {
				aim = pick();
				aimTicks = 0;
				if (aim == null) return R.NO_AIM;
			}
			aimTicks++;
			Bot.look.aimAt(aim.point(), 0.4, 1.0);
			// a chest or a trapdoor takes the click unless the player sneaks; but a sneaking player puts the water beside a block instead of into it
			if (mode == Mode.PLACE && (Blk.f(mc.level.getBlockState(support)) & Blk.INTERACT) != 0) Bot.ctl.sneak = true;
			since++;
			if (onTarget(support, aim.face()) && Bot.look.onTicks >= Settings.ACT_SETTLE.ticks() && since >= 2) {
				Bot.ctl.clickUse();
				clicks++;
				since = -Rng.range(1, 3);
				if (clicks > 6) {
					Log.debug("bucket failed: " + clicks + " clicks at " + target + " did nothing; now " + mc.level.getBlockState(target));
					return R.FAIL;
				}
			}
			return R.WORKING;
		}

		public int clicks() {
			return clicks;
		}

		/** A block that would take the water into itself instead of letting it flow into the cell beside it. */
		private static boolean takesWater(BlockState s) {
			return s.getBlock() instanceof net.minecraft.world.level.block.LiquidBlockContainer && !(s.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED)
				&& s.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED));
		}

		private Aim pick() {
			Vec3 eye = mc.player.getEyePosition(), look = mc.player.getViewVector(1.0F);
			if (mode == Mode.WATERLOG) {
				support = target;
				return pickAim(target, null, eye, true);
			}
			double margin = Settings.AIM_MARGIN.d(), spread = Settings.AIM_SPREAD.d();
			Aim best = null;
			double bestScore = Double.MAX_VALUE;
			for (Direction d : Direction.values()) {
				BlockPos sp = target.relative(d);
				BlockState ss = mc.level.getBlockState(sp);
				if ((Blk.f(ss) & (Blk.AIR | Blk.LIQUID | Blk.REPLACEABLE)) != 0 || takesWater(ss)) continue;
				Direction face = d.getOpposite();
				Vec3 e = (Blk.f(ss) & Blk.INTERACT) != 0 ? eye.add(0, -sneakDrop(), 0) : eye;
				double[] uv = closestUv(sp, face, e, look, margin);
				List<Vec3> pts = new ArrayList<>();
				for (int i = 0; i < 5; i++) {
					double u = i == 0 ? uv[0] : Mth.clamp(uv[0] + Rng.gauss() * 0.2 * spread, margin, 1 - margin);
					double v = i == 0 ? uv[1] : Mth.clamp(uv[1] + Rng.gauss() * 0.2 * spread, margin, 1 - margin);
					pts.add(facePoint(sp, face, u, v));
				}
				pts.addAll(surface(sp, face));
				for (Vec3 pt : pts) {
					if (pt.distanceTo(e) > reach() || !seesRobust(e, pt, sp, face)) continue;
					double score = angleTo(e, look, pt) + Rng.range(0, 5 * spread);
					if (score < bestScore) {
						bestScore = score;
						best = new Aim(pt, face);
						support = sp;
					}
				}
			}
			return best;
		}

		/** Could a click from an eye at {@code eye} put water into the cell? (-1 = no way to aim, 1 = yes) */
		public static double feasible(Vec3 eye, BlockPos target, Mode mode) {
			if (mode == Mode.WATERLOG) return pickAim(target, null, eye) != null ? 1 : -1;
			for (Direction d : Direction.values()) {
				BlockPos sp = target.relative(d);
				BlockState ss = mc.level.getBlockState(sp);
				if ((Blk.f(ss) & (Blk.AIR | Blk.LIQUID | Blk.REPLACEABLE)) != 0 || takesWater(ss)) continue;
				Direction face = d.getOpposite();
				Vec3 e = (Blk.f(ss) & Blk.INTERACT) != 0 ? eye.add(0, -sneakDrop(), 0) : eye;
				for (Vec3 pt : surface(sp, face)) if (pt.distanceTo(e) <= reach() && seesRobust(e, pt, sp, face)) return 1;
			}
			return -1;
		}
	}

	// ==================================================================== use

	/** Right-click a block once or twice, e.g. to open a door. */
	public static final class UseOp {
		public final BlockPos pos;
		private final java.util.function.BooleanSupplier done;
		private Aim aim;
		private int ticks, clicks, since;

		public UseOp(BlockPos pos) {
			this(pos, null);
		}

		/** @param done when this becomes true the job is finished (default: the block is now open, like a door) */
		public UseOp(BlockPos pos, java.util.function.BooleanSupplier done) {
			this.pos = pos;
			this.done = done;
		}

		public R tick() {
			BlockState st = mc.level.getBlockState(pos);
			if (done != null ? done.getAsBoolean() : Blk.isOpen(st)) return R.DONE;
			ticks++;
			if (ticks > 80 || clicks > 4) {
				var ch = crosshair();
				Log.debug("use failed at " + pos + " (" + mc.level.getBlockState(pos) + "): " + clicks + " clicks, " + ticks + " ticks, aim " + (aim == null ? "none" : aim.point() + " " + aim.face())
					+ ", crosshair " + (ch == null ? "nothing" : ch.getBlockPos() + " " + ch.getDirection()) + ", player " + mc.player.position());
				return R.FAIL;
			}
			if (aim == null || ticks % 25 == 0) {
				aim = pickAim(pos, null);
				if (aim == null) return R.NO_AIM;
			}
			Bot.look.aimAt(aim.point(), 0.4, 1.5);
			since++;
			if (onTarget(pos, null) && Bot.look.onTicks >= Settings.ACT_SETTLE.ticks() && since >= 4) {
				Bot.ctl.clickUse();
				clicks++;
				since = -Rng.range(0, 4);
			}
			return R.WORKING;
		}
	}
}
