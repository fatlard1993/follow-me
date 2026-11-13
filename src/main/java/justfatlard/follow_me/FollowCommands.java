package justfatlard.follow_me;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.Collection;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@code /follow <player>} asks to ride along behind them, {@code /follow lead <player>} offers
 * them a ride behind you, {@code /follow stop} ends either. {@code accept} and {@code deny}
 * answer an ask. {@code assign}, for ops, puts players behind a leader without asking.
 */
public final class FollowCommands {
	private FollowCommands() {}

	private interface Pair {
		void run(ServerPlayer self, ServerPlayer other);
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("follow")
			.then(Commands.literal("stop").executes(context -> {
				Tethers.stop(context.getSource().getPlayerOrException());
				return 1;
			}))
			.then(Commands.literal("lead").then(Commands.argument("player", EntityArgument.player())
				.executes(context -> pair(context, (self, other) -> Asks.send(self, other, Asks.Way.LEAD)))))
			.then(Commands.literal("accept").then(Commands.argument("player", EntityArgument.player())
				.executes(context -> pair(context, Asks::accept))))
			.then(Commands.literal("deny").then(Commands.argument("player", EntityArgument.player())
				.executes(context -> pair(context, Asks::deny))))
			.then(Commands.literal("assign")
				.requires(source -> Commands.LEVEL_GAMEMASTERS.check(source.permissions()))
				.then(Commands.argument("followers", EntityArgument.players())
					.then(Commands.argument("leader", EntityArgument.player())
						.executes(FollowCommands::assign))))
			.then(Commands.argument("player", EntityArgument.player())
				.executes(context -> pair(context, (self, other) -> Asks.send(self, other, Asks.Way.FOLLOW)))));
	}

	private static int pair(CommandContext<CommandSourceStack> context, Pair action) throws CommandSyntaxException {
		action.run(context.getSource().getPlayerOrException(), EntityArgument.getPlayer(context, "player"));
		return 1;
	}

	/** Each one put on a ride behind the leader, wherever they are, in the order given. */
	private static int assign(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		Collection<ServerPlayer> followers = EntityArgument.getPlayers(context, "followers");
		ServerPlayer leader = EntityArgument.getPlayer(context, "leader");
		int started = 0;
		for (ServerPlayer follower : followers) {
			if (follower == leader) continue;
			if (Tethers.start(follower, leader, false)) started++;
		}
		int count = started;
		context.getSource().sendSuccess(() -> Component.literal(
			count + (count == 1 ? " player is" : " players are") + " riding along behind " + Tethers.name(leader)), true);
		return count;
	}
}
