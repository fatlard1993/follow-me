package justfatlard.follow_me;

import java.util.List;
import justfatlard.pandorical.api.ActionMenuApi;
import justfatlard.pandorical.api.PandoricalApi;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.EnderEyeItem;
import net.minecraft.world.item.EnderpearlItem;
import net.minecraft.world.item.FishingRodItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ProjectileItem;
import net.minecraft.world.item.ProjectileWeaponItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Follow another player hands-off: ask to follow them, or ask them to follow you, and once the
 * other says yes the follower rides along behind the leader. For a long trip, a trip taken
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
			ActionMenuApi.Button.runs("minecraft:saddle", "Ride along", "follow {player}"),
			ActionMenuApi.Button.runs("minecraft:lead", "Give a ride", "follow lead {player}"),
			ActionMenuApi.Button.runs("minecraft:compass", "Wander", "follow nobody"),
			ActionMenuApi.Button.runs("minecraft:barrier", "Stop", "follow stop")));
		PandoricalApi.commandHelp().describe("/follow <player>",
			"Ask to ride along behind somebody on a creature that suits the land: hands off, and nothing can hurt you.");
		PandoricalApi.commandHelp().describe("/follow lead <player>",
			"Offer somebody a ride along behind you. They decide.");
		PandoricalApi.commandHelp().describe("/follow nobody",
			"Ride behind nobody: steer it yourself, jump to fly, sneak to land. Still a creature that suits the land, and still nothing can hurt you.");
		PandoricalApi.commandHelp().describe("/follow stop",
			"Get off, and stop anybody riding along behind you.");
		PandoricalApi.commandHelp().describe("/follow assign <players> <leader>",
			"Ops: put players behind a leader without asking, wherever they are. nobody as the leader gives them a ride of their own.");

		CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) ->
			FollowCommands.register(dispatcher));

		ServerTickEvents.END_SERVER_TICK.register(server -> {
			Asks.tick(server);
			Tethers.tick(server);
		});
		// Riding along is guaranteed safe: neither the rider nor what carries them can be hurt.
		// And it runs both ways: a rider hurts nothing, not by hand and not by anything they loosed.
		ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) ->
			!(entity instanceof ServerPlayer player && Tethers.carried(player)) && !Rides.isMount(entity)
				&& !(source.getEntity() instanceof ServerPlayer attacker && Tethers.carried(attacker)));
		// Hands off the world while riding: no swinging at things, no breaking blocks.
		AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) ->
			player instanceof ServerPlayer rider && Tethers.carried(rider) ? InteractionResult.FAIL : InteractionResult.PASS);
		AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) ->
			player instanceof ServerPlayer rider && Tethers.carried(rider) ? InteractionResult.FAIL : InteractionResult.PASS);
		UseItemCallback.EVENT.register((player, level, hand) -> {
			if (!(player instanceof ServerPlayer rider && Tethers.carried(rider) && launches(player.getItemInHand(hand).getItem())))
				return InteractionResult.PASS;
			// The client already spent the snowball or drew the bow on its own say-so: put it back.
			rider.containerMenu.sendAllDataToRemote();
			say(rider, "Not while riding along");
			return InteractionResult.FAIL;
		});
		PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, blockEntity) ->
			!(player instanceof ServerPlayer rider && Tethers.carried(rider)));
		ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> Rides.onLoad(entity));
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			Asks.forget(server, handler.getPlayer().getUUID());
			Tethers.forget(server, handler.getPlayer().getUUID());
		});

		FollowConfig.menu();
		LOGGER.info("Follow Me loaded");
	}

	/** Throws or shoots something out into the world. */
	private static boolean launches(Item item) {
		return item instanceof ProjectileWeaponItem || item instanceof ProjectileItem || item instanceof EnderpearlItem
			|| item instanceof EnderEyeItem || item instanceof FishingRodItem;
	}

	/** On the action bar: following is something happening to you, not a conversation. */
	public static void say(ServerPlayer player, String what) {
		player.sendSystemMessage(Component.literal(what).withStyle(ChatFormatting.LIGHT_PURPLE), true);
	}
}
