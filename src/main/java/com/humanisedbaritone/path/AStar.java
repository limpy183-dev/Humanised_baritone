package com.humanisedbaritone.path;

import com.humanisedbaritone.path.Moves.Kind;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A* over standing positions. Runs on a worker thread with its own {@link Ctx}. When the goal cannot be reached in time
 * it returns the best partial path, chosen the way Baritone does: nodes that are close to the goal relative to what
 * they cost, at a safe distance from the start.
 */
public final class AStar {
	private static final double[] COEFF = {1.5, 2, 2.5, 3, 4, 5, 10};
	private static final double MIN_PARTIAL_DIST_SQ = 25;

	private static final class Node {
		final int x, y, z;
		double g, f, h;
		Node parent;
		Kind kind;
		boolean placed;
		double edge;
		int heap = -1;

		Node(int x, int y, int z) {
			this.x = x;
			this.y = y;
			this.z = z;
		}
	}

	private final Ctx ctx;
	private final Goal goal;
	private final int sx, sy, sz;
	private final Long2ObjectOpenHashMap<Node> nodes = new Long2ObjectOpenHashMap<>(8192);
	private Node[] heap = new Node[2048];
	private int heapSize;
	public volatile boolean cancelled;
	public int expanded;

	public AStar(Ctx ctx, Goal goal, int sx, int sy, int sz) {
		this.ctx = ctx;
		this.goal = goal;
		this.sx = sx;
		this.sy = sy;
		this.sz = sz;
	}

	static long key(int x, int y, int z) {
		return ((long) x & 0x3FFFFFF) << 38 | ((long) z & 0x3FFFFFF) << 12 | ((long) (y + 2048) & 0xFFF);
	}

	/** Returns null when nothing usable was found (unreachable, cancelled or out of time). */
	public Path run(long primaryMs, long failMs, int maxNodes, double weight) {
		long start = System.nanoTime();
		Node first = new Node(sx, sy, sz);
		first.h = goal.h(sx, sy, sz) * weight;
		first.f = first.h;
		nodes.put(key(sx, sy, sz), first);
		push(first);
		Node[] best = new Node[COEFF.length];
		double[] bestScore = new double[COEFF.length];
		java.util.Arrays.fill(bestScore, Double.MAX_VALUE);
		Moves.Out out = new Moves.Out();

		while (heapSize > 0) {
			if ((expanded & 255) == 0) {
				if (cancelled) return null;
				long ms = (System.nanoTime() - start) / 1_000_000;
				if (ms > failMs || expanded > maxNodes) break;
				if (ms > primaryMs && partial(best) != null) break;
			}
			Node cur = pop();
			if (goal.isAt(cur.x, cur.y, cur.z)) return build(cur, true);
			expanded++;
			out.reset();
			Moves.expand(ctx, cur.x, cur.y, cur.z, cur.placed, out);
			for (int i = 0; i < out.n; i++) {
				int nx = out.x[i], ny = out.y[i], nz = out.z[i];
				if (ctx.isBanned(nx, ny, nz)) continue;
				long k = key(nx, ny, nz);
				double g = cur.g + out.cost[i];
				Node n = nodes.get(k);
				if (n == null) {
					n = new Node(nx, ny, nz);
					n.h = goal.h(nx, ny, nz) * weight;
					n.g = g;
					n.f = g + n.h;
					n.parent = cur;
					n.kind = out.kind[i];
					n.placed = out.placed[i];
					n.edge = out.cost[i];
					nodes.put(k, n);
					push(n);
					for (int c = 0; c < COEFF.length; c++) {
						double score = n.h + g / COEFF[c];
						if (score < bestScore[c] - 0.01) {
							bestScore[c] = score;
							best[c] = n;
						}
					}
				} else if (g < n.g - 0.01) {
					n.g = g;
					n.f = g + n.h;
					n.parent = cur;
					n.kind = out.kind[i];
					n.placed = out.placed[i];
					n.edge = out.cost[i];
					if (n.heap >= 0) up(n.heap);
					else push(n);
				}
			}
		}
		Node p = partial(best);
		return p == null ? null : build(p, false);
	}

	private Node partial(Node[] best) {
		for (Node n : best) {
			if (n == null) continue;
			int dx = n.x - sx, dy = n.y - sy, dz = n.z - sz;
			if (dx * dx + dy * dy + dz * dz > MIN_PARTIAL_DIST_SQ) return n;
		}
		return null;
	}

	private Path build(Node end, boolean complete) {
		List<Path.Step> steps = new ArrayList<>();
		for (Node n = end; n.parent != null; n = n.parent) {
			Node p = n.parent;
			steps.add(new Path.Step(n.kind, p.x, p.y, p.z, n.x, n.y, n.z, n.edge));
		}
		Collections.reverse(steps);
		return new Path(steps, complete, end.x, end.y, end.z, end.g);
	}

	// ----------------------------------------------------------------- binary heap

	private void push(Node n) {
		if (heapSize == heap.length) heap = java.util.Arrays.copyOf(heap, heap.length * 2);
		heap[heapSize] = n;
		n.heap = heapSize++;
		up(n.heap);
	}

	private Node pop() {
		Node top = heap[0];
		Node last = heap[--heapSize];
		heap[heapSize] = null;
		top.heap = -1;
		if (heapSize > 0) {
			heap[0] = last;
			last.heap = 0;
			down(0);
		}
		return top;
	}

	private void up(int i) {
		Node n = heap[i];
		while (i > 0) {
			int p = (i - 1) >>> 1;
			Node pn = heap[p];
			if (pn.f <= n.f) break;
			heap[i] = pn;
			pn.heap = i;
			i = p;
		}
		heap[i] = n;
		n.heap = i;
	}

	private void down(int i) {
		Node n = heap[i];
		while (true) {
			int l = 2 * i + 1;
			if (l >= heapSize) break;
			int r = l + 1;
			int c = r < heapSize && heap[r].f < heap[l].f ? r : l;
			if (heap[c].f >= n.f) break;
			heap[i] = heap[c];
			heap[i].heap = i;
			i = c;
		}
		heap[i] = n;
		n.heap = i;
	}
}
