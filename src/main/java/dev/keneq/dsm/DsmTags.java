package dev.keneq.dsm;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;

public final class DsmTags {
    public static final TagKey<EntityType<?>> HOSTILES = TagKey.create(
            Registries.ENTITY_TYPE,
            new ResourceLocation(DsmMod.MOD_ID, "hostiles")
    );

    private DsmTags() {
    }
}