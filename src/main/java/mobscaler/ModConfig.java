package mobscaler;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Все настройки мода в одном JSON: <code>config/mobscaler.json</code>.
 * Файл создаётся при первом запуске сервера, правится на горячую не будет — нужен рестарт.
 */
public class ModConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("mobscaler.json");

	/** Версия формата файла: старый конфиг догоняется новыми умолчаниями при загрузке. */
	public static final int CURRENT_VERSION = 2;

	/** 0 в файле без ключа = конфиг версии 1 (мир в 10к). Новый файл сразу пишется текущей версией. */
	public int config_version = 0;

	// --- дистанция, на которой начинает работать усиление ---
	public int radius_min = 3000;
	/** Верхняя точка прямой линейки. Читается только когда {@code diff_stages} пуст. */
	public int radius_max = 20000;

	/**
	 * Ступени роста сложности: {@code [блоки от центра, доля силы 0..1]}, по возрастанию.
	 * Ближе первой ступени — 0, между ступенями линейно, дальше последней — её значение.
	 * Пустой список — прямая линейка {@code radius_min..radius_max} (так живут чужие конфиги).
	 */
	public List<double[]> diff_stages = defaultDiffStages();

	/**
	 * Радиус мира от центра (0,0): на старте сервера ставится ванильная граница размером
	 * {@code 2 × world_border_radius} блоков, то есть игрок не уходит дальше этого радиуса.
	 * 0 — границу не трогать вообще.
	 */
	public int world_border_radius = 20000;

	/** Кэш ступеней в плоском виде, чтобы не собирать его на каждый моб. */
	private transient double[] stageTableCache;

	// --- множители характеристик на самой дальней дистанции (radius_max и дальше) ---
	public double max_health_multiplier = 2.5;
	public double max_attack_multiplier = 2.0;
	public double max_speed_multiplier = 1.25;
	public double max_follow_multiplier = 1.3;
	public double max_xp_multiplier = 4.0;

	// --- крипер ---
	public boolean scale_creepers = true;
	public int creeper_fuse_min = 15;
	public int creeper_radius_max = 7;

	// --- броня ---
	public boolean equip_armor = true;
	/** Шанс, что конкретный слот брони окажется заполнен, на radius_min. */
	public double armor_slot_chance_min = 0.05;
	/** Шанс на radius_max и дальше. */
	public double armor_slot_chance_max = 0.8;

	// --- дроп ---
	/** Во сколько раз максимум умножается количество дропа на дальней дистанции. */
	public double max_drop_multiplier = 3.0;

	// --- стрелы скелетов ---
	public boolean tipped_skeleton_arrows = true;
	/** Во сколько раз растягивается время действия эффекта на стреле. */
	public double skeleton_effect_duration_multiplier = 3.0;

	// --- фантомы ---
	/** Свой спавн фантомов вместо ванильного; {@code false} — ванильное поведение. */
	public boolean scale_phantoms = true;
	/**
	 * Стадии размера пачки фантомов: {@code [блоки от центра, сколько в пачке]}, по возрастанию.
	 * Ближе первой стадии фантомов нет вовсе, между стадиями размер растёт линейно,
	 * дальше последней — потолок.
	 */
	public List<int[]> phantom_packs = defaultPhantomPacks();
	/** С этой дистанции фантомы приходят даже к спавшему игроку — маленькой пачкой. */
	public int phantom_rested_radius = 7000;
	/** Сколько фантомов в пачке после сна (дальше {@code phantom_rested_radius}). */
	public int phantom_rested_pack = 2;
	/** Потолок фантомов на одного игрока: пачки копятся, пока не упрутся в него. */
	public int phantom_limit_per_player = 18;
	/** Радиус, в котором считаются «свои» фантомы игрока при проверке потолка. */
	public int phantom_limit_radius = 64;

	/**
	 * Кого не усиливаем вообще. По умолчанию — все рейдовые (Raider), вексы, фантомы,
	 * боссы и «особые» мобы, которым баффы только ломают поведение.
	 */
	public List<String> excluded_mobs = new ArrayList<>(List.of(
			"minecraft:pillager",
			"minecraft:vindicator",
			"minecraft:evoker",
			"minecraft:illusioner",
			"minecraft:ravager",
			"minecraft:witch",
			"minecraft:vex",
			"minecraft:phantom",
			"minecraft:wither",
			"minecraft:ender_dragon",
			"minecraft:warden",
			"minecraft:elder_guardian",
			"minecraft:shulker",
			"minecraft:breeze"
	));

	public static ModConfig load() {
		ModConfig config = new ModConfig();
		boolean fresh = !Files.exists(PATH);
		if (!fresh) {
			try {
				String json = Files.readString(PATH, StandardCharsets.UTF_8);
				ModConfig loaded = GSON.fromJson(json, ModConfig.class);
				if (loaded != null) {
					config = loaded;
					if (config.excluded_mobs == null) {
						config.excluded_mobs = new ArrayList<>();
					}
					// "phantom_packs": null в файле разбирается в null, а не в инит-значение поля
					if (config.phantom_packs == null) {
						config.phantom_packs = defaultPhantomPacks();
					}
				}
			} catch (Exception e) {
				MobScaler.LOGGER.error("Не смог прочитать {}, беру значения по умолчанию (старый файл уйдёт в .bak)", PATH, e);
				backupBroken();
				fresh = true;
			}
		}
		if (fresh) {
			// без файла умолчания уже новые: миграция не нужна, но версию надо записать
			config.config_version = CURRENT_VERSION;
		} else {
			config.migrate();
		}
		config.save();
		return config;
	}

	/**
	 * Файл версии 1 писался под мир в 10к: границы усиления, стадии фантомов и граница мира
	 * в нём ещё старые. Свой радиус игрока не переписываем — только растягиваем умолчания.
	 */
	private void migrate() {
		if (config_version >= CURRENT_VERSION) {
			return;
		}
		boolean untouchedRange = radius_min == 3000 && radius_max == 10000;
		boolean untouchedPacks = oldPhantomPacks();

		if (untouchedRange) {
			radius_max = 20000;
		} else {
			// своя линейка: ступени не навязываем, оставляем прямую на его числах
			diff_stages = new ArrayList<>();
		}
		if (untouchedPacks) {
			phantom_packs = defaultPhantomPacks();
		}

		config_version = CURRENT_VERSION;
		stageTableCache = null;
		MobScaler.LOGGER.info("MobScaler: конфиг обновлён до версии {} (cfg {}): усиление до {}, фантомы до {}, граница мира {}",
				CURRENT_VERSION, PATH, (int) rampEnd(), lastPhantomDistance(),
				world_border_radius <= 0 ? "не трогаем" : world_border_radius + " блоков от центра");

		if (!untouchedRange && world_border_radius > radius_max) {
			MobScaler.LOGGER.warn("MobScaler: свои границы {}..{} сохранены, но граница мира {} — с {} до {} мобы уже на потолке."
							+ " Поправь world_border_radius или задай diff_stages",
					radius_min, radius_max, world_border_radius, radius_max, world_border_radius);
		}
	}

	/** Куда копировать битый файл, чтобы правки владельца не пропали при перезаписи. */
	private static void backupBroken() {
		try {
			Path backup = PATH.resolveSibling(PATH.getFileName() + ".bak");
			Files.copy(PATH, backup, StandardCopyOption.REPLACE_EXISTING);
			MobScaler.LOGGER.warn("MobScaler: битый конфиг сохранён в {}", backup);
		} catch (IOException e) {
			MobScaler.LOGGER.error("MobScaler: не смог сохранить копию битого конфига {}", PATH, e);
		}
	}

	/** Где кончается рост сложности: конец последней ступени, а без ступеней — radius_max. */
	public double rampEnd() {
		double[] table = stageTable();
		return table.length == 0 ? radius_max : table[table.length - 2];
	}

	/** Дальняя стадия фантомов; мусорные записи пропускаются, null в списке не роняет лог. */
	public int lastPhantomDistance() {
		if (phantom_packs == null) {
			return 0;
		}
		int last = 0;
		for (int[] step : phantom_packs) {
			if (step != null && step.length >= 2) {
				last = Math.max(last, step[0]);
			}
		}
		return last;
	}

	private boolean oldPhantomPacks() {
		if (phantom_packs == null || phantom_packs.size() != 4) {
			return false;
		}
		return phantom_packs.get(0)[0] == 3000 && phantom_packs.get(0)[1] == 1
				&& phantom_packs.get(1)[0] == 5000 && phantom_packs.get(1)[1] == 5
				&& phantom_packs.get(2)[0] == 7000 && phantom_packs.get(2)[1] == 10
				&& phantom_packs.get(3)[0] == 10000 && phantom_packs.get(3)[1] == 18;
	}

	public static List<int[]> defaultPhantomPacks() {
		return new ArrayList<>(List.of(
				new int[]{3000, 1},
				new int[]{6000, 4},
				new int[]{10000, 8},
				new int[]{15000, 13},
				new int[]{20000, 18}));
	}

	/** Ступени усиления под мир в 20к: середина мягче прежней, последние километры — обрыв. */
	public static List<double[]> defaultDiffStages() {
		return new ArrayList<>(List.of(
				new double[]{3000, 0.0},
				new double[]{8000, 0.30},
				new double[]{12000, 0.55},
				new double[]{16000, 0.78},
				new double[]{20000, 1.0}));
	}

	public void save() {
		try {
			Files.createDirectories(PATH.getParent());
			Files.writeString(PATH, GSON.toJson(this), StandardCharsets.UTF_8);
		} catch (IOException e) {
			MobScaler.LOGGER.error("Не смог записать {}", PATH, e);
		}
	}

	/** 0.0 на первой ступени, 1.0 на последней и дальше; без ступеней — прямая radius_min..radius_max. */
	public double diffFactor(double distance) {
		double[] table = stageTable();
		if (table.length == 0) {
			return linearFactor(distance);
		}
		if (distance < table[0]) {
			return 0.0;
		}
		for (int i = 2; i < table.length; i += 2) {
			if (distance < table[i]) {
				double t = (distance - table[i - 2]) / (table[i] - table[i - 2]);
				return clamp01(table[i - 1] + (table[i + 1] - table[i - 1]) * t);
			}
		}
		return clamp01(table[table.length - 1]);
	}

	private double linearFactor(double distance) {
		if (distance <= radius_min) {
			return 0.0;
		}
		if (distance >= radius_max) {
			return 1.0;
		}
		return (distance - radius_min) / (double) (radius_max - radius_min);
	}

	/** Плоская таблица {@code [блоки, доля, блоки, доля, ...]} с отбрасыванием не по возрастанию. */
	private double[] stageTable() {
		double[] cache = stageTableCache;
		if (cache != null) {
			return cache;
		}
		double[] result = buildStageTable();
		// ссылку кладём в поле последней: конкурентный читатель не должен увидеть нулевой массив
		stageTableCache = result;
		return result;
	}

	private double[] buildStageTable() {
		if (diff_stages == null || diff_stages.size() < 2) {
			return new double[0];
		}
		double[] out = new double[diff_stages.size() * 2];
		double last = Double.NEGATIVE_INFINITY;
		int n = 0;
		for (double[] step : diff_stages) {
			if (step == null || step.length < 2 || step[0] <= last) {
				continue;
			}
			out[n++] = step[0];
			out[n++] = step[1];
			last = step[0];
		}
		if (n < 4) {
			return new double[0];
		}
		double[] trimmed = new double[n];
		System.arraycopy(out, 0, trimmed, 0, n);
		return trimmed;
	}

	private static double clamp01(double value) {
		return value < 0.0 ? 0.0 : Math.min(value, 1.0);
	}

	/**
	 * Размер пачки фантомов на этой дистанции: 0 — фантомов тут не бывает.
	 * Между стадиями {@code phantom_packs} считается линейно, за последней стадией — её значение.
	 */
	public int phantomPackSize(double distance) {
		if (phantom_packs == null || phantom_packs.isEmpty()) {
			return 0;
		}
		int[] previous = null;
		for (int[] step : phantom_packs) {
			if (step == null || step.length < 2) {
				continue;
			}
			if (distance < step[0]) {
				if (previous == null) {
					return 0;
				}
				double t = (distance - previous[0]) / (double) (step[0] - previous[0]);
				return (int) Math.round(previous[1] + (step[1] - previous[1]) * t);
			}
			previous = step;
		}
		return previous == null ? 0 : previous[1];
	}

	public boolean isExcluded(EntityType<?> type) {
		Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
		return id != null && excluded_mobs.contains(id.toString());
	}
}
