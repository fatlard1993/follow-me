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

	private static int requestSeconds = 60;
	private static int slack = 4;
	private static int leash = 32;

	private static final String DEFAULT_CONFIG = """
			# Follow Me Configuration
			# Delete this file to regenerate with defaults.

			# How long a request to follow, or to be followed, stands before it lapses, in seconds.
			request_seconds=60

			# How far a follower may stray from the one they follow, in blocks, before being pulled
			# back in line. Inside this they walk and look wherever they like.
			slack=4

			# How far a follower can fall behind, in blocks, before they have lost the one they
			# follow and it ends. Also how close they must be to start.
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
			requestSeconds = number(props, "request_seconds", 60, 10, 600);
			slack = number(props, "slack", 4, 2, 16);
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

	public static int requestSeconds() {
		return requestSeconds;
	}

	public static int slack() {
		return slack;
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
		group.number("requestSeconds", "Requests last, seconds", 10, 600, 10, 60)
			.backedBy(player -> requestSeconds, (player, value) -> {
				requestSeconds = value;
				store("request_seconds", String.valueOf(value));
			});
		group.number("slack", "Room to wander, blocks", 2, 16, 1, 4)
			.describe("How far a follower strays before being pulled back in line")
			.backedBy(player -> slack, (player, value) -> {
				slack = value;
				store("slack", String.valueOf(value));
			});
		group.number("leash", "Lost after, blocks", 16, 128, 4, 32)
			.describe("How far behind a follower can fall before it ends; also how close to start")
			.backedBy(player -> leash, (player, value) -> {
				leash = value;
				store("leash", String.valueOf(value));
			});
	}
}
