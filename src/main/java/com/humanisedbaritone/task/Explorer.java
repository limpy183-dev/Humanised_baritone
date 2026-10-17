package com.humanisedbaritone.task;

import com.humanisedbaritone.human.Rng;
import com.humanisedbaritone.path.Goal;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

/** Keeps track of where the player has been and suggests somewhere new to go. */
final class Explorer {
	private static final Minecraft mc = Minecraft.getInstance();
	private static final Set<Long> visited = new HashSet<>();

	private Explorer() {
	}

	private static long key(int cx, int cz) {
		return ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
	}

	static void reset() {
		visited.clear();
	}

	static int visitedChunks() {
		return visited.size();
	}

	/** Records the chunks around the player as seen. */
	static void mark() {
		int cx = mc.player.getBlockX() >> 4, cz = mc.player.getBlockZ() >> 4;
		for (int a = -2; a <= 2; a++) for (int b = -2; b <= 2; b++) visited.add(key(cx + a, cz + b));
	}

	/** A far away column in a direction that has not been seen yet, inside the loaded area. */
	static Goal pick(double minDist, double maxDist) {
		Vec3 p = mc.player.position();
		Goal best = null;
		double bestScore = -1e9;
		for (int i = 0; i < 28; i++) {
			double a = Rng.range(0, Math.PI * 2), d = Rng.range(minDist, maxDist);
			int x = (int) Math.floor(p.x + Math.cos(a) * d), z = (int) Math.floor(p.z + Math.sin(a) * d);
			if (mc.level.getChunkSource().getChunkNow(x >> 4, z >> 4) == null) continue;
			if (!mc.level.getWorldBorder().isWithinBounds(x, z)) continue;
			int fresh = 0;
			for (int da = -1; da <= 1; da++) for (int db = -1; db <= 1; db++) if (!visited.contains(key((x >> 4) + da, (z >> 4) + db))) fresh++;
			double score = fresh * 2 + d / maxDist + Rng.range(0, 1.2);
			if (score > bestScore) {
				bestScore = score;
				best = new Goal.Column(x, z);
			}
		}
		return best;
	}
}
