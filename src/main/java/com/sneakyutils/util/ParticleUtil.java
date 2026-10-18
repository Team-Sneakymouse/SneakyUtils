package com.sneakyutils.util;

import java.util.Map;
import java.util.HashMap;

import org.bukkit.Particle;

public enum ParticleUtil {

	EXPLOSION_NORMAL("explosion_normal", "poof", "explode"),
	EXPLOSION_LARGE("explosion_large", "explosion", "largeexplode"),
	EXPLOSION_HUGE("explosion_huge", "explosion_emitter", "hugeexplosion"),
	WATER_BUBBLE("water_bubble", "bubble"),
	WATER_SPLASH("water_splash", "splash"),
	WATER_WAKE("water_wake", "fishing", "wake"),
	WATER_DROP("water_drop", "rain", "droplet"),
	SPELL("spell", "effect"),
	SPELL_INSTANT("spell_instant", "instant_effect", "instantspell"),
	SPELL_MOB("spell_mob", "entity_effect", "mobspell"),
	SPELL_MOB_AMBIENT("spell_mob_ambient", "ambient_entity_effect", "mobspellambient"),
	SPELL_WITCH("spell_witch", "witch", "witchmagic"),
	ITEM_CRACK("item_crack", "item", "iconcrack"),
	BLOCK_CRACK("block_crack", "blockcrack"),
	BLOCK_DUST("block_dust", "blockdust", "block"),
	SMOKE_NORMAL("smoke_normal", "smoke"),
	SMOKE_LARGE("smoke_large", "large_smoke", "largesmoke"),
	DRIP_WATER("drip_water", "dripping_water", "dripwater"),
	DRIP_LAVA("drip_lava", "dripping_lava", "driplava"),
	VILLAGER_ANGRY("villager_angry", "angry_villager", "angryvillager"),
	VILLAGER_HAPPY("villager_happy", "happy_villager", "happyvillager"),
	FIREWORKS_SPARK("fireworks_spark", "firework", "fireworksspark"),
	SUSPENDED("suspended", "underwater"),
	SUSPENDED_DEPTH("suspended_depth", "depthsuspend"),
	CRIT_MAGIC("crit_magic", "enchanted_hit", "magiccrit"),
	TOWN_AURA("town_aura", "mycelium", "townaura"),
	ENCHANTMENT_TABLE("enchantment_table", "enchant", "enchantmenttable"),
	REDSTONE("redstone", "dust", "reddust"),
	SNOWBALL("snowball", "item_snowball", "snowballpoof"),
	SLIME("slime", "item_slime"),
	MOB_APPEARANCE("mob_appearance", "elder_guardian", "mobappearance"),
	SNOW_SHOVEL("snow_shovel", "snowshovel"),
	DRAGON_BREATH("dragon_breath", "dragonbreath"),
	END_ROD("end_rod", "endrod"),
	DAMAGE_INDICATOR("damage_indicator", "damageindicator"),
	SWEEP_ATTACK("sweep_attack", "sweepattack"),
	FALLING_DUST("falling_dust", "fallingdust"),
	;

	private static final Map<String, Particle> namesToType = new HashMap<>();
	private static boolean initialized = false;

	private final String[] names;

	ParticleUtil(String... names) {
		this.names = names;
	}

	private static void initialize() {
		if (initialized) return;

		for (ParticleUtil p : ParticleUtil.values()) {
			Particle particle = null;

			try {
				particle = Particle.valueOf(p.name());
			} catch (Exception e) {
				// ignored
			}

			if (particle == null) continue;

			// handle the names
			namesToType.put(p.name().toLowerCase(), particle);
			for (String s : p.names) {
				namesToType.put(s.toLowerCase(), particle);
			}

		}

		initialized = true;
	}

	public static Particle getParticle(String particleName) {
		initialize();

		Particle particle = namesToType.get(particleName.toLowerCase());
		if (particle != null) return particle;

		try {
			particle = Particle.valueOf(particleName.toUpperCase());
		} catch (IllegalArgumentException ignored) {}

		return particle;
	}

}
