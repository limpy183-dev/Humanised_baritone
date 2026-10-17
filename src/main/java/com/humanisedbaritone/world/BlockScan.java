package com.humanisedbaritone.world;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

/** Finds blocks in the loaded world quickly by asking each chunk section's palette before looking at single blocks. */
public final class BlockScan {
	private static final Minecraft mc = Minecraft.getInstance();

	private BlockScan() {
	}

	/** Matching blocks within {@code radius} of {@code center}, nearest first, at most {@code limit} of them. */
	public static List<BlockPos> find(BlockPos center, int radius, Predicate<BlockState> match, int limit) {
		List<BlockPos> out = new ArrayList<>();
		var level = mc.level;
		if (level == null) return out;
		int r2 = radius * radius;
		int minCx = (center.getX() - radius) >> 4, maxCx = (center.getX() + radius) >> 4;
		int minCz = (center.getZ() - radius) >> 4, maxCz = (center.getZ() + radius) >> 4;
		int minY = level.getMinY();
		for (int cx = minCx; cx <= maxCx; cx++) {
			for (int cz = minCz; cz <= maxCz; cz++) {
				LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
				if (chunk == null) continue;
				LevelChunkSection[] sections = chunk.getSections();
				for (int si = 0; si < sections.length; si++) {
					LevelChunkSection sec = sections[si];
					if (sec.hasOnlyAir() || !sec.maybeHas(match)) continue;
					int baseY = minY + si * 16;
					if (baseY + 15 < center.getY() - radius || baseY > center.getY() + radius) continue;
					for (int x = 0; x < 16; x++) {
						for (int y = 0; y < 16; y++) {
							for (int z = 0; z < 16; z++) {
								BlockState st = sec.getBlockState(x, y, z);
								if (!match.test(st)) continue;
								int wx = (cx << 4) + x, wy = baseY + y, wz = (cz << 4) + z;
								int dx = wx - center.getX(), dy = wy - center.getY(), dz = wz - center.getZ();
								if (dx * dx + dy * dy + dz * dz <= r2) out.add(new BlockPos(wx, wy, wz));
							}
						}
					}
				}
			}
		}
		out.sort((a, b) -> Double.compare(a.distSqr(center), b.distSqr(center)));
		return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
	}

	/** Every block of one chunk that matches, handed to {@code out} (a section whose palette cannot match is skipped without looking at a block). */
	public static void inChunk(LevelChunk chunk, Predicate<BlockState> match, BiConsumer<BlockPos, BlockState> out) {
		var level = mc.level;
		if (level == null) return;
		LevelChunkSection[] sections = chunk.getSections();
		int baseX = chunk.getPos().getMinBlockX(), baseZ = chunk.getPos().getMinBlockZ(), minY = level.getMinY();
		for (int si = 0; si < sections.length; si++) {
			LevelChunkSection sec = sections[si];
			if (sec.hasOnlyAir() || !sec.maybeHas(match)) continue;
			for (int x = 0; x < 16; x++) {
				for (int y = 0; y < 16; y++) {
					for (int z = 0; z < 16; z++) {
						BlockState st = sec.getBlockState(x, y, z);
						if (match.test(st)) out.accept(new BlockPos(baseX + x, minY + si * 16 + y, baseZ + z), st);
					}
				}
			}
		}
	}

	/** A block with at least one face open to air or liquid (visible from a cave or the surface). */
	public static boolean exposed(BlockPos p) {
		for (var d : net.minecraft.core.Direction.values()) {
			int f = Blk.f(mc.level.getBlockState(p.relative(d)));
			if ((f & Blk.FULL) == 0) return true;
		}
		return false;
	}
}
