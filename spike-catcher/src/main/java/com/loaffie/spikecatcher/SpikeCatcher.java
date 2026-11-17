package com.loaffie.spikecatcher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.loader.api.FabricLoader;

public final class SpikeCatcher implements ClientModInitializer {

	private static final long MAX_FILE_BYTES = 4L * 1024 * 1024;

	private static SpikeWatcher watcher;
	private static volatile boolean mixinWorks;

	@Override
	public void onInitializeClient() {
		Path gameDir = FabricLoader.getInstance().getGameDir();
		Path primary = outputPath(gameDir);
		FileSink sink = new FileSink(primary, gameDir.resolve("spike-catcher.txt"));
		SpikeWatcher.Settings settings = SpikeWatcher.Settings.fromProperties();
		sink.write(header(settings, sink.activePath()));
		watcher = new SpikeWatcher(settings, sink);

		// Only used when the frame hook above did not apply; see frameFromHud.
		try {
			HudRenderCallback.EVENT.register((context, tickCounter) -> frameFromHud());
		} catch (Throwable ignored) {
		}
	}

	/** Called at the top of every frame by the mixin. */
	public static void frameFromMixin() {
		mixinWorks = true;
		SpikeWatcher w = watcher;
		if (w != null) {
			w.beat();
		}
	}

	// The HUD does not draw on the title screen or with F1 pressed, so it is a worse clock than the mixin.
	private static void frameFromHud() {
		SpikeWatcher w = watcher;
		if (w != null && !mixinWorks) {
			w.beat();
		}
	}

	private static Path outputPath(Path gameDir) {
		String forced = System.getProperty("spikecatcher.file");
		if (forced != null && !forced.isBlank()) {
			return Path.of(forced);
		}
		// MobileGlues keeps its log here too, and it is easy to reach on the phone.
		Path mg = Path.of("/sdcard/MG");
		if (Files.isDirectory(mg) && Files.isWritable(mg)) {
			return mg.resolve("spikes.txt");
		}
		return gameDir.resolve("spike-catcher.txt");
	}

	private static String header(SpikeWatcher.Settings s, Path file) {
		String minecraft = FabricLoader.getInstance().getModContainer("minecraft")
			.map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?");
		String version = FabricLoader.getInstance().getModContainer("spike_catcher")
			.map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?");
		return String.format(Locale.ROOT,
			"---- session %s | Minecraft %s | Spike Catcher %s | Java %s%n"
				+ "---- frames over %d ms are written here; sampling starts %d ms into a frame, every %d ms%n"
				+ "---- Minecraft's class names are the obfuscated ones (class_757 and so on)%n%n",
			LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT)),
			minecraft, version, System.getProperty("java.version"), s.thresholdNs() / 1_000_000,
			s.suspectNs() / 1_000_000, s.sampleNs() / 1_000_000);
	}

	/** Appends to the file, and falls back to a second path if the first cannot be written. */
	private static final class FileSink implements SpikeWatcher.Sink {
		private Path path;
		private final Path fallback;

		FileSink(Path path, Path fallback) {
			this.path = path;
			this.fallback = fallback;
			trimIfLarge(path);
		}

		Path activePath() {
			return path;
		}

		@Override
		public synchronized void write(String text) {
			if (append(path, text)) {
				return;
			}
			if (!path.equals(fallback)) {
				path = fallback;
				append(path, text);
			}
		}

		private static boolean append(Path p, String text) {
			try {
				Files.writeString(p, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
					StandardOpenOption.APPEND);
				return true;
			} catch (IOException e) {
				return false;
			}
		}

		private static void trimIfLarge(Path p) {
			try {
				if (Files.exists(p) && Files.size(p) > MAX_FILE_BYTES) {
					Files.move(p, p.resolveSibling(p.getFileName() + ".old"),
						java.nio.file.StandardCopyOption.REPLACE_EXISTING);
				}
			} catch (IOException ignored) {
			}
		}
	}
}
