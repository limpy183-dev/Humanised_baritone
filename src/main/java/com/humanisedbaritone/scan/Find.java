package com.humanisedbaritone.scan;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/** One thing the scanner logged. Plain public fields so Gson reads and writes it as it is. */
public final class Find {
	public enum Kind {
		STASH("Stash", 0xFFFF9F2E, Items.CHEST),
		SPAWNER("Spawner", 0xFFB46CFF, Items.SPAWNER),
		PLAYER("Player", 0xFF4ADE80, Items.PLAYER_HEAD),
		SEARCH("Search", 0xFF22D3EE, Items.DIAMOND_ORE),
		PORTAL("Portal", 0xFFF472B6, Items.OBSIDIAN),
		BASE("Base marker", 0xFFFACC15, Items.BEACON),
		ITEM("Valuable item", 0xFFA3E635, Items.ITEM_FRAME),
		SIGN("Sign", 0xFFD6B98C, Items.OAK_SIGN),
		DEATH("Death", 0xFFEF4444, Items.SKELETON_SKULL),
		NOTE("Marker", 0xFF60A5FA, Items.PAPER);

		public final String label;
		public final int color;
		public final Item icon;

		Kind(String label, int color, Item icon) {
			this.label = label;
			this.color = color;
			this.icon = icon;
		}

		public int bit() {
			return 1 << ordinal();
		}
	}

	/** A marker whose height is not known (dropped on the map): travel goes to the column. */
	public static final int NO_Y = Integer.MIN_VALUE;

	public int id;
	public Kind kind = Kind.NOTE;
	public int x, y = NO_Y, z;
	/** Short title ("23 containers") and the line below it ("14 Chest, 6 Barrel, 3 Shulker Box"). */
	public String label = "", detail = "";
	/** What makes two finds the same thing: a block id, a player's UUID, an item id. */
	public String key = "";
	public int count = 1;
	/** A cluster's reach in blocks (its members are within this of its middle); 0 for a single point. */
	public int radius;
	/** Box around the members of a cluster. */
	public int minX, minY, minZ, maxX, maxY, maxZ;
	/** When it was first and last seen (epoch milliseconds), and how often it came into view (players). */
	public long first, last;
	public int seen = 1;
	/** The user has dealt with it. */
	public boolean checked;
	/** The size it had when the last chat alert went out, so only real growth alerts again. */
	public int alerted;

	public boolean hasY() {
		return y != NO_Y;
	}

	public String where() {
		return hasY() ? x + ", " + y + ", " + z : x + ", " + z;
	}

	public double dist2(double px, double pz) {
		double dx = x + 0.5 - px, dz = z + 0.5 - pz;
		return dx * dx + dz * dz;
	}

	/** "just now", "5 min ago", "3 h ago", "2 d ago". */
	public static String ago(long then) {
		long s = Math.max(0, (System.currentTimeMillis() - then) / 1000);
		if (s < 10) return "just now";
		if (s < 90) return s + " s ago";
		if (s < 5400) return Math.round(s / 60.0) + " min ago";
		if (s < 129600) return Math.round(s / 3600.0) + " h ago";
		return Math.round(s / 86400.0) + " d ago";
	}
}
