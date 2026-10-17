package com.humanisedbaritone.scan;

import com.humanisedbaritone.HumanisedBaritone;
import com.mojang.blaze3d.platform.NativeImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;

/**
 * The terrain you have seen, painted the way a vanilla map paints it, one 256 x 256 block tile per image file. Tiles are kept
 * in memory while they are used and written to {@code tiles/<x>_<z>.png} inside the store's folder, so every world and
 * dimension keeps its own map.
 */
public final class Atlas {
	public static final int SIZE = 256;
	private static final int MAX_RESIDENT = 220;
	private static int textureCounter;

	public static final class Tile {
		public final int tx, tz;
		final NativeImage img;
		DynamicTexture tex;
		Identifier id;
		boolean dirty, modified;
		/** A copy is on its way to the writer thread. */
		volatile boolean writing;
		long used;

		Tile(int tx, int tz, NativeImage img) {
			this.tx = tx;
			this.tz = tz;
			this.img = img;
		}

		/** The ARGB colour at a pixel of the tile (0 = nothing seen there). */
		public int pixel(int px, int pz) {
			return img.getPixel(px, pz);
		}
	}

	private final Path dir;
	private final Map<Long, Tile> tiles = new HashMap<>();
	private final Set<Long> onDisk = new HashSet<>();
	private boolean warned;
	/** Tiles the renderer may still read from disk this frame (decoding a PNG takes a moment). */
	public int loadsLeft = 2;

	public Atlas(Path dir) {
		this.dir = dir;
		try (Stream<Path> files = Files.exists(dir.resolve("tiles")) ? Files.list(dir.resolve("tiles")) : Stream.empty()) {
			files.forEach(f -> {
				String n = f.getFileName().toString();
				if (!n.endsWith(".png")) return;
				String[] p = n.substring(0, n.length() - 4).split("_");
				try {
					onDisk.add(key(Integer.parseInt(p[0]), Integer.parseInt(p[1])));
				} catch (RuntimeException ignored) {
				}
			});
		} catch (IOException ignored) {
		}
	}

	private static long key(int tx, int tz) {
		return ((long) tx << 32) | (tz & 0xFFFFFFFFL);
	}

	private Path file(int tx, int tz) {
		return dir.resolve("tiles").resolve(tx + "_" + tz + ".png");
	}

	/** How many tiles hold a map (on disk or in memory). */
	public int size() {
		Set<Long> all = new HashSet<>(onDisk);
		all.addAll(tiles.keySet());
		return all.size();
	}

	/** Every tile that has a map, as {tx, tz}. */
	public List<int[]> known() {
		Set<Long> all = new HashSet<>(onDisk);
		all.addAll(tiles.keySet());
		List<int[]> out = new ArrayList<>();
		for (long k : all) out.add(new int[]{(int) (k >> 32), (int) k});
		return out;
	}

	/** The tile if it has a map; {@code create} makes an empty one. Returns null when it is only on disk and the load budget is spent. */
	public Tile get(int tx, int tz, boolean create) {
		long k = key(tx, tz);
		Tile t = tiles.get(k);
		if (t != null) {
			t.used = System.nanoTime();
			return t;
		}
		if (onDisk.contains(k)) {
			if (!create && loadsLeft <= 0) return null;
			loadsLeft--;
			try (InputStream in = Files.newInputStream(file(tx, tz))) {
				t = new Tile(tx, tz, NativeImage.read(in));
				if (t.img.getWidth() != SIZE || t.img.getHeight() != SIZE) { // not ours: start over
					t.img.close();
					t = null;
				}
			} catch (IOException | RuntimeException e) {
				t = null;
				warn("could not read map tile " + tx + "," + tz + ": " + e);
			}
		}
		if (t == null && !create) return null;
		if (t == null) t = new Tile(tx, tz, new NativeImage(SIZE, SIZE, true));
		t.used = System.nanoTime();
		tiles.put(k, t);
		evict();
		return t;
	}

	private void warn(String s) {
		if (warned) return;
		warned = true;
		System.err.println("[humanised_baritone] " + s);
	}

	/** Keeps memory bounded: the tiles used least recently go, but only ones that are already on disk (the writer thread catches up with the others soon). */
	private void evict() {
		while (tiles.size() > MAX_RESIDENT) {
			Tile old = null;
			for (Tile t : tiles.values()) if (t.tex == null && !t.writing && !t.modified && (old == null || t.used < old.used)) old = t;
			if (old == null) return;
			tiles.remove(key(old.tx, old.tz));
			old.img.close();
		}
	}

	// ------------------------------------------------------------------ painting

	/**
	 * Paints one chunk. {@code refY} is the height the player is at: in a dimension with a ceiling (the Nether) the map shows
	 * the floor below that height, otherwise the surface.
	 */
	public void paint(ClientLevel level, LevelChunk chunk, int refY) {
		int cx = chunk.getPos().x(), cz = chunk.getPos().z();
		Tile t = get(Math.floorDiv(cx, 16), Math.floorDiv(cz, 16), true);
		int ox = Math.floorMod(cx, 16) * 16, oz = Math.floorMod(cz, 16) * 16;
		boolean ceiling = level.dimensionType().hasCeiling();
		LevelChunk north = level.getChunkSource().getChunkNow(cx, cz - 1);
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		int[][] h = new int[17][16];
		for (int lz = 0; lz < 16; lz++) for (int lx = 0; lx < 16; lx++) h[lz + 1][lx] = surface(level, chunk, lx, lz, refY, ceiling, p);
		for (int lx = 0; lx < 16; lx++) h[0][lx] = north != null ? surface(level, north, lx, 15, refY, ceiling, p) : h[1][lx];
		for (int lz = 0; lz < 16; lz++) {
			for (int lx = 0; lx < 16; lx++) {
				int y = h[lz + 1][lx];
				int argb = 0;
				if (y != Integer.MIN_VALUE) argb = color(level, chunk, lx, y, lz, h[lz][lx] == Integer.MIN_VALUE ? y : h[lz][lx], p);
				t.img.setPixel(ox + lx, oz + lz, argb);
			}
		}
		t.dirty = true;
		t.modified = true;
	}

	private static int surface(ClientLevel level, LevelChunk c, int lx, int lz, int refY, boolean ceiling, BlockPos.MutableBlockPos p) {
		int minY = level.getMinY(), bx = c.getPos().getMinBlockX() + lx, bz = c.getPos().getMinBlockZ() + lz;
		if (ceiling) {
			for (int y = Math.min(level.getMaxY(), refY + 4); y >= minY; y--) {
				BlockState s = c.getBlockState(p.set(bx, y, bz));
				if (!s.isAir() && (s.getMapColor(level, p) != MapColor.NONE || !s.getFluidState().isEmpty())) return y;
			}
			return Integer.MIN_VALUE;
		}
		int y = c.getHeight(Heightmap.Types.MOTION_BLOCKING, lx, lz);
		for (; y >= minY; y--) { // decoration that has no colour of its own (glass, air pockets) shows what is below
			BlockState s = c.getBlockState(p.set(bx, y, bz));
			if (!s.isAir() && (s.getMapColor(level, p) != MapColor.NONE || !s.getFluidState().isEmpty())) return y;
		}
		return Integer.MIN_VALUE;
	}

	/** The pixel of a column: the block's map colour, lighter or darker with the slope towards the north (water: with its depth). */
	private static int color(ClientLevel level, LevelChunk c, int lx, int y, int lz, int northY, BlockPos.MutableBlockPos p) {
		int bx = c.getPos().getMinBlockX() + lx, bz = c.getPos().getMinBlockZ() + lz;
		BlockState s = c.getBlockState(p.set(bx, y, bz));
		MapColor mc = s.getMapColor(level, p);
		if (s.getFluidState().is(FluidTags.WATER) || mc == MapColor.WATER) {
			int depth = 1;
			for (int yy = y - 1; yy > level.getMinY() && depth < 24; yy--, depth++) {
				if (!c.getBlockState(p.set(bx, yy, bz)).getFluidState().is(FluidTags.WATER)) break;
			}
			double d = depth * 0.1 + ((bx + bz) & 1) * 0.2;
			return MapColor.WATER.calculateARGBColor(d < 0.5 ? MapColor.Brightness.HIGH : d > 0.9 ? MapColor.Brightness.LOW : MapColor.Brightness.NORMAL);
		}
		double d = (y - northY) * 4.0 / 5.0 + (((bx + bz) & 1) - 0.5) * 0.4;
		return mc.calculateARGBColor(d > 0.6 ? MapColor.Brightness.HIGH : d < -0.6 ? MapColor.Brightness.LOW : MapColor.Brightness.NORMAL);
	}

	// ------------------------------------------------------------------ textures

	/** The texture of a tile for drawing, uploaded when the pixels changed. */
	public Identifier texture(Tile t) {
		if (t.tex == null) {
			t.id = Identifier.fromNamespaceAndPath(HumanisedBaritone.ID, "map/" + textureCounter++);
			t.tex = new DynamicTexture(() -> "humanised baritone map " + t.tx + "," + t.tz, SIZE, SIZE, false);
			Minecraft.getInstance().getTextureManager().register(t.id, t.tex);
			t.dirty = true;
		}
		if (t.dirty) {
			t.tex.getPixels().copyFrom(t.img);
			t.tex.upload();
			t.dirty = false;
		}
		t.used = System.nanoTime();
		return t.id;
	}

	/** Frees the textures of tiles not drawn for a while ({@code all}: every one of them). The pixels stay in memory. */
	public void releaseTextures(boolean all) {
		long old = System.nanoTime() - 5_000_000_000L;
		for (Tile t : tiles.values()) {
			if (t.tex == null || !all && t.used > old) continue;
			Minecraft.getInstance().getTextureManager().release(t.id);
			t.tex = null;
			t.id = null;
		}
	}

	// ------------------------------------------------------------------- saving

	/** One thread writes the map images, so encoding a PNG never stalls a frame. */
	private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(r -> {
		Thread th = new Thread(r, "humanised-baritone map writer");
		th.setDaemon(true);
		return th;
	});

	private Path tmp(Tile t) {
		return dir.resolve("tiles").resolve(t.tx + "_" + t.tz + ".png.tmp");
	}

	/** Hands a copy of the tile to the writer thread. */
	private void writeLater(Tile t) {
		NativeImage copy = new NativeImage(SIZE, SIZE, false);
		copy.copyFrom(t.img);
		t.modified = false;
		t.writing = true;
		onDisk.add(key(t.tx, t.tz));
		Path file = file(t.tx, t.tz), tmp = tmp(t);
		WRITER.execute(() -> {
			try {
				Files.createDirectories(file.getParent());
				copy.writeToFile(tmp);
				Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
			} catch (IOException | RuntimeException e) {
				warn("could not write map tile " + t.tx + "," + t.tz + ": " + e);
			} finally {
				copy.close();
				t.writing = false;
			}
		});
	}

	/** Writes the tile now, on this thread (for leaving the world, when the writer has to be finished with anyway). */
	private void writeNow(Tile t) {
		try {
			Files.createDirectories(dir.resolve("tiles"));
			t.img.writeToFile(tmp(t));
			Files.move(tmp(t), file(t.tx, t.tz), StandardCopyOption.REPLACE_EXISTING);
			onDisk.add(key(t.tx, t.tz));
			t.modified = false;
		} catch (IOException | RuntimeException e) {
			warn("could not write map tile " + t.tx + "," + t.tz + ": " + e);
		}
	}

	/** Waits until everything handed to the writer thread is on disk. */
	public static void flushWrites() {
		try {
			WRITER.submit(() -> { }).get(30, TimeUnit.SECONDS);
		} catch (Exception ignored) {
		}
	}

	/** Hands at most {@code max} changed tiles to the writer thread. */
	public void saveSome(int max) {
		for (Tile t : tiles.values()) {
			if (max <= 0) return;
			if (!t.modified || t.writing) continue;
			writeLater(t);
			max--;
		}
	}

	/** Everything changed is on disk when this returns. */
	public void saveAll() {
		flushWrites();
		for (Tile t : tiles.values()) if (t.modified) writeNow(t);
	}

	/** Forgets the whole map, in memory and on disk. */
	public void clear() {
		flushWrites();
		releaseTextures(true);
		for (Tile t : tiles.values()) t.img.close();
		tiles.clear();
		for (long k : onDisk) {
			try {
				Files.deleteIfExists(file((int) (k >> 32), (int) k));
			} catch (IOException ignored) {
			}
		}
		onDisk.clear();
	}

	/** Gives the memory back (the store is no longer shown or recorded to). */
	public void close() {
		saveAll();
		releaseTextures(true);
		for (Tile t : tiles.values()) t.img.close();
		tiles.clear();
	}
}
