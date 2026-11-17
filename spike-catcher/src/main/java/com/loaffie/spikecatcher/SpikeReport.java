package com.loaffie.spikecatcher;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the stack samples taken during one slow frame into text. No Minecraft classes in here, so it can be
 * tested on its own.
 */
final class SpikeReport {

	record Sample(long offsetNs, StackTraceElement[] stack) {
	}

	record Input(
		int number,
		String clock,
		double frameMs,
		double firstSampleMs,
		double sampleIntervalMs,
		List<Sample> samples,
		double cpuMs,
		double wallMs,
		String gc
	) {
	}

	private enum State {
		CODE("running"), GL("in a GL call"), WAITING("waiting");

		final String word;

		State(String word) {
			this.word = word;
		}
	}

	private static final String[][] OWNERS = {
		{"net.caffeinemc.mods.sodium", "sodium"},
		{"me.flashyreese.mods.sodiumextra", "sodium-extra"},
		{"net.caffeinemc.mods.lithium", "lithium"},
		{"me.jellysquid.mods.lithium", "lithium"},
		{"dev.tr7zw.entityculling", "entityculling"},
		{"ca.fxco.moreculling", "moreculling"},
		{"dev.zelo.renderscale", "renderscale"},
		{"com.loaffie.", "loaffie"},
		{"net.fabricmc.", "fabric"},
		{"net.minecraft.", "minecraft"},
		{"com.mojang.", "minecraft"},
		{"org.lwjgl.", "lwjgl"},
		{"org.spongepowered.", "mixin"},
		{"io.netty.", "netty"},
		{"java.", "java"},
		{"javax.", "java"},
		{"jdk.", "java"},
		{"sun.", "java"},
		{"com.sun.", "java"},
	};

	// Minecraft's own class names are obfuscated at runtime. These few are stable and worth naming.
	private static final Map<String, String> CLASS_HINTS = Map.of(
		"net.minecraft.class_310", "MinecraftClient",
		"net.minecraft.class_757", "GameRenderer",
		"net.minecraft.class_761", "WorldRenderer",
		"net.minecraft.class_329", "InGameHud",
		"net.minecraft.class_1041", "Window",
		"net.minecraft.class_898", "EntityRenderDispatcher",
		"net.minecraft.class_702", "ParticleManager"
	);

	// Mixin names the methods it adds like handler$abc000$<mod id>$name.
	private static final Pattern INJECTED = Pattern.compile(
		"^(?:handler|redirect|wrapOperation|wrapWithCondition|wrapMethod|modifyArg|modifyArgs|modifyConstant"
			+ "|modifyVariable|modifyExpressionValue|modifyReturnValue)\\$[A-Za-z0-9]+\\$([^$]+)\\$");

	private static final int MAX_TIMELINE_ROWS = 12;
	private static final int MAX_TOP_ROWS = 6;
	private static final int STACK_LINES = 26;

	private SpikeReport() {
	}

	static String ownerOf(StackTraceElement e) {
		Matcher injected = INJECTED.matcher(e.getMethodName());
		if (injected.find()) {
			return injected.group(1);
		}
		String cls = e.getClassName();
		for (String[] row : OWNERS) {
			if (cls.startsWith(row[0])) {
				return row[1];
			}
		}
		String[] parts = cls.split("\\.");
		return String.join(".", java.util.Arrays.copyOf(parts, Math.min(3, parts.length)));
	}

	static String label(StackTraceElement e) {
		String text = e.getClassName() + "." + e.getMethodName();
		String hint = CLASS_HINTS.get(e.getClassName());
		return hint == null ? text : text + " <" + hint + ">";
	}

	private static boolean isBackground(String owner) {
		return owner.equals("java") || owner.equals("lwjgl") || owner.equals("mixin");
	}

	// The first frame from the top that belongs to Minecraft or a mod: who asked for the work.
	private static StackTraceElement whoAsked(StackTraceElement[] stack) {
		for (StackTraceElement e : stack) {
			if (!isBackground(ownerOf(e))) {
				return e;
			}
		}
		return stack[0];
	}

	private static State stateOf(StackTraceElement leaf) {
		String m = leaf.getMethodName();
		String c = leaf.getClassName();
		switch (m) {
			case "park", "parkNanos", "parkUntil", "wait", "wait0", "sleep", "sleep0", "join", "yield", "yield0",
				"onSpinWait" -> {
				return State.WAITING;
			}
			default -> {
			}
		}
		if (c.startsWith("org.lwjgl.")) {
			return State.GL;
		}
		return State.CODE;
	}

	static String format(Input in) {
		List<Sample> samples = in.samples().stream().filter(s -> s.stack().length > 0).toList();
		StringBuilder out = new StringBuilder(2048);
		out.append(String.format(Locale.ROOT, "=== spike %d  %s  frame took %.1f ms ===%n", in.number(), in.clock(),
			in.frameMs()));

		if (samples.isEmpty()) {
			out.append("no stack samples (the frame ended before the first one)\n\n");
			return out.toString();
		}

		out.append(String.format(Locale.ROOT, "%d samples, from %.0f ms into the frame to the end%n", samples.size(),
			in.firstSampleMs()));
		if (in.cpuMs() >= 0 && in.wallMs() > 0) {
			double pct = Math.min(100.0, in.cpuMs() / in.wallMs() * 100.0);
			out.append(String.format(Locale.ROOT,
				"main thread: %.0f ms of CPU in %.0f ms (%.0f%%) %s%n", in.cpuMs(), in.wallMs(), pct,
				pct >= 80 ? "= busy the whole time"
					: pct >= 40 ? "= partly working, partly waiting or switched out"
					: "= mostly waiting or switched out by the OS"));
		}
		out.append("garbage collection during it: ").append(in.gc() == null || in.gc().isEmpty() ? "none"
			: in.gc()).append('\n');

		// What kind of thing the thread was doing.
		int[] states = new int[State.values().length];
		for (Sample s : samples) {
			states[stateOf(s.stack()[0]).ordinal()]++;
		}
		out.append("it was: ");
		boolean first = true;
		for (State st : State.values()) {
			if (states[st.ordinal()] == 0) {
				continue;
			}
			if (!first) {
				out.append(", ");
			}
			out.append(percent(states[st.ordinal()], samples.size())).append(' ').append(st.word);
			first = false;
		}
		out.append('\n');

		// Who asked for the work.
		Map<String, Integer> byOwner = new LinkedHashMap<>();
		Map<String, Integer> byWho = new HashMap<>();
		Map<String, Integer> byLeaf = new HashMap<>();
		for (Sample s : samples) {
			StackTraceElement who = whoAsked(s.stack());
			byOwner.merge(ownerOf(who), 1, Integer::sum);
			byWho.merge(label(who) + "  [" + ownerOf(who) + "]", 1, Integer::sum);
			byLeaf.merge(label(s.stack()[0]), 1, Integer::sum);
		}
		out.append("code that asked for the work, by owner: ");
		out.append(joinTop(byOwner, samples.size(), 6, false)).append('\n');

		out.append("code that asked for the work:\n");
		appendTop(out, byWho, samples.size());
		out.append("what the thread was inside (innermost frame):\n");
		appendTop(out, byLeaf, samples.size());

		appendTimeline(out, in, samples);
		appendStack(out, samples);
		out.append('\n');
		return out.toString();
	}

	private static void appendTimeline(StringBuilder out, Input in, List<Sample> samples) {
		record Run(double startMs, double endMs, StackTraceElement who, Map<String, Integer> leaves, State state) {
		}

		List<Run> runs = new ArrayList<>();
		for (Sample s : samples) {
			StackTraceElement who = whoAsked(s.stack());
			double at = s.offsetNs() / 1_000_000.0;
			Run last = runs.isEmpty() ? null : runs.get(runs.size() - 1);
			if (last != null && label(last.who()).equals(label(who))) {
				runs.set(runs.size() - 1, new Run(last.startMs(), at + in.sampleIntervalMs(), last.who(), last.leaves(),
					last.state()));
				last.leaves().merge(label(s.stack()[0]), 1, Integer::sum);
			} else {
				Map<String, Integer> leaves = new HashMap<>();
				leaves.put(label(s.stack()[0]), 1);
				runs.add(new Run(at, at + in.sampleIntervalMs(), who, leaves, stateOf(s.stack()[0])));
			}
		}

		out.append("timeline, ms from the start of the frame:\n");
		int shown = Math.min(runs.size(), MAX_TIMELINE_ROWS);
		for (int i = 0; i < shown; i++) {
			Run r = runs.get(i);
			String leaf = r.leaves().entrySet().stream().max(Map.Entry.comparingByValue()).get().getKey();
			String who = label(r.who());
			out.append(String.format(Locale.ROOT, "  %5.0f-%5.0f  %s  [%s]", r.startMs(), r.endMs(), who,
				ownerOf(r.who())));
			if (!leaf.equals(who)) {
				out.append("  ->  ").append(leaf).append("  (").append(r.state().word).append(')');
			}
			out.append('\n');
		}
		if (runs.size() > shown) {
			out.append("  ... and ").append(runs.size() - shown).append(" more changes\n");
		}
	}

	// One full stack, from the most common "who asked" frame, so the call path can be read top to bottom.
	private static void appendStack(StringBuilder out, List<Sample> samples) {
		Map<String, Integer> counts = new HashMap<>();
		for (Sample s : samples) {
			counts.merge(label(whoAsked(s.stack())), 1, Integer::sum);
		}
		String common = counts.entrySet().stream().max(Map.Entry.comparingByValue()).get().getKey();
		Sample pick = null;
		for (Sample s : samples) {
			if (label(whoAsked(s.stack())).equals(common)) {
				pick = s;
			}
		}
		out.append("one stack, innermost first:\n");
		StackTraceElement[] st = pick.stack();
		int n = Math.min(st.length, STACK_LINES);
		for (int i = 0; i < n; i++) {
			out.append("    ").append(label(st[i])).append("  [").append(ownerOf(st[i])).append("]\n");
		}
		if (st.length > n) {
			out.append("    ... ").append(st.length - n).append(" more frames\n");
		}
	}

	private static void appendTop(StringBuilder out, Map<String, Integer> counts, int total) {
		counts.entrySet().stream()
			.sorted((a, b) -> b.getValue() - a.getValue())
			.limit(MAX_TOP_ROWS)
			.forEach(e -> out.append("  ").append(percent(e.getValue(), total)).append("  ").append(e.getKey())
				.append('\n'));
	}

	private static String joinTop(Map<String, Integer> counts, int total, int limit, boolean newline) {
		StringBuilder sb = new StringBuilder();
		counts.entrySet().stream()
			.sorted((a, b) -> b.getValue() - a.getValue())
			.limit(limit)
			.forEach(e -> {
				if (sb.length() > 0) {
					sb.append(newline ? "\n" : ", ");
				}
				sb.append(e.getKey()).append(' ').append(percent(e.getValue(), total));
			});
		return sb.toString();
	}

	private static String percent(int part, int total) {
		return String.format(Locale.ROOT, "%3.0f%%", part * 100.0 / total);
	}
}
