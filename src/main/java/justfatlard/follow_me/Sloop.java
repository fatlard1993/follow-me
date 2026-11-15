package justfatlard.follow_me;

import java.util.ArrayList;
import java.util.List;
import justfatlard.follow_me.integration.BigBoatsShips;
import justfatlard.pandorical.api.BlockEntry;
import justfatlard.pandorical.api.PandoricalApi;
import justfatlard.pandorical.api.RelPos;
import justfatlard.pandorical.api.StructurePose;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * The little ship a follower sails behind a leader at the wheel of a Big Boats ship: seven blocks
 * stem to stern, a mast and a fore-and-aft sail, built of the leader's own planks and sail cloth.
 *
 * <p>Nothing of it is in the world. It is a Pandorical structure carried on an invisible creature
 * the follower sits on, so it places no block, docks nowhere, and is gone the moment the ride is.
 *
 * <p>Laid out facing +z, three blocks across, the floor in the water and the follower sat on it in
 * the stern, a block in from the transom. A structure turns about its origin as
 * {@code origin + R(yaw) * local}, which takes +z to the way a creature with the same yaw faces.
 */
final class Sloop {
	private Sloop() {}

	/** Where the follower sits, on the floor in the stern. */
	private static final double SEAT_X = 0.5;
	private static final double SEAT_Z = 1.5;
	/** From the follower forward to the stem: how much sloop there is ahead of them to keep clear. */
	static final double BOW = 5.5;
	/** How far down from the trail to look for the sea, the trail being on the leader's deck. */
	private static final int SEA_DEPTH = 24;

	static String id(Mob mount) {
		return Main.MOD_ID + ":sloop/" + mount.getUUID();
	}

	/** The top of the water under this point, as a block height, or null with no sea under it. */
	static Integer sea(ServerLevel level, Vec3 at) {
		BlockPos pos = BlockPos.containing(at);
		for (int down = 0; down <= SEA_DEPTH; down++, pos = pos.below()) {
			if (!level.isLoaded(pos)) return null;
			if (level.getFluidState(pos).is(FluidTags.WATER)) return pos.getY();
			if (!level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()) return null;
		}
		return null;
	}

	static void spawn(Mob mount, BigBoatsShips.Ship ship, StructurePose pose) {
		PandoricalApi.structures().spawn(mount, id(mount), blocks(ship), pose);
	}

	static void despawn(Mob mount) {
		PandoricalApi.structures().despawn(id(mount));
	}

	static void move(Mob mount, StructurePose pose) {
		PandoricalApi.structures().updatePose(id(mount), pose);
	}

	/** Posed so the seat is where the follower is: {@code seat} is their place, on the floor's top. */
	static StructurePose pose(Vec3 seat, float yaw) {
		float sin = Mth.sin(yaw * Mth.DEG_TO_RAD);
		float cos = Mth.cos(yaw * Mth.DEG_TO_RAD);
		double x = seat.x - (SEAT_X * cos - SEAT_Z * sin);
		double z = seat.z - (SEAT_X * sin + SEAT_Z * cos);
		return new StructurePose(x, seat.y - 1, z, yaw);
	}

	/** {@code ship} null, as when a sloop is remade just after the leader let go of the wheel: oak and white. */
	private static List<BlockEntry> blocks(BigBoatsShips.Ship ship) {
		Block wood = ship != null ? ship.planks() : Blocks.OAK_PLANKS;
		BlockState planks = wood.defaultBlockState();
		BlockState mast = sibling(wood, "_fence", Blocks.OAK_FENCE).defaultBlockState();
		BlockState sail = (ship != null ? ship.sail() : Blocks.WOOL.white()).defaultBlockState();
		List<BlockEntry> blocks = new ArrayList<>();
		for (int z = 0; z <= 5; z++) {
			for (int x = -1; x <= 1; x++) blocks.add(new BlockEntry(new RelPos(x, 0, z), planks));
			blocks.add(new BlockEntry(new RelPos(-1, 1, z), planks));
			blocks.add(new BlockEntry(new RelPos(1, 1, z), planks));
		}
		blocks.add(new BlockEntry(new RelPos(0, 1, 0), planks));
		blocks.add(new BlockEntry(new RelPos(0, 0, 6), planks));
		blocks.add(new BlockEntry(new RelPos(0, 1, 6), planks));
		for (int y = 1; y <= 5; y++) blocks.add(new BlockEntry(new RelPos(0, y, 4), mast));
		// Up above a sitting follower's eyes, so the ship they are following stays in sight under it.
		for (int y = 3; y <= 4; y++) {
			blocks.add(new BlockEntry(new RelPos(0, y, 3), sail));
			blocks.add(new BlockEntry(new RelPos(0, y, 5), sail));
		}
		return blocks;
	}

	/** The same wood's fence for a mast: oak planks, oak fence. */
	private static Block sibling(Block planks, String suffix, Block otherwise) {
		var id = BuiltInRegistries.BLOCK.getKey(planks);
		if (!id.getPath().endsWith("_planks")) return otherwise;
		return BuiltInRegistries.BLOCK.getOptional(id.withPath(id.getPath().replace("_planks", suffix))).orElse(otherwise);
	}
}
