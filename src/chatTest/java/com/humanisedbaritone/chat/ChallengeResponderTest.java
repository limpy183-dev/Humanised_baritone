package com.humanisedbaritone.chat;

import java.util.EnumMap;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import com.humanisedbaritone.chat.ChallengeResponder.Kind;
import com.humanisedbaritone.chat.ChallengeResponder.Options;

/** Standalone checks executed by gradlew chatResponderTest (also part of check / build). */
public final class ChallengeResponderTest {
	private static int checks;
	private final EnumMap<Kind, Options> options = new EnumMap<>(Kind.class);
	private final ChallengeResponder bot = new ChallengeResponder(options::get,
		() -> List.of("Diamond", "Diamond Sword", "stone", "stone", "Water", "melon", "lemon"), new Random(123));

	private ChallengeResponderTest() {
		for (Kind kind : Kind.values()) options.put(kind, new Options(true, 1000, 1000));
	}

	private static void equal(Object expected, Object actual) {
		checks++;
		if (!Objects.equals(expected, actual)) throw new AssertionError("Expected " + expected + ", got " + actual);
	}

	private void answer(String message, String expected) {
		bot.reset();
		bot.receive(message, 0);
		equal(null, bot.poll(999));
		equal(expected, bot.poll(1000));
		equal(null, bot.poll(1001));
	}

	private void run() {
		answer("\u00a7eUnreverse \u00a7cthe letters to win!\n\u00a7d➤ \u00a7eAbC123xY", "Yx321CbA");
		answer("[ChatGames] Unreverse the letters to win! ➤ Z9a", "a9Z");
		answer("Unreverse the letters: aB cD", "Dc Ba");
		answer("Unscramble the word to win!\n➤ dnomaiD", "Diamond");
		answer("UNSCRAMBLE THE WORD: drows_dnomaid", "Diamond Sword");
		answer("Unscramble the word: notarealitem", null);
		answer("Guess the number between 1 and 1!", "1");
		answer("Guess between 2,000 and 2,000 to win!", "2000");
		answer("Guess a number between 2147483647-2147483647!", "2147483647");
		answer("Guess the number between 100 and 1!", null);
		answer("Guess the number between 0 and 5!", null);
		answer("Guess the number between 1 and 999999999999999999999!", null);
		answer("Guess the number between 1 and 100 apples", null);
		answer("<Player> Unreverse the letters: abc", null);
		answer("Player: Unscramble the word: dnomaiD", null);
		answer("➤ abc", null);
		answer("Unreverse the letters: /op player", null);
		answer("Unreverse the letters: " + "a".repeat(257), null);

		bot.reset();
		bot.receive("Unreverse the letters to win!", 0);
		bot.receive("<Player> normal chat", 50);
		bot.receive("➤ Xy12", 100);
		equal(null, bot.poll(1099));
		equal("21yX", bot.poll(1100));
		bot.receive("Unreverse the letters to win!\n➤ Xy12", 1500);
		equal(null, bot.poll(2500)); // repeated announcement is not another answer
		bot.receive("Unreverse the letters: Xy12", 62000);
		equal("21yX", bot.poll(63000));

		bot.reset();
		bot.receive("Unreverse the letters to win!", 0);
		bot.receive("➤ late", 5001);
		equal(null, bot.poll(6001));
		bot.receive("Unreverse the letters: pending", 7000);
		options.put(Kind.REVERSE, new Options(false, 1000, 1000));
		equal(null, bot.poll(8000));
		bot.receive("Unreverse the letters: disabled", 9000);
		options.put(Kind.REVERSE, new Options(true, 1000, 1000));
		equal(null, bot.poll(10000));

		bot.receive("Unreverse the letters: old", 11000);
		bot.receive("Unscramble the word: dnomaiD", 11500);
		equal(null, bot.poll(12000));
		equal("Diamond", bot.poll(12500));
		bot.receive("Unreverse the letters: reset", 13000);
		bot.reset();
		equal(null, bot.poll(14000));
		bot.receive("Unreverse the letters: expired", 15000);
		equal(null, bot.poll(21001));
		bot.receive("Unreverse the letters: ended", 22000);
		bot.receive("The challenge is over!", 22100);
		equal(null, bot.poll(23000));

		options.put(Kind.NUMBER, new Options(true, 0, 0));
		boolean low = false, high = false;
		for (int i = 0; i < 100; i++) {
			bot.reset();
			bot.receive("Guess the number between 1 and 2!", 0);
			int guess = Integer.parseInt(bot.poll(0));
			equal(true, guess >= 1 && guess <= 2);
			low |= guess == 1;
			high |= guess == 2;
		}
		equal(true, low && high);
		options.put(Kind.REVERSE, new Options(true, 200, 100)); // inverted bounds
		java.util.Set<Long> delays = new java.util.HashSet<>();
		for (int i = 0; i < 100; i++) {
			bot.reset();
			bot.receive("Unreverse the letters: abc", 0);
			equal(null, bot.poll(99));
			long at = 100;
			while (at <= 200 && bot.poll(at) == null) at++;
			equal(true, at <= 200);
			delays.add(at);
		}
		equal(true, delays.size() > 1);
		options.put(Kind.UNSCRAMBLE, new Options(true, 0, 0));
		bot.reset();
		bot.receive("Unscramble the word: nomel", 0);
		equal(true, List.of("melon", "lemon").contains(bot.poll(0)));
	}

	public static void main(String[] args) {
		new ChallengeResponderTest().run();
		System.out.println("Chat responder: " + checks + " checks passed.");
	}
}
