package com.humanisedbaritone;

import com.humanisedbaritone.gui.Ui;
import com.humanisedbaritone.path.Goal;
import com.humanisedbaritone.path.Path;
import com.humanisedbaritone.settings.Settings;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoProperties;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.phys.Vec3;

/** In-world overlay (path, goal, block targets) drawn with the vanilla gizmo system. */
public final class Render {
	private static final List<BlockPos> breakTargets = new ArrayList<>(), placeTargets = new ArrayList<>();

	private Render() {
	}

	public static void markBreak(BlockPos p) {
		breakTargets.add(p);
	}

	public static void markPlace(BlockPos p) {
		placeTargets.add(p);
	}

	private static GizmoProperties top(GizmoProperties g) {
		return Settings.RENDER_TOP.on() ? g.setAlwaysOnTop() : g;
	}

	/** End of client tick: gizmos created here stay visible until the next tick. */
	public static void tick() {
		try {
			com.humanisedbaritone.build.Ghosts.tick();
			com.humanisedbaritone.scan.Radar.highlight();
			draw();
		} finally {
			breakTargets.clear();
			placeTargets.clear();
		}
	}

	private static void draw() {
		if (!Bot.enabled || Bot.mc.player == null) return;
		int acc = Ui.accent();
		Path path = Bot.nav.path();
		if (path != null && Settings.RENDER_PATH.on()) {
			int from = Math.max(0, Bot.nav.index());
			for (int i = from; i < path.steps.size() && i < from + 160; i++) {
				Path.Step s = path.steps.get(i);
				Vec3 a = new Vec3(s.sx() + 0.5, s.sy() + 0.08, s.sz() + 0.5), b = new Vec3(s.dx() + 0.5, s.dy() + 0.08, s.dz() + 0.5);
				int c = switch (s.kind()) {
					case PARKOUR -> 0xFFFFD166;
					case PILLAR, DIG_DOWN -> 0xFFFF8FA3;
					case ASCEND, DESCEND -> Ui.mix(acc, 0xFFFFFFFF, 0.35f);
					default -> acc;
				};
				top(Gizmos.line(a, b, c, 4.0f));
			}
		}
		Goal g = Bot.nav.goal();
		if (g != null && Settings.RENDER_GOAL.on() && !(g instanceof Goal.Away) && !(g instanceof Goal.Level)) {
			top(Gizmos.cuboid(g.anchor(), 0.02f, GizmoStyle.strokeAndFill(0xFF46E6A0, 3.0f, 0x2246E6A0)));
		}
		if (Settings.RENDER_BLOCKS.on()) {
			for (BlockPos p : breakTargets) top(Gizmos.cuboid(p, 0.01f, GizmoStyle.strokeAndFill(0xFFFF5A6E, 3.0f, 0x26FF5A6E)));
			for (BlockPos p : placeTargets) top(Gizmos.cuboid(p, 0.01f, GizmoStyle.strokeAndFill(0xFF6EA8FF, 3.0f, 0x266EA8FF)));
		}
	}
}
