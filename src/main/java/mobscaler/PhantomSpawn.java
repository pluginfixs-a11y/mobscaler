package mobscaler;

import mobscaler.mixin.PhantomSpawnerAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.monster.Phantom;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.PhantomSpawner;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;

/**
 * Спавн фантомов вместо ванильного (сам ванильный отменён в {@code PhantomSpawnerMixin}).
 * <p>
 * Правила:
 * <ul>
 *   <li>ближе первой стадии {@code phantom_packs} (3000 блоков от центра мира) фантомов нет вовсе;</li>
 *   <li>до {@code phantom_rested_radius} (7000) они приходят только к тому, кто давно не спал,
 *       и пачкой по таблице расстояний (3000 → 1, 5000 → 5, 7000 → 10);</li>
 *   <li>дальше 7000 — даже к спавшему игроку, но маленькой пачкой {@code phantom_rested_pack} (2);</li>
 *   <li>пачки копятся, пока у игрока не наберётся {@code phantom_limit_per_player} (18) фантомов.</li>
 * </ul>
 * Остальные ванильные условия сохранены: ночь, открытое небо, сложность, шанс от времени без сна,
 * пустой блок для спавна. Периодичность попыток — тоже ванильная, через поле {@code nextTick}.
 */
public final class PhantomSpawn {
	/** Ванильный порог: спавн возможен, когда с последнего сна прошло больше 72000 тиков. */
	private static final int INSOMNIA_TICKS = 72000;

	private PhantomSpawn() {
	}

	public static void tick(ServerLevel level, PhantomSpawner spawner) {
		PhantomSpawnerAccessor accessor = (PhantomSpawnerAccessor) spawner;
		RandomSource random = level.getRandom();

		int nextTick = accessor.mobscaler$getNextTick() - 1;
		accessor.mobscaler$setNextTick(nextTick);
		if (nextTick > 0) {
			return;
		}
		accessor.mobscaler$setNextTick(nextTick + (60 + random.nextInt(60)) * 20);

		// Днём фантомы не приходят — то же условие, что в ванили.
		if (level.getSkyDarken() < 5 && level.dimensionType().hasSkyLight()) {
			return;
		}

		for (ServerPlayer player : level.players()) {
			if (player.isSpectator()) {
				continue;
			}
			trySpawnFor(level, player, random);
		}
	}

	private static void trySpawnFor(ServerLevel level, ServerPlayer player, RandomSource random) {
		ModConfig config = MobScaler.config;
		BlockPos pos = player.blockPosition();
		if (level.dimensionType().hasSkyLight() && (pos.getY() < level.getSeaLevel() || !level.canSeeSky(pos))) {
			return;
		}

		double distance = Math.hypot(player.getX(), player.getZ());
		int pack = packToSpawn(config, distance, sleptRecently(player, random),
				phantomsAround(level, player, config.phantom_limit_radius));
		if (pack <= 0) {
			return;
		}

		DifficultyInstance difficulty = level.getCurrentDifficultyAt(pos);
		if (!difficulty.isHarderThan(random.nextFloat() * 3.0f)) {
			return;
		}

		BlockPos anchor = pos.above(20 + random.nextInt(15))
				.east(-10 + random.nextInt(21))
				.south(-10 + random.nextInt(21));
		if (!isValidSpawnSpot(level, anchor)) {
			return;
		}

		spawn(level, anchor, difficulty, pack, random);
	}

	/**
	 * Правило пачки без побочных эффектов: сколько фантомов спавнить этому игроку, 0 — не спавнить.
	 * Вынесено отдельно, чтобы самопроверка проверяла именно правило, а не мир вокруг.
	 *
	 * @param distance расстояние игрока от центра мира по X/Z
	 * @param slept    игрок спал недавно (ванильная проверка {@code time_since_rest})
	 * @param existing сколько фантомов уже рядом с игроком
	 */
	public static int packToSpawn(ModConfig config, double distance, boolean slept, int existing) {
		int pack = config.phantomPackSize(distance);
		if (pack <= 0) {
			return 0;
		}
		if (slept) {
			if (distance < config.phantom_rested_radius) {
				return 0;
			}
			pack = Math.min(pack, config.phantom_rested_pack);
		}
		return Math.max(0, Math.min(pack, config.phantom_limit_per_player - existing));
	}

	/** Спавн только к тому, кто давно не спал: ванильная проверка по статистике {@code time_since_rest}. */
	private static boolean sleptRecently(ServerPlayer player, RandomSource random) {
		int sinceRest = Mth.clamp(
				player.getStats().getValue(Stats.CUSTOM.get(Stats.TIME_SINCE_REST)), 1, Integer.MAX_VALUE);
		return random.nextInt(sinceRest) < INSOMNIA_TICKS;
	}

	private static int phantomsAround(ServerLevel level, ServerPlayer player, int radius) {
		AABB box = player.getBoundingBox().inflate(radius);
		return level.getEntitiesOfClass(Phantom.class, box).size();
	}

	private static boolean isValidSpawnSpot(ServerLevel level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		FluidState fluid = level.getFluidState(pos);
		return NaturalSpawner.isValidEmptySpawnBlock(level, pos, state, fluid, EntityTypes.PHANTOM);
	}

	/** Каждый фантом пачки садится в свою точку рядом с якорем, чтобы 18 штук не вылезали из одного блока. */
	private static void spawn(ServerLevel level, BlockPos anchor, DifficultyInstance difficulty, int count, RandomSource random) {
		SpawnGroupData groupData = null;
		for (int i = 0; i < count; i++) {
			Phantom phantom = EntityTypes.PHANTOM.create(level, EntitySpawnReason.NATURAL);
			if (phantom == null) {
				return;
			}
			phantom.snapTo(spread(level, anchor, random), 0.0f, 0.0f);
			groupData = phantom.finalizeSpawn(level, difficulty, EntitySpawnReason.NATURAL, groupData);
			level.addFreshEntityWithPassengers(phantom);
		}
	}

	private static BlockPos spread(ServerLevel level, BlockPos anchor, RandomSource random) {
		BlockPos spot = anchor.offset(random.nextInt(7) - 3, random.nextInt(5) - 2, random.nextInt(7) - 3);
		return isValidSpawnSpot(level, spot) ? spot : anchor;
	}
}
