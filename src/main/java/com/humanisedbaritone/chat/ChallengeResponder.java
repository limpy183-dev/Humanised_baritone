package com.humanisedbaritone.chat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;
import java.util.regex.Pattern;

/** Chat parsing and delayed replies, independent of Minecraft and driven on the client thread. */
public final class ChallengeResponder {
	public enum Kind { REVERSE, UNSCRAMBLE, NUMBER }
	public record Options(boolean enabled, long minMillis, long maxMillis) {}
	private record Reply(Kind kind, String answer, long due) {}
	private static final Pattern FORMAT = Pattern.compile("(?i)\u00a7[0-9a-fk-orx]");
	private static final String PREFIX = "^(?:\\[(?:chat ?games?|challenge)\\]\\s*)?";
	private static final Pattern WORD = Pattern.compile(PREFIX + "(unreverse\\s+(?:the\\s+)?letters|unscramble\\s+(?:the\\s+)?word)(?:\\s+to\\s+win)?\\s*[!:]?\\s*(.*)$", Pattern.CASE_INSENSITIVE);
	private static final Pattern NUMBER = Pattern.compile(PREFIX + "guess\\s+(?:(?:the|a)\\s+)?(?:number\\s+)?between\\b(.*)$", Pattern.CASE_INSENSITIVE);
	private static final Pattern RANGE = Pattern.compile("^(?:a\\s+number\\s+)?(\\d[\\d,]*)\\s*(?:and|to|[-\u2013])\\s*(\\d[\\d,]*)(?:\\s+to\\s+win)?\\s*[!.]?$", Pattern.CASE_INSENSITIVE);
	private static final Pattern MARKER = Pattern.compile("^(?:[>\u00bb\u203a\u279c\u27a4\u27a5\u27a6\u27a7\u27a8\u27a9\u27aa\u27ab\u27ac\u27ad\u27ae\u27af\u27b0\u27b1\u27b2\u27b3\u27b4\u27b5\u27b6\u27b7\u27b8\u27b9\u27ba\u27bb\u27bc\u27bd\u27be\u2192]+\\s*|:\\s*)");
	private static final Pattern FINISHED = Pattern.compile(PREFIX + "(?:the\\s+)?(?:chat\\s+)?(?:challenge|game)\\s+(?:is\\s+)?(?:over|ended|expired|cancelled|canceled)\\b.*", Pattern.CASE_INSENSITIVE);
	private final Function<Kind, Options> options;
	private final Supplier<List<String>> names;
	private final RandomGenerator random;
	private Map<String, List<String>> dictionary;
	private final Map<String, Long> seen = new LinkedHashMap<>();
	private Kind waiting;
	private long waitingUntil;
	private Reply reply;
	private String status = "Waiting for a challenge.";

	public ChallengeResponder(Function<Kind, Options> options, Supplier<List<String>> names, RandomGenerator random) {
		this.options = options;
		this.names = names;
		this.random = random;
	}

	public String status() { return status; }

	/** Reset on disconnect / switch-off, so a queued answer never follows the player to another server. */
	public void reset() {
		waiting = null;
		reply = null;
		seen.clear();
		dictionary = null;
		status = "Waiting for a challenge.";
	}

	public void receive(String message, long now) {
		if (message == null || message.length() > 4096) return;
		refresh(now);
		for (String raw : FORMAT.matcher(message).replaceAll("").replace('\u00a0', ' ').split("\\R")) {
			String line = raw.strip();
			if (line.isEmpty()) continue;
			if (FINISHED.matcher(line).matches()) {
				waiting = null;
				reply = null;
				status = "Challenge ended.";
				continue;
			}
			var word = WORD.matcher(line);
			var number = NUMBER.matcher(line);
			if (word.matches()) {
				begin(word.group(1).toLowerCase(Locale.ROOT).startsWith("unreverse") ? Kind.REVERSE : Kind.UNSCRAMBLE, word.group(2), now);
			} else if (number.matches()) {
				begin(Kind.NUMBER, number.group(1).replaceFirst("^[\\s!:]+", ""), now);
			} else if (waiting != null) {
				// Only arrow-prefixed continuation lines belong to a split announcement.
				// Ordinary chat between the header and the puzzle must not become an answer.
				var marker = MARKER.matcher(line);
				if (marker.find()) {
					Kind kind = waiting;
					waiting = null;
					solve(kind, line.substring(marker.end()).strip(), now);
				}
			}
		}
	}

	private void begin(Kind kind, String tail, long now) {
		waiting = null;
		if (!options.apply(kind).enabled()) {
			reply = null;
			return;
		}
		String payload = MARKER.matcher(tail.strip()).replaceFirst("").strip();
		if (payload.isEmpty()) {
			waiting = kind;
			waitingUntil = now + 5000;
			status = "Waiting for the challenge text...";
		} else solve(kind, payload, now);
	}

	private void solve(Kind kind, String payload, long now) {
		if (payload.isEmpty() || payload.length() > 256) return;
		String key = kind + ":" + payload;
		seen.entrySet().removeIf(e -> now - e.getValue() > 60000);
		if (seen.containsKey(key)) return;
		// The latest complete challenge supersedes an earlier, unanswered one.
		reply = null;
		String answer = switch (kind) {
			case REVERSE -> payload.matches("[\\p{L}\\p{N}_ ]+") ? new StringBuilder(payload).reverse().toString() : null;
			case UNSCRAMBLE -> unscramble(payload);
			case NUMBER -> guess(payload);
		};
		if (answer == null) {
			status = kind == Kind.UNSCRAMBLE ? "No matching block or item; skipped." : "Could not read the challenge; skipped.";
			return;
		}
		seen.put(key, now);
		if (seen.size() > 64) seen.remove(seen.keySet().iterator().next());
		Options o = options.apply(kind);
		long lo = Math.clamp(Math.min(o.minMillis(), o.maxMillis()), 0, 30000);
		long hi = Math.clamp(Math.max(o.minMillis(), o.maxMillis()), 0, 30000);
		long delay = random.nextLong(lo, hi + 1);
		reply = new Reply(kind, answer, now + delay);
		status = "Reply queued: " + answer;
	}

	private String unscramble(String payload) {
		if (!payload.matches("[\\p{L}\\p{N}_ '\u2019-]+")) return null;
		if (dictionary == null) {
			dictionary = new HashMap<>();
			for (String name : names.get()) {
				String value = name.strip();
				if (value.isEmpty() || value.length() > 256) continue;
				List<String> matches = dictionary.computeIfAbsent(signature(value), ignored -> new ArrayList<>());
				if (matches.stream().noneMatch(value::equalsIgnoreCase)) matches.add(value);
			}
		}
		List<String> matches = dictionary.get(signature(payload));
		return matches == null || matches.isEmpty() ? null : matches.get(random.nextInt(matches.size()));
	}

	private static String signature(String value) {
		int[] letters = value.toLowerCase(Locale.ROOT).codePoints().filter(Character::isLetterOrDigit).toArray();
		Arrays.sort(letters);
		return new String(letters, 0, letters.length);
	}

	private String guess(String payload) {
		var range = RANGE.matcher(payload);
		if (!range.matches()) return null;
		try {
			long lo = Long.parseLong(range.group(1).replace(",", ""));
			long hi = Long.parseLong(range.group(2).replace(",", ""));
			if (lo < 1 || hi < lo || hi > Integer.MAX_VALUE) return null;
			return Long.toString(random.nextLong(lo, hi + 1));
		} catch (NumberFormatException ignored) {
			return null;
		}
	}

	private void refresh(long now) {
		if (waiting != null && (now > waitingUntil || !options.apply(waiting).enabled())) {
			waiting = null;
			status = "Waiting for a challenge.";
		}
		if (reply != null && (!options.apply(reply.kind()).enabled() || now > reply.due() + 5000)) {
			reply = null;
			status = "Pending reply cancelled.";
		}
	}

	/** Poll once per tick; returns each answer at most once, never sleeps or sends a command. */
	public String poll(long now) {
		refresh(now);
		if (reply == null || now < reply.due()) return null;
		String answer = reply.answer();
		reply = null;
		status = "Last reply: " + answer;
		return answer;
	}
}
