package mobscaler;

import mobscaler.mixin.PhantomSpawnerAccessor;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.levelgen.PhantomSpawner;

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

			// до 3к фантомов нет вообще, дальше пачка растёт по расстоянию
			check("2999 не спавнить", 0, PhantomSpawn.packToSpawn(config, 2999, false, 0));
			check("3000 пачка 1", 1, PhantomSpawn.packToSpawn(config, 3000, false, 0));
			check("4000 пачка 3", 3, PhantomSpawn.packToSpawn(config, 4000, false, 0));
			check("5000 пачка 5", 5, PhantomSpawn.packToSpawn(config, 5000, false, 0));
			check("7000 пачка 10", 10, PhantomSpawn.packToSpawn(config, 7000, false, 0));
			check("10000 пачка 18", 18, PhantomSpawn.packToSpawn(config, 10000, false, 0));
			check("12000 пачка 18", 18, PhantomSpawn.packToSpawn(config, 12000, false, 0));

			// спавшему игроку — только с 7к и не больше двух
			check("3000 спал — нет", 0, PhantomSpawn.packToSpawn(config, 3000, true, 0));
			check("6999 спал — нет", 0, PhantomSpawn.packToSpawn(config, 6999, true, 0));
			check("7000 спал — 2", 2, PhantomSpawn.packToSpawn(config, 7000, true, 0));
			check("10000 спал — 2", 2, PhantomSpawn.packToSpawn(config, 10000, true, 0));

			// потолок 18 на игрока
			check("10000, 16 рядом — 2", 2, PhantomSpawn.packToSpawn(config, 10000, false, 16));
			check("10000, 17 рядом — 1", 1, PhantomSpawn.packToSpawn(config, 10000, false, 17));
			check("10000, 18 рядом — 0", 0, PhantomSpawn.packToSpawn(config, 10000, false, 18));

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

	private static void check(String name, int expected, int actual) {
		report(name + " (ожидалось " + expected + ", получилось " + actual + ")", expected == actual);
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
