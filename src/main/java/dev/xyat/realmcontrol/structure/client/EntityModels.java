package dev.xyat.realmcontrol.structure.client;

import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;

import javax.annotation.Nullable;

/** Entities of drafts as the 3D editor shows them: made from their data in the client's level, never added to it. */
public final class EntityModels {
    private EntityModels() {
    }

    /** The entity {@code data} describes, as type {@code type}; {@code null} if this client cannot make it. */
    @Nullable
    public static Entity load(String type, CompoundTag data) {
        var level = Minecraft.getInstance().level;
        if (level == null) return null;
        CompoundTag tag = data.copy();
        tag.putString("id", type);
        try {
            //? if >=26.1 {
            /*Entity entity = EntityType.loadEntityRecursive(tag, level, net.minecraft.world.entity.EntitySpawnReason.LOAD,
                    net.minecraft.world.entity.EntityProcessor.NOP);
            *///?} else {
            Entity entity = EntityType.loadEntityRecursive(tag, level, loaded -> loaded);
            //?}
            if (entity != null) entity.setCustomNameVisible(false);
            return entity;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Width and height of a type's standing box, in blocks; a half block for an unknown type. */
    public static float[] size(String type) {
        return EntityType.byString(type).map(entityType -> {
            var dimensions = entityType.getDimensions();
            //? if >=1.21 {
            /*return new float[] {dimensions.width(), dimensions.height()};
            *///?} else {
            return new float[] {dimensions.width, dimensions.height};
            //?}
        }).orElse(new float[] {0.5F, 0.5F});
    }
}
