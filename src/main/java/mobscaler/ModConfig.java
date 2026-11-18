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
import java.util.ArrayList;
import java.util.List;

/**
 * Все настройки мода в одном JSON: <code>config/mobscaler.json</code>.
 * Файл создаётся при первом запуске сервера, правится на горячую не будет — нужен рестарт.
 */
public class ModConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("mobscaler.json");

	// --- дистанция, на которой начинает работать усиление ---
	public int radius_min = 3000;
	public int radius_max = 10000;

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
	public List<int[]> phantom_packs = new ArrayList<>(List.of(
			new int[]{3000, 1},
			new int[]{5000, 5},
			new int[]{7000, 10},
			new int[]{10000, 18}));
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
		if (Files.exists(PATH)) {
			try {
				String json = Files.readString(PATH, StandardCharsets.UTF_8);
				ModConfig loaded = GSON.fromJson(json, ModConfig.class);
				if (loaded != null) {
					config = loaded;
					if (config.excluded_mobs == null) {
						config.excluded_mobs = new ArrayList<>();
					}
				}
			} catch (Exception e) {
				MobScaler.LOGGER.error("Не смог прочитать {}, беру значения по умолчанию", PATH, e);
			}
		}
		config.save();
		return config;
	}

	public void save() {
		try {
			Files.createDirectories(PATH.getParent());
			Files.writeString(PATH, GSON.toJson(this), StandardCharsets.UTF_8);
		} catch (IOException e) {
			MobScaler.LOGGER.error("Не смог записать {}", PATH, e);
		}
	}

	/** 0.0 на radius_min, 1.0 на radius_max и дальше. */
	public double diffFactor(double distance) {
		if (distance <= radius_min) {
			return 0.0;
		}
		if (distance >= radius_max) {
			return 1.0;
		}
		return (distance - radius_min) / (double) (radius_max - radius_min);
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
