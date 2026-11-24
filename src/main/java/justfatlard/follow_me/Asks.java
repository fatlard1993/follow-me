package justfatlard.follow_me;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import justfatlard.pandorical.api.Capabilities;
import justfatlard.pandorical.api.NoticeApi;
import justfatlard.pandorical.api.PandoricalApi;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Asking to follow, which goes both ways: "let me follow you", and "follow me". Either way the one
 * who did not ask is the one who answers, in the notice tray or in chat.
 *
 * <p>Nobody is carried anywhere, or has somebody trailing them, without having said yes to it: a
 * follower agrees to be steered, a leader agrees to be followed. One ask at a time from each
 * player; asking again takes back the first.
 */
public final class Asks {
	private Asks() {}

	public enum Way {
		/** The asker follows the one asked: "let me follow you". */
		FOLLOW,
		/** The one asked follows the asker: "follow me". */
		LEAD
	}

	record Ask(UUID asker, UUID asked, long until, Way way) {}

	/** By asker. */
	private static final Map<UUID, Ask> asks = new ConcurrentHashMap<>();

	/** What the tray files these under; the id of each one is the asker. */
	private static final String NOTICE_KIND = "follow-me:ask";

	public static void register() {
		PandoricalApi.notices().onChoice(NOTICE_KIND, (asked, noticeId, choiceId) -> {
			ServerPlayer asker = Tethers.player(asked.level().getServer(), UUID.fromString(noticeId));
			if (asker == null) {
				Main.say(asked, "They are not on any more");
				return;
			}
			if ("accept".equals(choiceId)) accept(asked, asker); else deny(asked, asker);
		});
	}

	private static void withdraw(ServerPlayer asked, UUID asker) {
		if (asked != null) PandoricalApi.notices().withdraw(asked, NOTICE_KIND, asker.toString());
	}

	public static void send(ServerPlayer asker, ServerPlayer asked, Way way) {
		if (asker == asked) return;
		ServerPlayer follower = way == Way.FOLLOW ? asker : asked;
		ServerPlayer leader = way == Way.FOLLOW ? asked : asker;
		String why = Tethers.cannot(follower, leader);
		if (why != null) {
			Main.say(asker, why);
			return;
		}

		String who = Tethers.name(asker);
		String them = Tethers.name(asked);
		long until = asker.level().getGameTime() + 20L * FollowConfig.requestSeconds(asker.level().getServer());
		Ask previous = asks.put(asker.getUUID(), new Ask(asker.getUUID(), asked.getUUID(), until, way));
		if (previous != null && !previous.asked().equals(asked.getUUID())) {
			withdraw(Tethers.player(asker.level().getServer(), previous.asked()), asker.getUUID());
		}

		Main.say(asker, way == Way.FOLLOW
			? "Asked " + them + " if you can ride along behind them"
			: "Asked " + them + " to ride along behind you");

		String key = way == Way.FOLLOW ? "follow-me.ask" : "follow-me.ask-lead";
		String fallback = way == Way.FOLLOW
			? "%s asks to ride along behind you. "
			: "%s offers you a ride along behind them. You will not steer, and stay on until one of you stops it. ";
		Component asking = Component.translatableWithFallback(key, fallback, who);

		// The tray where it can be drawn, chat where it cannot.
		if (PandoricalApi.hasCapability(asked, Capabilities.SCREENS)) {
			PandoricalApi.notices().offer(asked, new NoticeApi.Notice(
				asker.getUUID().toString(), NOTICE_KIND,
				way == Way.FOLLOW ? "minecraft:lead" : "minecraft:saddle",
				asking.getString(),
				List.of(new NoticeApi.Choice("accept", "minecraft:lime_dye",
						way == Way.FOLLOW ? "Lead them" : "Ride along"),
					new NoticeApi.Choice("deny", "minecraft:barrier", "No thanks")),
				FollowConfig.requestSeconds(asker.level().getServer())));
		} else {
			asked.sendSystemMessage(asking.copy()
				.withStyle(ChatFormatting.LIGHT_PURPLE)
				.append(button("[Yes]", ChatFormatting.GREEN, "/follow accept " + who,
					way == Way.FOLLOW ? "Let " + who + " follow you" : "Follow " + who))
				.append(Component.literal("  "))
				.append(button("[No]", ChatFormatting.RED, "/follow deny " + who, "No thanks")));
		}
	}

	private static Component button(String label, ChatFormatting color, String command, String hover) {
		return Component.literal(label).withStyle(style -> style
			.withColor(color).withBold(true)
			.withClickEvent(new ClickEvent.RunCommand(command))
			.withHoverEvent(new HoverEvent.ShowText(Component.literal(hover))));
	}

	public static void accept(ServerPlayer asked, ServerPlayer asker) {
		Ask ask = asks.get(asker.getUUID());
		if (ask == null || !ask.asked().equals(asked.getUUID())) {
			Main.say(asked, Tethers.name(asker) + " is not asking you anything");
			return;
		}
		asks.remove(asker.getUUID());
		withdraw(asked, asker.getUUID());
		if (ask.way() == Way.FOLLOW) Tethers.start(asker, asked, true);
		else Tethers.start(asked, asker, true);
	}

	public static void deny(ServerPlayer asked, ServerPlayer asker) {
		Ask ask = asks.get(asker.getUUID());
		if (ask == null || !ask.asked().equals(asked.getUUID())) return;
		asks.remove(asker.getUUID());
		withdraw(asked, asker.getUUID());
		Main.say(asker, Tethers.name(asked) + " said no");
		Main.say(asked, "Told " + Tethers.name(asker) + " no");
	}

	/** Once a tick: asks past their time lapse, and the asker hears so. */
	public static void tick(MinecraftServer server) {
		long now = server.overworld().getGameTime();
		for (Ask ask : List.copyOf(asks.values())) {
			if (now < ask.until()) continue;
			asks.remove(ask.asker(), ask);
			ServerPlayer asker = Tethers.player(server, ask.asker());
			ServerPlayer asked = Tethers.player(server, ask.asked());
			if (asker != null) {
				Main.say(asker, "Your request" + (asked != null ? " to " + Tethers.name(asked) : "") + " lapsed");
			}
			withdraw(asked, ask.asker());
		}
	}

	/** A player who left: nothing they asked stands, and nobody waits on them to answer. */
	public static void forget(MinecraftServer server, UUID player) {
		Ask theirs = asks.remove(player);
		if (theirs != null) withdraw(Tethers.player(server, theirs.asked()), player);
		asks.values().removeIf(ask -> ask.asked().equals(player));
	}
}
