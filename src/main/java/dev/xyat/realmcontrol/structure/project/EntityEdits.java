package dev.xyat.realmcontrol.structure.project;

import com.google.gson.JsonObject;
import dev.xyat.kineticcore.api.inventory.KineticItemText;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.List;

/**
 * Edited entity data of drafts. The editor's common fields (name, health, AI, staying, equipment) are applied the way
 * the game does it: the entity is made from its data in the level, without joining it, changed through its own
 * methods and saved again, so the data keeps the format of the running version. Data never carries the position,
 * motion or UUID; the placement gives those.
 */
public final class EntityEdits {
    /** Slots the editor shows, in its order. */
    public static final List<EquipmentSlot> SLOTS = List.of(EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND,
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET);
    /** Highest health the editor sets: vanilla's limit of the maximum health attribute. */
    public static final float MAX_HEALTH = 1024F;
    private static final List<String> PLACED = List.of("Pos", "Motion", "UUID");

    private EntityEdits() {
    }

    /** The data the scan saw for an entity, from the draft's tiles; {@code null} if it is not there. */
    @Nullable
    public static CompoundTag scanned(StructureProject project, Markers.EntityMark mark) throws IOException {
        int tx = (int) Math.floor(mark.x / project.tileSize), tz = (int) Math.floor(mark.z / project.tileSize);
        Tile tile = ProjectStore.readTile(project.id, tx, tz);
        if (tile == null) return null;
        double ox = tx * (double) project.tileSize, oz = tz * (double) project.tileSize;
        for (Tile.EntityData entity : tile.entities) {
            CompoundTag tag = entity.tag();
            if (tag.getString("id").equals(mark.type) && Math.abs(ox + entity.x() - mark.x) < 1.0E-3D
                    && Math.abs(entity.y() - mark.y) < 1.0E-3D && Math.abs(oz + entity.z() - mark.z) < 1.0E-3D) {
                return tag;
            }
        }
        return null;
    }

    /** The data published for an entity: the edited data at the scanned position, or the scanned data. */
    public static CompoundTag published(CompoundTag scanned, @Nullable Markers.EntityMark mark) {
        if (mark == null || mark.data == null) return scanned;
        CompoundTag edited = parse(mark.data);
        if (edited == null) return scanned;
        edited.putString("id", mark.type);
        if (scanned.contains("Pos")) edited.put("Pos", scanned.get("Pos").copy());
        return edited;
    }

    @Nullable
    public static CompoundTag parse(String snbt) {
        try {
            return TagParser.parseTag(snbt);
        } catch (Exception e) {
            return null;
        }
    }

    /** Data without what a placement gives, as SNBT. */
    public static String clean(CompoundTag data) {
        CompoundTag copy = data.copy();
        PLACED.forEach(copy::remove);
        return copy.toString();
    }

    /** Whether the game can make an entity of {@code type} from this data. */
    public static boolean loads(ServerLevel level, String type, CompoundTag data) {
        return entity(level, type, data) != null;
    }

    /** The entity of {@code type} this data makes, not added to the level; {@code null} if it cannot be made. */
    @Nullable
    public static Entity entity(ServerLevel level, String type, CompoundTag data) {
        CompoundTag copy = data.copy();
        copy.putString("id", type);
        return load(level, copy);
    }

    /**
     * Applies the editor's fields to entity data: {@code name} (empty for none), {@code health} (the maximum and
     * current health), {@code no_ai}, {@code persistent} and {@code equipment} (slot name to item text, empty for
     * none). Fields the entity has no use for are skipped.
     *
     * @throws IllegalArgumentException if the entity cannot be made from {@code base}, or an item is unknown
     */
    public static CompoundTag apply(ServerLevel level, String type, CompoundTag base, JsonObject fields) {
        CompoundTag data = base.copy();
        data.putString("id", type);
        Entity entity = load(level, data);
        if (entity == null) throw new IllegalArgumentException("cannot make " + type);
        if (fields.has("name")) {
            String name = fields.get("name").getAsString().trim();
            entity.setCustomName(name.isEmpty() ? null : Component.literal(name));
        }
        if (entity instanceof LivingEntity living) {
            if (fields.has("health")) {
                float health = Math.max(1F, Math.min(MAX_HEALTH, fields.get("health").getAsFloat()));
                AttributeInstance maximum = living.getAttribute(Attributes.MAX_HEALTH);
                if (maximum != null) maximum.setBaseValue(health);
                living.setHealth(health);
            }
            if (fields.has("equipment")) {
                JsonObject equipment = fields.getAsJsonObject("equipment");
                for (EquipmentSlot slot : SLOTS) {
                    if (!equipment.has(slot.getName())) continue;
                    String text = equipment.get(slot.getName()).getAsString().trim();
                    living.setItemSlot(slot, text.isEmpty() ? ItemStack.EMPTY : KineticItemText.parse(text));
                }
            }
        }
        if (entity instanceof Mob mob && fields.has("no_ai")) mob.setNoAi(fields.get("no_ai").getAsBoolean());
        CompoundTag saved = save(level, entity);
        saved.putString("id", type);
        // A mob only ever sets this; clearing it goes through the data.
        if (entity instanceof Mob && fields.has("persistent")) saved.putBoolean("PersistenceRequired", fields.get("persistent").getAsBoolean());
        return saved;
    }

    @Nullable
    private static Entity load(ServerLevel level, CompoundTag data) {
        try {
            //? if >=26.1 {
            /*return EntityType.loadEntityRecursive(data, level, net.minecraft.world.entity.EntitySpawnReason.LOAD,
                    net.minecraft.world.entity.EntityProcessor.NOP);
            *///?} else {
            return EntityType.loadEntityRecursive(data, level, entity -> entity);
            //?}
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static CompoundTag save(ServerLevel level, Entity entity) {
        //? if >=26.1 {
        /*net.minecraft.world.level.storage.TagValueOutput output = net.minecraft.world.level.storage.TagValueOutput.createWithContext(
                net.minecraft.util.ProblemReporter.DISCARDING, level.registryAccess());
        entity.saveWithoutId(output);
        return output.buildResult();
        *///?} else {
        CompoundTag tag = new CompoundTag();
        entity.saveWithoutId(tag);
        return tag;
        //?}
    }
}
