package justfatlard.follow_me;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import justfatlard.pandorical.api.PandoricalApi;
import net.fabricmc.loader.api.FabricLoader;

/** The file's knobs, and the same knobs on the mod's page of the mod menu for ops. */
public final class FollowConfig {
	private FollowConfig() {}

	private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve(Main.MOD_ID + ".properties");

	private static int leash = 32;

	private static final String DEFAULT_CONFIG = """
			# Follow Me Configuration
			# Delete this file to regenerate with defaults.

			# How close, in blocks, a follower must be to the one they follow for it to start.
			# Once started nothing ends it but either of them stopping it or leaving.
			leash=32
			""";

	public static void load() {
		try {
			if (!Files.exists(CONFIG_PATH)) {
				Files.createDirectories(CONFIG_PATH.getParent());
				Files.writeString(CONFIG_PATH, DEFAULT_CONFIG);
			}
			Properties props = new Properties();
			try (Reader reader = Files.newBufferedReader(CONFIG_PATH)) {
				props.load(reader);
			}
			leash = number(props, "leash", 32, 16, 128);
		} catch (IOException e) {
			Main.LOGGER.error("[{}] Could not read config, using defaults: {}", Main.MOD_ID, e.toString());
		}
	}

	private static int number(Properties props, String key, int fallback, int min, int max) {
		try {
			return Math.clamp(Integer.parseInt(props.getProperty(key, String.valueOf(fallback)).trim()), min, max);
		} catch (NumberFormatException e) {
			Main.LOGGER.warn("[{}] {} is not a number, using {}", Main.MOD_ID, key, fallback);
			return fallback;
		}
	}

	/** How long a request stands: one setting for every mod's requests, on Pandorical's page of the mod menu. */
	public static int requestSeconds(net.minecraft.server.MinecraftServer server) {
		try {
			return PandoricalApi.notices().requestSeconds(server);
		} catch (LinkageError older) {
			// A Pandorical from before the shared setting: vanilla-length requests, as before it
			return 60;
		}
	}

	public static int leash() {
		return leash;
	}

	/** One value back into the file, keeping its comments and order. */
	private static void store(String key, String value) {
		try {
			List<String> lines = Files.exists(CONFIG_PATH) ? new ArrayList<>(Files.readAllLines(CONFIG_PATH)) : new ArrayList<>();
			boolean found = false;
			for (int i = 0; i < lines.size(); i++) {
				String line = lines.get(i).trim();
				if (line.startsWith(key + "=") || line.startsWith(key + " =")) {
					lines.set(i, key + "=" + value);
					found = true;
				}
			}
			if (!found) lines.add(key + "=" + value);
			Files.createDirectories(CONFIG_PATH.getParent());
			Files.write(CONFIG_PATH, lines);
		} catch (IOException e) {
			Main.LOGGER.error("[{}] Could not write config: {}", Main.MOD_ID, e.toString());
		}
	}

	public static void menu() {
		var group = PandoricalApi.settings().serverGroup(Main.MOD_ID, "Follow Me");
		group.number("leash", "Start within, blocks", 16, 128, 4, 32)
			.describe("How close a follower must be to the one they follow for it to start")
			.backedBy(player -> leash, (player, value) -> {
				leash = value;
				store("leash", String.valueOf(value));
			});
	}
}
