package com.humanisedbaritone.path;

import com.humanisedbaritone.Bot;
import com.humanisedbaritone.Interact;
import com.humanisedbaritone.Log;
import com.humanisedbaritone.behave.Clutch;
import com.humanisedbaritone.human.Gaze;
import com.humanisedbaritone.human.Rng;
import com.humanisedbaritone.path.Moves.Kind;
import com.humanisedbaritone.settings.Settings;
import com.humanisedbaritone.world.Blk;
import com.humanisedbaritone.world.Inv;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** Runs one step of a path tick by tick: decides keys, jumps, camera aim and any blocks to break or place first. */
public abstract class Exec {
	/** LOST: the player is standing on another level of the route than this step expects (say on the next terrace after a jump), so the step makes no sense any more, but the route does. */
	public enum Res { RUN, DONE, FAIL, LOST }

	static final Minecraft mc = Minecraft.getInstance();
	protected final Nav nav;
	protected final Path.Step st;
	protected final Path.Step next;
	protected int ticks;
	private boolean inited;
	/** Real horizontal speed in blocks per tick, from how far the player actually moved. */
	protected double speed;
	/** Room either side of a {@link Kind#RUN} when it begins, see {@link Smooth#slack}. */
	protected double runSlack;

	Exec(Nav nav, Path.Step st, Path.Step next) {
		this.nav = nav;
		this.st = st;
		this.next = next;
	}

	public static Exec create(Nav nav, Path.Step st, Path.Step next) {
		return switch (st.kind()) {
			case PILLAR -> new Pillar(nav, st, next);
			case DIG_DOWN -> new Dig(nav, st, next);
			default -> new Walk(nav, st, next);
		};
	}

	public final Res tick() {
		ticks++;
		speed = nav.speed;
		if (!inited) {
			inited = true;
			if (st.kind() == Kind.RUN) {
				runSlack = Smooth.slack(nav.live(), st);
				if (runSlack < 0) return bail("the open ground is not clear any more");
			} else if (!Moves.stillValid(nav.live(), st.kind(), st.sx(), st.sy(), st.sz(), st.dx(), st.dy(), st.dz())) return bail("the move is not possible any more");
			init();
		}
		return step();
	}

	abstract void init();

	abstract Res step();

	// ---------------------------------------------------------------- helpers

	static double hdist(Vec3 a, double x, double z) {
		return Math.hypot(a.x - x, a.z - z);
	}

	static Vec3 center(int x, int y, int z) {
		return new Vec3(x + 0.5, y, z + 0.5);
	}

	static void standStill() {
		Bot.ctl.forward = Bot.ctl.back = Bot.ctl.left = Bot.ctl.right = Bot.ctl.jump = Bot.ctl.sprint = false;
	}

	/** Gives the step up and records why, so that failures in real terrain can be diagnosed from the log. */
	protected final Res bail(String why) {
		Log.debug("  " + st.kind() + " " + st.sx() + "," + st.sy() + "," + st.sz() + "->" + st.dx() + "," + st.dy() + "," + st.dz() + " failed: " + why
			+ String.format(" at %.2f %.2f %.2f tick %d", mc.player.getX(), mc.player.getY(), mc.player.getZ(), ticks));
		return Res.FAIL;
	}

	// ================================================================== Walk

	static final class Walk extends Exec {
		private final Kind kind;
		private Object op;
		private int pause, stuck, jumpCd, hesitate, strafeTap, bridgeTicks;
		private boolean moving, precise, runUpDone;
		/** The run has room to hop along its whole length (see {@link #headroom}). */
		private boolean hop;
		private Vec3 S, D;
		private double ux, uz, lat, len, takeoff, bestDist = 1e9;
		private int noProgress, collideTicks, offLevel;
		private boolean jumped;
		/** Long drop that is cushioned with a water bucket. */
		private boolean clutch;
		private int equipDelay = -1;
		/** A long straight stretch of open ground: steered by pure pursuit along its line, with a slow sideways drift. */
		private boolean run;
		private Rng.Ou drift;
		private double driftLim;

		Walk(Nav nav, Path.Step st, Path.Step next) {
			super(nav, st, next);
			this.kind = st.kind();
		}

		@Override void init() {
			Ctx c = nav.live();
			S = center(st.sx(), st.sy(), st.sz());
			D = center(st.dx(), st.dy(), st.dz());
			double hx = D.x - S.x, hz = D.z - S.z;
			len = Math.hypot(hx, hz);
			if (len > 1e-6) {
				ux = hx / len;
				uz = hz / len;
			}
			run = kind == Kind.RUN;
			precise = !run && (kind == Kind.PARKOUR || edgeRisk(c, st.sx(), st.sy(), st.sz()) || edgeRisk(c, st.dx(), st.dy(), st.dz()));
			double wander = Settings.MOTION_WANDER.d();
			if (run) {
				// the line was checked to be plain this far either side: wander slowly inside that room (a mean-reverting drift, a few seconds long)
				driftLim = Math.min(wander, runSlack);
				double theta = 0.3;
				drift = new Rng.Ou(theta, driftLim * 0.5 * Math.sqrt(2 * theta));
				for (int i = 0; i < 40; i++) drift.step(0.1);
				hop = headroom(c, 0.4 + driftLim);
				Log.debug(String.format("  run of %.0f blocks, %.2f of room each side%s", len, runSlack, hop ? "" : ", too low to hop"));
			}
			double side = 0.2;
			int px = (int) Math.round(-uz), pz = (int) Math.round(ux);
			if (!c.passable(st.dx() + px, st.dy(), st.dz() + pz) || !c.passable(st.dx() - px, st.dy(), st.dz() - pz)) side = 0.1;
			// the offset of a single block is a change of heading of its own at every step, so it stays small; the slow drift of a run is where more wander shows
			double lane = Math.min(wander, 0.16);
			lat = run || precise || kind == Kind.DIAGONAL ? 0 : Mth.clamp(Rng.gauss(0, lane * 0.6), -Math.min(lane, side), Math.min(lane, side));
			takeoff = Rng.range(0.35, 0.65);
			clutch = kind == Kind.DESCEND && st.sy() - st.dy() > c.maxFall && !c.water(st.dx(), st.dy(), st.dz()) && !c.climbable(st.dx(), st.dy(), st.dz());
		}

		/** A hop lifts the head about a block and a quarter: two blocks of air above the feet, along the line and {@code half} either side. A bonk on a ceiling would only cost speed. */
		private boolean headroom(Ctx c, double half) {
			int n = Math.max(1, (int) Math.ceil(len / 0.4));
			for (int i = 0; i <= n; i++) {
				double x = S.x + (D.x - S.x) * i / n, z = S.z + (D.z - S.z) * i / n;
				for (int ix = (int) Math.floor(x - half); ix <= (int) Math.floor(x + half); ix++) {
					for (int iz = (int) Math.floor(z - half); iz <= (int) Math.floor(z + half); iz++) if (!c.passable(ix, st.sy() + 2, iz)) return false;
				}
			}
			return true;
		}

		/** A fall that hurts or lava right next to this position: walk the middle of the lane and do not sprint. A step down of a block or two is just a step. */
		private static boolean edgeRisk(Ctx c, int x, int y, int z) {
			boolean slick = (c.f(x, y - 1, z) & Blk.SLIPPERY) != 0; // on ice the player slides on: any drop beside the lane is a danger
			int[] dxs = {1, -1, 0, 0}, dzs = {0, 0, 1, -1};
			for (int i = 0; i < 4; i++) {
				int nx = x + dxs[i], nz = z + dzs[i];
				if (Blk.isLava(c.get(nx, y, nz)) || Blk.isLava(c.get(nx, y - 1, nz))) return true;
				if (slick ? c.passable(nx, y, nz) && c.passable(nx, y - 1, nz) && !c.water(nx, y - 1, nz) : Smooth.drop(c, nx, y, nz)) return true;
			}
			return false;
		}

		// ----------------------------------------------------------- prep

		private List<BlockPos> clearCells() {
			List<BlockPos> l = new ArrayList<>(3);
			switch (kind) {
				case TRAVERSE -> {
					l.add(new BlockPos(st.dx(), st.dy(), st.dz()));
					l.add(new BlockPos(st.dx(), st.dy() + 1, st.dz()));
				}
				case ASCEND -> {
					l.add(new BlockPos(st.sx(), st.sy() + 2, st.sz()));
					l.add(new BlockPos(st.dx(), st.dy(), st.dz()));
					l.add(new BlockPos(st.dx(), st.dy() + 1, st.dz()));
				}
				case DESCEND -> {
					l.add(new BlockPos(st.dx(), st.sy(), st.dz()));
					l.add(new BlockPos(st.dx(), st.sy() + 1, st.dz()));
				}
				default -> {
				}
			}
			return l;
		}

		private BlockPos floorCell() {
			return kind == Kind.TRAVERSE || kind == Kind.ASCEND ? new BlockPos(st.dx(), st.dy() - 1, st.dz()) : null;
		}

		private Object nextOp(Ctx c) {
			for (BlockPos b : clearCells()) {
				BlockState s = mc.level.getBlockState(b);
				int f = Blk.f(s);
				if ((f & (Blk.PASS | Blk.CLIMB)) != 0) continue;
				if (Blk.openable(s)) return new Interact.UseOp(b);
				return new Interact.BreakOp(b);
			}
			BlockPos fl = floorCell();
			if (fl != null) {
				BlockState s = mc.level.getBlockState(fl);
				int f = Blk.f(s);
				boolean wet = (f & Blk.WATER) != 0 && c.swim && kind == Kind.TRAVERSE;
				if ((f & (Blk.AIR | Blk.REPLACEABLE)) != 0 && !wet && !c.climbable(st.dx(), st.dy(), st.dz())) return new Interact.PlaceOp(fl);
			}
			return null;
		}

		private Res prep() {
			if (pause > 0) {
				pause--;
				standStill();
				return null;
			}
			if (op == null) {
				op = nextOp(nav.live());
				if (op == null) {
					if (clutch && !Inv.held().is(Items.WATER_BUCKET)) return equipBucket();
					moving = true;
					return null;
				}
			}
			standStill();
			Interact.R r = op instanceof Interact.BreakOp b ? b.tick() : op instanceof Interact.UseOp u ? u.tick() : ((Interact.PlaceOp) op).tick();
			switch (r) {
				case DONE -> {
					if (op instanceof Interact.PlaceOp po) com.humanisedbaritone.build.Scaffold.placed(po.target, mc.level.getBlockState(po.target).getBlock()); // a bridge floor: temporary
					op = null;
					pause = Rng.delayTicks(Settings.ACT_DELAY.d());
				}
				case FAIL -> {
					return fail("block operation failed: " + op.getClass().getSimpleName());
				}
				case NO_AIM -> {
					if (op instanceof Interact.PlaceOp) {
						// bridging: creep towards the edge (sneaking keeps us from falling) until the side of the block is in reach
						bridgeTicks++;
						Bot.ctl.sneak = true;
						double u = along(mc.player.position());
						if (u < 0.85 && bridgeTicks < 70) {
							Bot.ctl.walkTowards(Math.toDegrees(Math.atan2(-ux, uz)), mc.player.getYRot());
							Bot.look.aim(Math.toDegrees(Math.atan2(-ux, uz)), 62, 0.3, 6);
						} else if (bridgeTicks >= 70) return fail("no place to click while bridging");
					} else if (++stuck > 40) return fail("no visible face to work on");
				}
				default -> {
				}
			}
			return null;
		}

		private Res equipBucket() {
			standStill();
			if (equipDelay > 0) {
				equipDelay--;
				return null;
			}
			int slot = Inv.find(i -> i.is(Items.WATER_BUCKET));
			if (slot < 0) return bail("no water bucket to equip");
			if (slot < 9) Inv.select(slot);
			else Inv.toHotbar(slot);
			equipDelay = Rng.delayTicks(Settings.ACT_SWAP.d()) + 1;
			return null;
		}

		// ----------------------------------------------------------- geometry

		private double along(Vec3 p) {
			return (p.x - S.x) * ux + (p.z - S.z) * uz;
		}

		private double lateral(Vec3 p) {
			return -(p.x - S.x) * uz + (p.z - S.z) * ux;
		}

		private boolean walkish(Path.Step s) {
			return s != null && (s.kind() == Kind.TRAVERSE || s.kind() == Kind.DIAGONAL || s.kind() == Kind.DESCEND || s.kind() == Kind.RUN);
		}

		/** The step carries straight on from the previous one without a stop: walking on, or hopping up a block. */
		private boolean flows(Path.Step s) {
			return walkish(s) || s != null && s.kind() == Kind.ASCEND;
		}

		/** A point up to three blocks into step {@code n}, which starts where this one ends: what to turn towards while rounding the corner. */
		private static Vec3 ahead(Path.Step n) {
			Vec3 a = center(n.sx(), n.sy(), n.sz()), b = center(n.dx(), n.dy(), n.dz());
			double l = hdist(a, b.x, b.z);
			if (l <= 3) return b;
			return new Vec3(a.x + (b.x - a.x) * 3 / l, b.y, a.z + (b.z - a.z) * 3 / l);
		}

		/**
		 * Walking block by block (not across open ground, where a run takes over): head for the point a few blocks further along
		 * the route rather than for the middle of the next block, so that the staircase of straight and diagonal moves a grid
		 * search produces comes out as a gentle line. Only where the straight way to that point is plainly walkable; otherwise the
		 * route is followed as it is.
		 */
		private Vec3 pursue(Vec3 pos, Vec3 base) {
			Path path = nav.path();
			int i = nav.index();
			if (path == null || i >= path.steps.size() || path.steps.get(i) != st) return base;
			double cut = Settings.MOTION_CORNER.d();
			if (cut <= 0) return base;
			double left = 0.8 + 1.7 * cut + speed * 9 * (0.4 + 0.6 * cut) - hdist(pos, D.x, D.z); // how much of the look-ahead is left beyond this block
			if (left <= 0.3) return base;
			Vec3 from = D, carrot = null;
			for (int k = i + 1; k < path.steps.size() && left > 0; k++) {
				Path.Step s = path.steps.get(k);
				if (s.kind() != Kind.TRAVERSE && s.kind() != Kind.DIAGONAL || s.sy() != st.dy()) break; // only flat walking: a step up, a drop or a jump needs its own approach
				Vec3 to = center(s.dx(), s.dy(), s.dz());
				double l = hdist(from, to.x, to.z);
				if (l >= left) {
					carrot = new Vec3(from.x + (to.x - from.x) * left / l, to.y, from.z + (to.z - from.z) * left / l);
					break;
				}
				carrot = to;
				left -= l;
				from = to;
			}
			return carrot != null && walkable(nav.live(), st.dy(), pos.x, pos.z, carrot.x, carrot.z) ? carrot : base;
		}

		/** The body can go straight from one point to the other at this level: every cell it touches on the way is open and has a floor. */
		private static boolean walkable(Ctx c, int y, double x0, double z0, double x1, double z1) {
			double dx = x1 - x0, dz = z1 - z0;
			int n = Math.max(1, (int) Math.ceil(Math.hypot(dx, dz) / 0.4));
			for (int i = 1; i <= n; i++) {
				double x = x0 + dx * i / n, z = z0 + dz * i / n;
				int ax = (int) Math.floor(x - 0.45), bx = (int) Math.floor(x + 0.45), az = (int) Math.floor(z - 0.45), bz = (int) Math.floor(z + 0.45);
				for (int ix = ax; ix <= bx; ix++) for (int iz = az; iz <= bz; iz++) if (!c.valid(ix, y, iz)) return false;
			}
			return true;
		}

		private Vec3 target(Vec3 pos) {
			if (run) return runTarget(pos);
			Vec3 t = new Vec3(D.x - uz * lat, D.y, D.z + ux * lat);
			// already past the target (cut, or overshot while bouncing): carry on to what comes next instead of turning back for it
			if (!precise && next != null && kind != Kind.DESCEND && walkish(st) && along(pos) > len + 0.25) return ahead(next);
			if (!precise && (kind == Kind.TRAVERSE || kind == Kind.DIAGONAL) && next != null && (next.kind() == Kind.TRAVERSE || next.kind() == Kind.DIAGONAL)) {
				Vec3 c = pursue(pos, t);
				if (c != t) return c;
			}
			if (!precise && walkish(st) && flows(next) && Settings.MOTION_CORNER.d() > 0) {
				double cut = Settings.MOTION_CORNER.d();
				double d = hdist(pos, D.x, D.z), radius = 0.4 + 1.6 * cut;
				if (d < radius) {
					double w = (1 - d / radius) * cut * 0.9;
					Vec3 n = ahead(next);
					t = new Vec3(Mth.lerp(w, t.x, n.x), t.y, Mth.lerp(w, t.z, n.z));
				}
			}
			return t;
		}

		/**
		 * Pure pursuit along the run: aim at the point a few blocks further on the line, plus a slowly wandering sideways
		 * offset. The bot never heads for a block centre, so there is no swerving at every block, and a corner is rounded
		 * because the look-ahead point swings round to the next stretch before the end is reached.
		 */
		private Vec3 runTarget(Vec3 pos) {
			double a = along(pos);
			double look = 2.5 + speed * 14; // about 6 blocks at a sprint
			double reach = Math.min(Math.max(a, 0) + look, len);
			double fade = Mth.clamp((len - a) / 4.0, 0, 1); // the drift dies out before the end, so the run ends where the next step expects
			double off = Mth.clamp(drift.step(0.05), -driftLim, driftLim) * fade;
			Vec3 t = new Vec3(S.x + ux * reach - uz * off, D.y, S.z + uz * reach + ux * off);
			double cut = Settings.MOTION_CORNER.d();
			if (flows(next) && cut > 0 && a + look > len) {
				double w = Mth.clamp((a + look - len) / look, 0, 1) * (0.35 + 0.6 * cut);
				Vec3 n = ahead(next);
				t = new Vec3(Mth.lerp(w, t.x, n.x), t.y, Mth.lerp(w, t.z, n.z));
			}
			return t;
		}

		private boolean arrived(Vec3 pos, BlockPos feet, boolean ground, boolean water, boolean ladder) {
			boolean cell = feet.getX() == st.dx() && feet.getY() == st.dy() && feet.getZ() == st.dz();
			boolean support = ground || water || ladder;
			if (clutch && (Clutch.busy() || !ground && !water && ticks < 400)) return false;
			if (cell && support) return true;
			// landed beyond the target after a jump or a drop: fine, as long as it is solid ground at the right height
			if ((kind == Kind.PARKOUR || kind == Kind.DESCEND) && ground && Math.abs(pos.y - st.dy()) < 0.7 && along(pos) >= len - 0.35 && Math.abs(lateral(pos)) < 1.0) return true;
			// stepped up but landed beside the planned block, or a sprint-jump carried it on past: still on the upper level and not near an edge, so there is nothing to put right
			if (kind == Kind.ASCEND && !precise && ground && Math.abs(pos.y - st.dy()) < 0.3 && (hdist(pos, D.x, D.z) < 1.3
				|| along(pos) > len - 0.5 && along(pos) < len + 3.0 && Math.abs(lateral(pos)) < 1.3)) return true;
			if (kind == Kind.TRAVERSE || kind == Kind.DIAGONAL || kind == Kind.RUN) {
				double capture = precise || next == null ? 0.18 : 0.25 + 0.55 * Settings.MOTION_CORNER.d();
				if (support && hdist(pos, D.x, D.z) < capture && Math.abs(pos.y - st.dy()) < 0.55 && walkish(next)) return true;
				// gone past the target: when nothing is at stake there, going back for it only looks silly. A step up needs a better line than that, though
				double side = walkish(next) ? 1.4 : next != null && next.kind() == Kind.ASCEND ? 0.5 : 0.7;
				if (!precise && next != null && support && along(pos) >= len - 0.1 && Math.abs(lateral(pos)) < side && Math.abs(pos.y - st.dy()) < 0.7) return true;
			}
			return false;
		}

		private Res fail(String why) {
			return bail(why);
		}

		// ----------------------------------------------------------- stepping

		@Override Res step() {
			LocalPlayer p = mc.player;
			if (!moving) {
				Res r = prep();
				if (r != null) return r;
				if (!moving) return Res.RUN;
			}
			Vec3 pos = p.position();
			BlockPos feet = nav.feet();
			boolean ground = p.onGround(), water = p.isInWater(), ladder = p.onClimbable();
			if (arrived(pos, feet, ground, water, ladder)) return Res.DONE;
			// flat walking, yet the feet are firmly on another level (a jump over a corner, a slip down): do not go back for the height this step wanted
			if ((kind == Kind.TRAVERSE || kind == Kind.DIAGONAL || kind == Kind.RUN) && ground && Math.abs(pos.y - st.dy()) > 0.8) {
				if (++offLevel > 4) return Res.LOST;
			} else offLevel = 0;

			if (pos.y < st.sy() - 2.6 && kind != Kind.DESCEND && kind != Kind.PARKOUR) return fail("fell");
			if (kind == Kind.PARKOUR && pos.y < Math.min(st.sy(), st.dy()) - 3) return fail("fell during the jump");
			double u = along(pos), v = lateral(pos);
			if (len > 0 && (Math.abs(v) > 2.4 || u < -2.6 || u > len + 2.6)) return fail("off course u=" + u + " v=" + v);
			if (ticks > Math.max(60, st.cost() * 3 + 40 + (op != null ? 120 : 0))) return fail("timeout");
			if (run && ticks % 8 == 0 && Smooth.slack(nav.live(), st) < 0) return fail("something is in the way of the straight run");

			double dist = hdist(pos, D.x, D.z) + Math.abs(pos.y - st.dy()) * 0.5;
			if (dist < bestDist - 0.08) {
				bestDist = dist;
				noProgress = 0;
			} else if (++noProgress > Settings.STUCK_TICKS.i()) return fail("no progress");

			switch (kind) {
				case VERTICAL -> vertical(p, pos, water, ladder);
				case PARKOUR -> parkour(p, pos, u, ground);
				default -> walk(p, pos, u, ground, water);
			}
			return Res.RUN;
		}

		private void steer(Vec3 target, double urgency) {
			LocalPlayer p = mc.player;
			// Off the edge of a long drop nothing can be steered, and the cell below is behind the player by now: chasing it swung the camera half way round before
			// the clutch took over, which then placed the water, and looked for it, along a random heading. Keep the heading and look down already: the clutch needs it
			if (clutch && !p.onGround() && !p.isInWater() && !p.onClimbable()) {
				Bot.look.aim(p.getYRot(), 88, 1.0, 4);
				return;
			}
			Vec3 pos = p.position();
			double dx = target.x - pos.x, dz = target.z - pos.z;
			double heading = Math.toDegrees(Math.atan2(-dx, dz));
			double yaw = p.getYRot();
			boolean busy = precise || kind == Kind.PARKOUR;
			if (hdist(pos, target.x, target.z) < (busy ? 0.12 : 0.5)) {
				// practically there, where the direction of the target is just noise (and the camera would whip round): look at what comes next, or keep looking ahead
				Vec3 n = busy || next == null ? null : ahead(next);
				heading = n != null && hdist(pos, n.x, n.z) > 0.3 ? Math.toDegrees(Math.atan2(-(n.x - pos.x), n.z - pos.z)) : yaw;
			}
			// nothing can be steered in mid-air, and a camera that chases the landing block while jumping up to it swings round and back
			if (kind == Kind.ASCEND && !busy && !p.onGround() && !p.isInWater() && !p.onClimbable()) heading = yaw;
			double pitch = Gaze.pitch(true);
			if (Settings.ENDERMAN.on() && !busy && com.humanisedbaritone.behave.Safety.endermanNear()) pitch = Math.max(pitch, 58); // looking at an enderman's face angers it
			if (!busy && speed > 0.1 && !p.isInWater()) { // (in water a current can keep the speed up whatever the keys do)
				// Still going fast and the target is behind: do not whip the camera round for it and do not push on the wrong way. Look the way
				// we are going with the keys let go of, and turn once the speed is gone, the way a person does a U-turn
				double going = Math.toDegrees(Math.atan2(-nav.velX, nav.velZ));
				if (Math.abs(Mth.wrapDegrees(heading - going)) > 110) {
					Bot.look.aim(going + Gaze.yaw(true), pitch, urgency, 12);
					return;
				}
			}
			Bot.look.aim(heading + (busy ? 0 : Gaze.yaw(true)), pitch, urgency, busy ? 4 : 12);
			double rel = Mth.wrapDegrees(heading - yaw);
			if (hesitate > 0) {
				hesitate--;
				return;
			}
			if (Math.abs(rel) > 100) return;
			if (hdist(pos, target.x, target.z) > 0.12) Bot.ctl.walkTowards(heading, yaw, busy ? 22.5 : 40);
			if (strafeTap > 0) {
				strafeTap--;
				if (Bot.ctl.left || Bot.ctl.right) strafeTap = 0;
				else if (strafeTap % 2 == 0) Bot.ctl.left = true;
				else Bot.ctl.right = true;
			}
			if (!busy && speed < 0.25 && Rng.chance(Settings.MOTION_HESITATE.d() * 0.003)) hesitate = Rng.range(2, 6); // never in a full sprint: that would just be a stutter
			if (!busy && Rng.chance(Settings.MOTION_STRAFE.d() * 0.004)) strafeTap = Rng.range(1, 2);
		}

		private boolean wantSprint(Vec3 pos) {
			if (!Settings.SPRINT.on() || precise || op != null) return false;
			LocalPlayer p = mc.player;
			if (p.getFoodData().getFoodLevel() <= 6) return false;
			if (nav.movingTicks < nav.sprintDelay || nav.noSprintTicks > 0) return false;
			// soul sand, slime, honey and the like: nothing to gain, and slime bounces the sprint on past where it should stop
			Ctx c = nav.live();
			if (((c.f(st.sx(), st.sy() - 1, st.sz()) | c.f(st.dx(), st.dy() - 1, st.dz())) & Blk.SLOW) != 0) return false;
			// Walk up to a drop of more than a block and over its edge, and up to a step that is not straight ahead: a sprint carries on
			// for a few blocks in the air and throws the player past the landing
			if (kind == Kind.DESCEND && st.sy() - st.dy() >= 2) return false;
			if (next != null && (next.kind() == Kind.DESCEND && next.sy() - next.dy() >= 2 || next.kind() == Kind.ASCEND && !straightInto(next))) return false;
			return hdist(pos, D.x, D.z) > 0.5 || next != null;
		}

		/** The step {@code n} goes the way this one does, so whatever speed there is can simply be carried into it. */
		private boolean straightInto(Path.Step n) {
			double nx = n.dx() - n.sx(), nz = n.dz() - n.sz(), l = Math.hypot(nx, nz);
			return l > 0 && (nx * ux + nz * uz) / l > 0.9;
		}

		private void walk(LocalPlayer p, Vec3 pos, double u, boolean ground, boolean water) {
			Vec3 t = target(pos);
			steer(t, 0.3);
			if (wantSprint(pos)) Bot.ctl.sprint = true;
			if (jumpCd > 0) jumpCd--;
			// stepping up
			if (kind == Kind.ASCEND) {
				if (water) {
					Bot.ctl.jump = true;
				} else if (ground && jumpCd == 0) {
					double lead = Mth.clamp(speed * 3.2, 0.3, 1.25) + Rng.range(0, 0.12 * Settings.MOTION_JUMP_SLACK.d());
					// A jump goes the way the player is already going. Still sliding sideways from the step before (a diagonal into a step up),
					// or looking elsewhere, it would land beside the block or straight back down: let the line settle first
					double landing = lateral(pos) + 6 * (-nav.velX * uz + nav.velZ * ux);
					boolean facing = Math.abs(Mth.wrapDegrees(Math.toDegrees(Math.atan2(-ux, uz)) - p.getYRot())) < 35;
					boolean lined = u > 0.15 || Math.abs(landing) < 0.65 && facing && nav.velX * ux + nav.velZ * uz > 0.08;
					if (!lined) Bot.ctl.sprint = false;
					if (u >= 0.2 - lead && lined) {
						Bot.ctl.jump = true;
						jumpCd = 10;
					}
				}
			} else if (water) {
				double ty = st.dy() + 0.3;
				if (p.getY() < ty) Bot.ctl.jump = true;
			} else if (hop && Settings.SPRINT_JUMP.on() && ground && jumpCd == 0 && Bot.ctl.sprint && p.isSprinting()
				&& len - u > speed * 12 + 1.5 // a hop carries about four blocks: it must land well before the end of the run, where the next step wants a steady approach
				&& Math.abs(Mth.wrapDegrees(Math.toDegrees(Math.atan2(-ux, uz)) - p.getYRot())) < 30) { // a hop goes the way the camera faces
				Bot.ctl.jump = true;
				jumpCd = 10 + Rng.range(0, 2 * Settings.MOTION_JUMP_SLACK.i()); // a person lands and jumps again a tick or two late, not on the exact tick
			}
			// bumped into something: a step the model did not see
			if (p.horizontalCollision && Bot.ctl.forward) {
				if (++collideTicks > 6 && ground && jumpCd == 0) {
					Bot.ctl.jump = true;
					jumpCd = 14;
				}
			} else collideTicks = 0;
			// landing: stop pushing once over the target so momentum does not carry us past it
			if (kind == Kind.DESCEND && !ground && hdist(pos, D.x, D.z) < 0.3) Bot.ctl.forward = Bot.ctl.sprint = false;
			if (ground && Blk.has(mc.level.getBlockState(p.getBlockPosBelowThatAffectsMyMovement()), Blk.SLIPPERY)) {
				// ice: with the key held the speed keeps growing far past a walk, and after letting go the player slides about nine ticks' worth of it.
				// So ease off above walking pace, and coast into a stop or a turn
				if (speed > 0.2) Bot.ctl.forward = Bot.ctl.sprint = false;
				if ((kind == Kind.TRAVERSE || kind == Kind.DIAGONAL) && !straightOn() && u < len && len - u < speed * 9 + 0.2) {
					Bot.ctl.forward = Bot.ctl.back = Bot.ctl.left = Bot.ctl.right = Bot.ctl.sprint = false;
				}
			}
		}

		/** The next step carries on the same way, so there is nothing to slow down for. */
		private boolean straightOn() {
			return walkish(next) && next.dy() == st.dy() && next.dx() - next.sx() == st.dx() - st.sx() && next.dz() - next.sz() == st.dz() - st.sz();
		}

		private void vertical(LocalPlayer p, Vec3 pos, boolean water, boolean ladder) {
			int dy = st.dy() - st.sy();
			Bot.look.aim(p.getYRot(), dy > 0 ? 20 : 45, 0.25, 15);
			if (hdist(pos, D.x, D.z) > 0.35) {
				double heading = Math.toDegrees(Math.atan2(-(D.x - pos.x), D.z - pos.z));
				Bot.ctl.walkTowards(heading, p.getYRot());
			}
			if (dy > 0) Bot.ctl.jump = true;
			else if (water) Bot.ctl.sneak = true;
		}

		private void parkour(LocalPlayer p, Vec3 pos, double u, boolean ground) {
			double heading = Math.toDegrees(Math.atan2(-ux, uz));
			Bot.look.aim(heading, 8, 0.6, 3);
			double yaw = p.getYRot();
			boolean aligned = Math.abs(Mth.wrapDegrees(heading - yaw)) < 12;
			if (ground && !jumped) {
				double back = len >= 5 ? 3.6 : len >= 4 ? 2.6 : len >= 3 ? 1.0 : 0;
				if (!runUpDone && back > 0 && speed < 0.15 && u > -back + 0.15) {
					// build up speed first: walk backwards to get a run-up
					Bot.ctl.back = true;
					return;
				}
				runUpDone = true;
				if (!aligned) return;
				Bot.ctl.forward = true;
				Bot.ctl.sprint = true;
				double predicted = u + speed;
				if (predicted >= takeoff && ticks > 2 && speed > (len >= 3 ? 0.23 : 0.0)) {
					Bot.ctl.jump = true;
					jumped = true;
					Log.debug(String.format("  parkour jump len=%.0f u=%.2f speed=%.3f takeoff=%.2f sprinting=%b", len, u, speed, takeoff, p.isSprinting()));
				}
				return;
			}
			// airborne (or landing): keep going towards the far side
			Bot.ctl.forward = true;
			Bot.ctl.sprint = jumped;
			double dx = D.x - pos.x, dz = D.z - pos.z;
			double want = Math.toDegrees(Math.atan2(-dx, dz));
			double rel = Mth.wrapDegrees(want - yaw);
			if (Math.abs(rel) > 18) Bot.ctl.walkTowards(want, yaw);
			if (!ground && hdist(pos, D.x, D.z) < 0.25) Bot.ctl.forward = Bot.ctl.sprint = false;
		}
	}

	// ================================================================== Pillar

	static final class Pillar extends Exec {
		private int phase, tries, since, jumpCd, swap = -1;
		private Vec3 S;
		private double aimJx, aimJz;

		Pillar(Nav nav, Path.Step st, Path.Step next) {
			super(nav, st, next);
		}

		@Override void init() {
			S = center(st.sx(), st.sy(), st.sz());
			aimJx = Rng.gauss(0, 0.12);
			aimJz = Rng.gauss(0, 0.12);
		}

		@Override Res step() {
			LocalPlayer p = mc.player;
			Vec3 pos = p.position();
			BlockPos feet = nav.feet();
			if (feet.getY() >= st.dy() && p.onGround() && feet.getX() == st.dx() && feet.getZ() == st.dz()) return Res.DONE;
			if (ticks > 160 || tries > 5) return bail("pillar: timeout or too many jumps (" + tries + ")");
			// a block in the hand
			if (!(Inv.held().getItem() instanceof net.minecraft.world.item.BlockItem bi) || !Settings.PLACEABLE.has(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(bi.getBlock()).toString())) {
				if (swap > 0) {
					swap--;
					return Res.RUN;
				}
				int slot = Inv.find(s -> s.getItem() instanceof net.minecraft.world.item.BlockItem b && Settings.PLACEABLE.has(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(b.getBlock()).toString()));
				if (slot < 0) return bail("pillar: no block to place");
				if (slot < 9) Inv.select(slot);
				else Inv.toHotbar(slot);
				swap = Rng.delayTicks(Settings.ACT_SWAP.d()) + 1;
				return Res.RUN;
			}
			standStill();
			boolean soft = (Blk.f(mc.level.getBlockState(new BlockPos(st.sx(), st.sy() - 1, st.sz()))) & Blk.INTERACT) != 0; // the click goes on a chest, a hopper, a dispenser ...: only a sneaking player does not open it, and sneaking takes a tick to count
			Vec3 base = new Vec3(st.sx() + 0.5 + aimJx, st.sy(), st.sz() + 0.5 + aimJz);
			Bot.look.aimAt(base, 0.5, 4);
			// keep centred over the column
			if (hdist(pos, S.x, S.z) > 0.22 && p.onGround()) {
				double heading = Math.toDegrees(Math.atan2(-(S.x - pos.x), S.z - pos.z));
				Bot.ctl.walkTowards(heading, p.getYRot());
				Bot.ctl.sneak = false;
			}
			if (soft) Bot.ctl.sneak = true;
			if (jumpCd > 0) jumpCd--;
			boolean lookedDown = p.getXRot() > 62;
			double above = pos.y - st.sy();
			if (p.onGround() && lookedDown && jumpCd == 0 && above < 0.2 && hdist(pos, S.x, S.z) < 0.3) {
				Bot.ctl.jump = true;
				jumpCd = 5;
				tries++;
			}
			since++;
			if (!p.onGround() && above >= 1.0 && lookedDown) {
				var h = Interact.crosshair();
				if (h != null && h.getBlockPos().getY() == st.sy() - 1 && h.getDirection() == net.minecraft.core.Direction.UP && since > 2) {
					Bot.ctl.clickUse();
					if (Inv.held().getItem() instanceof net.minecraft.world.item.BlockItem held) com.humanisedbaritone.build.Scaffold.placed(new BlockPos(st.sx(), st.sy(), st.sz()), held.getBlock()); // the pillar: temporary
					since = -2;
				}
			}
			return Res.RUN;
		}
	}

	// ================================================================== Dig down

	static final class Dig extends Exec {
		private Interact.BreakOp op;
		private boolean dug;

		Dig(Nav nav, Path.Step st, Path.Step next) {
			super(nav, st, next);
		}

		@Override void init() {
			op = new Interact.BreakOp(new BlockPos(st.sx(), st.sy() - 1, st.sz()));
		}

		@Override Res step() {
			LocalPlayer p = mc.player;
			BlockPos feet = nav.feet();
			if (feet.getX() == st.dx() && feet.getY() == st.dy() && feet.getZ() == st.dz() && (p.onGround() || p.isInWater())) return Res.DONE;
			if (ticks > Math.max(240, st.cost() * 3 + 120)) return bail("dig down: timeout"); // hard blocks take long by hand
			if (!dug) {
				standStill();
				Vec3 pos = p.position();
				if (hdist(pos, st.sx() + 0.5, st.sz() + 0.5) > 0.3) {
					double heading = Math.toDegrees(Math.atan2(-(st.sx() + 0.5 - pos.x), st.sz() + 0.5 - pos.z));
					Bot.ctl.walkTowards(heading, p.getYRot());
					Bot.look.aim(heading, 30, 0.3, 10);
					return Res.RUN;
				}
				Interact.R r = op.tick();
				if (r == Interact.R.FAIL || r == Interact.R.NO_AIM && ticks > 60) return bail("dig down: " + (r == Interact.R.FAIL ? "breaking the floor failed" : "cannot aim at the floor block") + " (" + mc.level.getBlockState(new BlockPos(st.sx(), st.sy() - 1, st.sz())) + ", feet: " + mc.level.getBlockState(new BlockPos(st.sx(), st.sy(), st.sz())) + ")");
				if (r == Interact.R.DONE) dug = true;
			} else {
				standStill();
				Bot.look.aim(p.getYRot(), 70, 0.3, 10);
				// still held up by the corner of a neighbouring block: shuffle into the hole
				Vec3 pos = p.position();
				if (p.onGround() && feet.getY() > st.dy() && hdist(pos, st.sx() + 0.5, st.sz() + 0.5) > 0.05) {
					Bot.ctl.walkTowards(Math.toDegrees(Math.atan2(-(st.sx() + 0.5 - pos.x), st.sz() + 0.5 - pos.z)), p.getYRot());
				}
			}
			return Res.RUN;
		}
	}
}
