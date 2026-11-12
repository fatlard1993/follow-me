package justfatlard.follow_me;

import java.util.List;
import justfatlard.pandorical.api.ActionMenuApi;
import justfatlard.pandorical.api.PandoricalApi;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Follow another player hands-off: ask to follow them, or ask them to follow you, and once the
 * other says yes the follower is walked along behind the leader. For a long trip, a trip taken
 * away from the keyboard, or a small child who would otherwise be left at the first hill.
 */
public class Main implements ModInitializer {
	public static final String MOD_ID = "follow-me";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		FollowConfig.load();
		Asks.register();

		PandoricalApi.actionMenus().suggestMenu(MOD_ID + ":follow", "Follow", List.of(
			ActionMenuApi.Button.runs("minecraft:leather_boots", "Follow", "follow {player}"),
			ActionMenuApi.Button.runs("minecraft:lead", "Lead", "follow lead {player}"),
			ActionMenuApi.Button.runs("minecraft:barrier", "Stop", "follow stop")));
		PandoricalApi.commandHelp().describe("/follow <player>",
			"Ask somebody if you can follow them. Once they say yes you are walked along behind them.");
		PandoricalApi.commandHelp().describe("/follow lead <player>",
			"Ask somebody to follow you. They decide.");
		PandoricalApi.commandHelp().describe("/follow stop",
			"Stop following, and stop anybody following you.");

		CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) ->
			FollowCommands.register(dispatcher));

		ServerTickEvents.END_SERVER_TICK.register(server -> {
			Asks.tick(server);
			Tethers.tick(server);
		});
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			Asks.forget(server, handler.getPlayer().getUUID());
			Tethers.forget(server, handler.getPlayer().getUUID());
		});

		FollowConfig.menu();
		LOGGER.info("Follow Me loaded");
	}

	/** On the action bar: following is something happening to you, not a conversation. */
	public static void say(ServerPlayer player, String what) {
		player.sendSystemMessage(Component.literal(what).withStyle(ChatFormatting.LIGHT_PURPLE), true);
	}
}
