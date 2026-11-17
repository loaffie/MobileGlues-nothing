package com.loaffie.spikecatcher;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Watches the render thread from a second thread. The render thread calls {@link #beat()} at the start of every
 * frame. When a frame has been running for longer than the suspect time, the watcher starts taking stack traces
 * of it, so the report shows what the thread was doing while the frame was slow, not afterwards.
 *
 * Nothing in here touches Minecraft.
 */
public final class SpikeWatcher implements Runnable {

	public interface Sink {
		void write(String text);
	}

	public record Settings(
		long thresholdNs, long suspectNs, long sampleNs, int maxSamples, long graceNs, long pauseNs, int maxReports,
		long summaryNs
	) {
		static Settings fromProperties() {
			long threshold = ms("spikecatcher.thresholdMs", 50);
			long suspect = Math.min(ms("spikecatcher.suspectMs", 25), threshold);
			return new Settings(
				threshold,
				suspect,
				Math.max(1_000_000L, ms("spikecatcher.sampleMs", 4)),
				Long.getLong("spikecatcher.maxSamples", 250L).intValue(),
				ms("spikecatcher.graceSeconds", 20) * 1000,
				ms("spikecatcher.pauseMs", 3000),
				Long.getLong("spikecatcher.maxReports", 500L).intValue(),
				ms("spikecatcher.summarySeconds", 60) * 1000
			);
		}

		private static long ms(String property, long fallback) {
			return Long.getLong(property, fallback) * 1_000_000L;
		}
	}

	private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss.SSS", Locale.ROOT);

	private final Settings settings;
	private final Sink sink;
	private final ThreadMXBean threads = threadBean();
	private final List<GarbageCollectorMXBean> collectors = collectorBeans();

	private volatile long lastBeatNs;
	private volatile long frames;
	private volatile boolean running = true;
	private Thread target;
	private long firstBeatNs;

	// Only the watcher thread touches these.
	private int reports;
	private int dropped;
	private long summaryFramesMark;
	private long summarySpikes;
	private long summaryOver100;
	private double summaryWorstMs;
	private long summaryStartNs;

	public SpikeWatcher(Settings settings, Sink sink) {
		this.settings = settings;
		this.sink = sink;
	}

	/** Called by the render thread at the start of each frame. */
	public void beat() {
		long now = System.nanoTime();
		if (target == null) {
			target = Thread.currentThread();
			firstBeatNs = now;
			lastBeatNs = now;
			Thread watcher = new Thread(this, "spike-catcher");
			watcher.setDaemon(true);
			watcher.start();
			return;
		}
		if (Thread.currentThread() != target) {
			return;
		}
		frames++;
		lastBeatNs = now;
	}

	public void stop() {
		running = false;
	}

	@Override
	public void run() {
		long seenBeat = lastBeatNs;
		summaryFramesMark = frames;
		summaryStartNs = System.nanoTime();
		Spike current = null;

		while (running) {
			try {
				Thread.sleep(settings.sampleNs() / 1_000_000, (int) (settings.sampleNs() % 1_000_000));
			} catch (InterruptedException e) {
				return;
			}
			long now = System.nanoTime();
			long beat = lastBeatNs;

			if (beat != seenBeat) {
				if (current != null) {
					finish(current, beat - seenBeat, now);
					current = null;
				}
				seenBeat = beat;
			}

			if (now - seenBeat >= settings.suspectNs()) {
				if (current == null) {
					current = new Spike(now, cpuTimeNs(), gcSnapshot());
				}
				if (current.samples.size() < settings.maxSamples()) {
					StackTraceElement[] stack = target.getStackTrace();
					current.samples.add(new SpikeReport.Sample(now - seenBeat, stack));
				}
			}

			if (now - summaryStartNs >= settings.summaryNs()) {
				writeSummary(now);
			}
		}
	}

	private void finish(Spike spike, long frameNs, long now) {
		if (frameNs < settings.thresholdNs() || now - firstBeatNs < settings.graceNs()) {
			return;
		}
		double frameMs = frameNs / 1_000_000.0;
		if (frameNs >= settings.pauseNs()) {
			sink.write(String.format(Locale.ROOT,
				"[pause] a frame took %.0f ms (loading, minimised, or a long stall). Not analysed.%n%n", frameMs));
			return;
		}

		summarySpikes++;
		if (frameMs >= 100.0) {
			summaryOver100++;
		}
		summaryWorstMs = Math.max(summaryWorstMs, frameMs);

		if (reports >= settings.maxReports()) {
			dropped++;
			return;
		}
		reports++;

		double cpuMs = spike.cpuStartNs < 0 ? -1 : (cpuTimeNs() - spike.cpuStartNs) / 1_000_000.0;
		double wallMs = (now - spike.wallStartNs) / 1_000_000.0;
		long firstOffset = spike.samples.isEmpty() ? 0 : spike.samples.get(0).offsetNs();
		sink.write(SpikeReport.format(new SpikeReport.Input(
			reports,
			LocalTime.now().format(CLOCK),
			frameMs,
			firstOffset / 1_000_000.0,
			settings.sampleNs() / 1_000_000.0,
			spike.samples,
			cpuMs,
			wallMs,
			gcDelta(spike.gcStart)
		)));
		if (reports == settings.maxReports()) {
			sink.write("[limit] " + reports + " spikes written. Later ones are only counted in the summaries.\n\n");
		}
	}

	private void writeSummary(long now) {
		long frameCount = frames;
		long inWindow = frameCount - summaryFramesMark;
		if (inWindow > 0) {
			sink.write(String.format(Locale.ROOT,
				"[summary] last %.0f s: %d frames, %d over %d ms, %d over 100 ms, worst %.1f ms%s%n%n",
				(now - summaryStartNs) / 1e9, inWindow, summarySpikes, settings.thresholdNs() / 1_000_000,
				summaryOver100, summaryWorstMs, dropped > 0 ? ", " + dropped + " not written" : ""));
		}
		summaryFramesMark = frameCount;
		summaryStartNs = now;
		summarySpikes = 0;
		summaryOver100 = 0;
		summaryWorstMs = 0;
	}

	private long cpuTimeNs() {
		try {
			if (threads != null && target != null) {
				return threads.getThreadCpuTime(target.threadId());
			}
		} catch (RuntimeException ignored) {
		}
		return -1;
	}

	private Map<String, long[]> gcSnapshot() {
		Map<String, long[]> snapshot = new HashMap<>();
		for (GarbageCollectorMXBean bean : collectors) {
			try {
				snapshot.put(bean.getName(), new long[] {bean.getCollectionCount(), bean.getCollectionTime()});
			} catch (RuntimeException ignored) {
			}
		}
		return snapshot;
	}

	private String gcDelta(Map<String, long[]> before) {
		StringBuilder out = new StringBuilder();
		for (Map.Entry<String, long[]> now : gcSnapshot().entrySet()) {
			long[] was = before.get(now.getKey());
			if (was == null) {
				continue;
			}
			long count = now.getValue()[0] - was[0];
			if (count > 0) {
				if (out.length() > 0) {
					out.append(", ");
				}
				out.append(now.getKey()).append(" +").append(count).append(" (").append(now.getValue()[1] - was[1])
					.append(" ms)");
			}
		}
		return out.toString();
	}

	private static ThreadMXBean threadBean() {
		try {
			ThreadMXBean bean = ManagementFactory.getThreadMXBean();
			return bean.isCurrentThreadCpuTimeSupported() && bean.isThreadCpuTimeEnabled() ? bean : null;
		} catch (RuntimeException | LinkageError e) {
			return null;
		}
	}

	private static List<GarbageCollectorMXBean> collectorBeans() {
		try {
			return ManagementFactory.getGarbageCollectorMXBeans();
		} catch (RuntimeException | LinkageError e) {
			return new ArrayList<>();
		}
	}

	private static final class Spike {
		final long wallStartNs;
		final long cpuStartNs;
		final Map<String, long[]> gcStart;
		final List<SpikeReport.Sample> samples = new ArrayList<>();

		Spike(long wallStartNs, long cpuStartNs, Map<String, long[]> gcStart) {
			this.wallStartNs = wallStartNs;
			this.cpuStartNs = cpuStartNs;
			this.gcStart = gcStart;
		}
	}
}
