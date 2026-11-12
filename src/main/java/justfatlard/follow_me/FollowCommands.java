package justfatlard.follow_me;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@code /follow <player>} asks to follow them, {@code /follow lead <player>} asks them to follow
 * you, {@code /follow stop} ends either. A trailing {@code ride} on either asks for a theme ride. {@code accept} and {@code deny} answer an ask.
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
				.executes(context -> pair(context, (self, other) -> Asks.send(self, other, Asks.Way.LEAD, false)))
				.then(Commands.literal("ride")
					.executes(context -> pair(context, (self, other) -> Asks.send(self, other, Asks.Way.LEAD, true))))))
			.then(Commands.literal("accept").then(Commands.argument("player", EntityArgument.player())
				.executes(context -> pair(context, Asks::accept))))
			.then(Commands.literal("deny").then(Commands.argument("player", EntityArgument.player())
				.executes(context -> pair(context, Asks::deny))))
			.then(Commands.argument("player", EntityArgument.player())
				.executes(context -> pair(context, (self, other) -> Asks.send(self, other, Asks.Way.FOLLOW, false)))
				.then(Commands.literal("ride")
					.executes(context -> pair(context, (self, other) -> Asks.send(self, other, Asks.Way.FOLLOW, true))))));
	}

	private static int pair(CommandContext<CommandSourceStack> context, Pair action) throws CommandSyntaxException {
		action.run(context.getSource().getPlayerOrException(), EntityArgument.getPlayer(context, "player"));
		return 1;
	}
}
