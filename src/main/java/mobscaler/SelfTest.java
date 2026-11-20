package mobscaler;

import mobscaler.mixin.PhantomSpawnerAccessor;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.levelgen.PhantomSpawner;

import java.util.ArrayList;
import java.util.List;

/**
 * Самопроверка правил спавна фантомов без клиента и без игрока: правило пачки
 * ({@link PhantomSpawn#packToSpawn}) и таймер ванильного спавнера через {@code @Accessor}.
 * Включается только системным свойством {@code -Dmobscaler.selftest=1}.
 * <p>
 * Почему не {@code SERVER_STARTED}: первый тик — как в ladderhang/steed, к этому моменту
 * конфиг уже прочитан и все моды подняты.
 */
public final class SelfTest {
	private static boolean done;
	private static int passed;
	private static int failed;

	private SelfTest() {
	}

	public static void onTick(MinecraftServer server) {
		if (done) {
			return;
		}
		done = true;
		run(server);
	}

	public static void run(MinecraftServer server) {
		try {
			ModConfig config = MobScaler.config;
			checkPhantoms(config);
			checkCurve(config);
			checkEdgeStages();

			if (config.world_border_radius > 0) {
				double side = 2.0 * config.world_border_radius;
				checkRate("граница мира — сторона " + side, side, server.overworld().getWorldBorder().getSize());
				boolean everyLevel = true;
				for (net.minecraft.server.level.ServerLevel level : server.getAllLevels()) {
					double actual = level.getWorldBorder().getSize();
					if (actual != side) {
						everyLevel = false;
						MobScaler.LOGGER.error("SELFTEST FAIL: граница в {} = {}", level.dimension(), actual);
					}
				}
				report("граница мира стоит во всех измерениях", everyLevel);
			}

			// таймер: @Accessor читает и пишет ванильное поле nextTick, тик его уменьшает
			PhantomSpawner spawner = new PhantomSpawner();
			PhantomSpawnerAccessor accessor = (PhantomSpawnerAccessor) spawner;
			accessor.mobscaler$setNextTick(0);
			PhantomSpawn.tick(server.overworld(), spawner);
			int afterFirst = accessor.mobscaler$getNextTick();
			boolean timerRange = afterFirst >= 1199 && afterFirst <= 2379;
			report("таймер: 0 -> " + afterFirst + " в диапазоне 1199..2379", timerRange);
			PhantomSpawn.tick(server.overworld(), spawner);
			int afterSecond = accessor.mobscaler$getNextTick();
			report("таймер: второй тик уменьшил на 1 (" + afterFirst + " -> " + afterSecond + ")", afterSecond == afterFirst - 1);
		} catch (Throwable t) {
			failed++;
			MobScaler.LOGGER.error("SELFTEST упал", t);
		}

		MobScaler.LOGGER.info("SELFTEST: {} из {} — {}", passed, passed + failed, failed == 0 ? "PASS" : "FAILED");
	}

	/**
	 * Правила фантомов проверяются от самого конфига, а не от зашитых чисел: на своём конфиге
	 * самопроверка не должна краснеть из-за того, что владелец поменял стадии.
	 */
	private static void checkPhantoms(ModConfig config) {
		if (config.phantom_packs == null || config.phantom_packs.isEmpty()) {
			report("стадии фантомов не заданы — проверять нечего", false);
			return;
		}
		int[] first = config.phantom_packs.get(0);
		int[] last = config.phantom_packs.get(config.phantom_packs.size() - 1);
		int limit = config.phantom_limit_per_player;

		check("до первой стадии (" + (first[0] - 1) + ") не спавнить", 0,
				PhantomSpawn.packToSpawn(config, first[0] - 1, false, 0));

		// ровно в точке стадии — ровно её пачка (не больше потолка на игрока)
		for (int[] stage : config.phantom_packs) {
			int expected = Math.min(stage[1], limit);
			check("стадия " + stage[0] + " — пачка " + expected, expected,
					PhantomSpawn.packToSpawn(config, stage[0], false, 0));
		}

		// между стадиями пачка не убывает, за последней — её значение
		int previous = 0;
		boolean grows = true;
		for (int distance = 2500; distance <= last[0] + 5000; distance += 250) {
			int pack = PhantomSpawn.packToSpawn(config, distance, false, 0);
			if (pack < previous) {
				grows = false;
			}
			previous = pack;
		}
		report("пачка растёт с расстоянием и за последней стадией не падает", grows);
		check("за последней стадией (" + (last[0] + 5000) + ") — пачка " + Math.min(last[1], limit),
				Math.min(last[1], limit), PhantomSpawn.packToSpawn(config, last[0] + 5000, false, 0));

		// спавшему игроку: до phantom_rested_radius — ничего, дальше — не больше phantom_rested_pack
		int rested = Math.min(config.phantom_rested_pack, limit);
		check("спал на " + (config.phantom_rested_radius - 1) + " — нет", 0,
				PhantomSpawn.packToSpawn(config, config.phantom_rested_radius - 1, true, 0));
		check("спал на " + last[0] + " — " + rested, rested,
				PhantomSpawn.packToSpawn(config, last[0], true, 0));

		// потолок: рядом уже limit фантомов — новых нет, limit-1 — ровно один
		check("потолок: рядом " + limit + " — 0", 0, PhantomSpawn.packToSpawn(config, last[0], false, limit));
		check("потолок: рядом " + (limit - 1) + " — 1", 1, PhantomSpawn.packToSpawn(config, last[0], false, limit - 1));
	}

	/** Кривая сложности: 0 в начале, 1 в конце, не убывает, всегда в 0..1. */
	private static void checkCurve(ModConfig config) {
		double start = config.diff_stages == null || config.diff_stages.size() < 2
				? config.radius_min : config.diff_stages.get(0)[0];
		double end = config.diff_stages == null || config.diff_stages.size() < 2
				? config.radius_max
				: config.diff_stages.get(config.diff_stages.size() - 1)[0];

		checkRate("кривая: " + (int) start + " — 0", 0.0, config.diffFactor(start));
		checkRate("кривая: " + (int) end + " — 1", 1.0, config.diffFactor(end));
		checkRate("кривая: за концом (" + (int) (end + 5000) + ") — 1", 1.0, config.diffFactor(end + 5000));

		double previous = 0.0;
		boolean monotone = true;
		boolean bounded = true;
		for (double distance = 0; distance <= end + 5000; distance += 250) {
			double diff = config.diffFactor(distance);
			if (diff < previous - 1e-9) {
				monotone = false;
			}
			if (diff < 0.0 || diff > 1.0) {
				bounded = false;
			}
			previous = diff;
		}
		report("кривая не убывает", monotone);
		report("кривая всегда в 0..1", bounded);
		checkRate("дроп на конце — ×" + config.max_drop_multiplier, config.max_drop_multiplier,
				MobScaler.dropMultiplier(end));
	}

	/**
	 * Крайние формы ступеней: null, одна ступень, не по возрастанию, дубль дистанции.
	 * Все они должны тихо откатываться на прямую {@code radius_min..radius_max}, без исключений.
	 * У каждого случая свой экземпляр конфига: кэш таблицы считается один раз на объект.
	 */
	private static void checkEdgeStages() {
		ModConfig empty = edgeConfig();
		empty.diff_stages = null;
		checkRate("ступени null → прямая (1500 из 1000..2000 = 0.5)", 0.5, empty.diffFactor(1500));

		ModConfig one = edgeConfig();
		one.diff_stages = new ArrayList<>(List.of(new double[]{1000, 0.0}));
		checkRate("одна ступень → прямая", 0.5, one.diffFactor(1500));

		ModConfig unordered = edgeConfig();
		unordered.diff_stages = new ArrayList<>(List.of(
				new double[]{2000, 0.5}, new double[]{1000, 0.9}));
		checkRate("ступени не по возрастанию → прямая", 0.5, unordered.diffFactor(1500));

		ModConfig duplicate = edgeConfig();
		duplicate.diff_stages = new ArrayList<>(List.of(
				new double[]{1000, 0.0}, new double[]{1000, 0.5}, new double[]{2000, 1.0}));
		checkRate("дубль дистанции не даёт деления на ноль", 0.0, duplicate.diffFactor(1000));
	}

	private static ModConfig edgeConfig() {
		ModConfig config = new ModConfig();
		config.radius_min = 1000;
		config.radius_max = 2000;
		return config;
	}

	private static void check(String name, int expected, int actual) {
		report(name + " (ожидалось " + expected + ", получилось " + actual + ")", expected == actual);
	}

	private static void checkRate(String name, double expected, double actual) {
		report(name + " (ожидалось " + expected + ", получилось " + actual + ")",
				Math.abs(expected - actual) < 1e-6);
	}

	private static void report(String line, boolean ok) {
		if (ok) {
			passed++;
			MobScaler.LOGGER.info("SELFTEST ok: {}", line);
		} else {
			failed++;
			MobScaler.LOGGER.error("SELFTEST FAIL: {}", line);
		}
	}
}
