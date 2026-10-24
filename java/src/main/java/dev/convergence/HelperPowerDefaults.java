package dev.convergence;

import java.util.Map;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.golem.IronGolem;

/** A one-time native helper upgrade, independent of the owner's stat undo history. */
final class HelperPowerDefaults {
    static final String MARKER = "infinity_helper_power_defaults";
    static final int VERSION = 1;
    private static final Map<Holder<Attribute>, Double> DEFAULTS = Map.of(
        Attributes.MAX_HEALTH, 1000d, Attributes.ATTACK_DAMAGE, 100d,
        Attributes.ARMOR, 30d, Attributes.ARMOR_TOUGHNESS, 20d,
        Attributes.KNOCKBACK_RESISTANCE, 1d);
    private static final net.minecraft.world.entity.ai.attributes.AttributeSupplier VANILLA = IronGolem.createAttributes().build();

    private HelperPowerDefaults() {}

    static void applyRegistered(IronGolem helper) {
        if (!AgentCompanions.isAgent(helper) || !(helper.level() instanceof ServerLevel world)
            || !(helper instanceof AdminStatEntity)) return;
        var companions = AgentCompanions.INSTANCES.get(world.getServer());
        if (companions == null || !companions.data.agents.containsKey(helper.getStringUUID())) return;
        var saved = (AdminStatEntity)helper;
        var records = saved.infinity$adminStats();
        // Even an unknown marker is retained rather than reapplying over a later
        // migration or manually repaired entity. Resetting stats keeps this marker.
        if (records.contains(MARKER)) return;
        float health = helper.getHealth();
        for (var entry : DEFAULTS.entrySet()) {
            var attribute = entry.getKey(); var instance = helper.getAttribute(attribute);
            String key = BuiltInRegistries.ATTRIBUTE.getKey(attribute.value()).toString();
            if (instance != null && !records.contains(key)
                && Double.compare(instance.getBaseValue(), VANILLA.getBaseValue(attribute)) == 0)
                instance.setBaseValue(entry.getValue());
        }
        records.putInt(MARKER, VERSION); saved.infinity$adminStats(records);
        // Existing damage is not silently repaired by loading, recalling or
        // changing a profile. Only the explicit creation paths fill the new max.
        helper.setHealth(health);
    }

    static void fillNewSpawn(IronGolem helper) {
        applyRegistered(helper);
        if (AgentCompanions.isAgent(helper) && helper instanceof AdminStatEntity saved
            && saved.infinity$adminStats().getIntOr(MARKER, 0) == VERSION)
            helper.setHealth(helper.getMaxHealth());
    }
}
