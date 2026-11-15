package justfatlard.follow_me.integration;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * A leader at the wheel of a Big Boats ship, seen from here: how far her hull reaches from the
 * helm, so a follower keeps clear of her stern, and what she is built of, so the sloop sailing
 * behind her is built of the same.
 *
 * <p>Guarded as Big Boats guards its own crossovers: every Big Boats type is named behind the
 * flag, and a Big Boats that has changed under us is warned about once and then sailed without.
 */
public final class BigBoatsShips {
	private BigBoatsShips() {}

	private static final boolean PRESENT = FabricLoader.getInstance().isModLoaded("big-boats-justfatlard");

	/** {@code reach}: the furthest any of her blocks lies from the helm, across the water. */
	public record Ship(double reach, Block planks, Block sail) {}

	private record Seen(int blocks, Ship ship) {}

	/** By ship: read again only when her blocks change, not every tick of every follower. */
	private static final Map<UUID, Seen> seen = new ConcurrentHashMap<>();

	/** The ship this player is at the wheel of, or null when they are not steering one. */
	public static Ship piloting(ServerPlayer leader) {
		if (!PRESENT) return null;
		try {
			if (!(leader.getVehicle() instanceof justfatlard.big_boats.ship.MultiBlockShipEntity ship)
				|| ship.getControllingPassenger() != leader) return null;
			var blocks = ship.getBlocks();
			Seen known = seen.get(ship.getUUID());
			if (known != null && known.blocks() == blocks.size()) return known.ship();

			double reach = 0;
			Map<Block, Integer> planks = new HashMap<>();
			Map<Block, Integer> wool = new HashMap<>();
			for (var block : blocks) {
				var at = block.relativePos();
				reach = Math.max(reach, Math.hypot(at.x() + 0.5, at.z() + 0.5));
				var state = block.blockState();
				if (state.is(BlockTags.PLANKS)) planks.merge(state.getBlock(), 1, Integer::sum);
				else if (state.is(BlockTags.WOOL)) wool.merge(state.getBlock(), 1, Integer::sum);
			}
			Ship made = new Ship(reach, most(planks, Blocks.OAK_PLANKS), most(wool, Blocks.WOOL.white()));
			if (seen.size() > 64) seen.clear();
			seen.put(ship.getUUID(), new Seen(blocks.size(), made));
			return made;
		} catch (LinkageError e) {
			warnOnce(e);
			return null;
		}
	}

	private static Block most(Map<Block, Integer> counts, Block otherwise) {
		return counts.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(otherwise);
	}

	private static boolean warned = false;

	private static void warnOnce(LinkageError e) {
		if (warned) return;
		warned = true;
		org.slf4j.LoggerFactory.getLogger(BigBoatsShips.class)
			.warn("big-boats has changed under us; followers behind a ship will ride as they would at sea", e);
	}
}
