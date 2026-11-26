package dev.xyat.realmcontrol.structure.network;

import dev.xyat.kineticcore.api.text.KineticI18n;
import dev.xyat.realmcontrol.structure.StructureEditorModule;
import dev.xyat.realmcontrol.structure.mega.MegaStructure;
import dev.xyat.realmcontrol.structure.mega.MegaLayout;
import dev.xyat.realmcontrol.structure.project.Markers;
import dev.xyat.realmcontrol.structure.project.PlacedDrafts;
import dev.xyat.realmcontrol.structure.project.ProjectStore;
import dev.xyat.realmcontrol.structure.project.ScanService;
import dev.xyat.realmcontrol.structure.project.StructureProject;
import dev.xyat.realmcontrol.structure.selection.Selection;
import dev.xyat.realmcontrol.structure.selection.SelectionStore;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructureStart;

import javax.annotation.Nullable;
import java.util.Comparator;
import java.util.List;

final class WorldStructureInspector {
    /** Draft IDs stay separate from the registered source and any previously saved edit. */
    static String newId(String source) {
        String slug = source.replace(':', '_').replace('/', '_');
        String base = "realmcontrol_edits:" + slug.substring(0, Math.min(slug.length(), 96));
        String id = base;
        for (int n = 2; ProjectStore.exists(id) || ScanService.busy(id); n++) id = base + "_" + n;
        return id;
    }

    static void inspect(ServerPlayer player, BlockPos clicked, int entityId) {
        ServerLevel level = player.serverLevel();
        if (!player.getMainHandItem().is(StructureEditorModule.CONTROLLER.get()) && !player.getOffhandItem().is(StructureEditorModule.CONTROLLER.get())) return;
        Entity entity = entityId < 0 ? null : level.getEntity(entityId);
        if (entityId >= 0 && (entity == null || entity instanceof net.minecraft.world.entity.player.Player)) return;
        BlockPos target = entity == null ? clicked : entity.blockPosition();
        if (target.distToCenterSqr(player.position()) > 514D * 514D || !level.hasChunkAt(target)) return;
        PlacedDrafts.Instance link = PlacedDrafts.at(level, target);
        if (link != null) {
            open(player, link.id(), link.min(), target, entity);
            return;
        }
        var starts = level.structureManager().startsForStructure(new ChunkPos(target), s -> true);
        StructureStart start = starts.stream().filter(s -> s.isValid() && s.getBoundingBox().isInside(target))
                .min(Comparator.comparing((StructureStart s) -> s.getPieces().stream().noneMatch(p -> p.getBoundingBox().isInside(target)))
                        .thenComparingLong(s -> box(s.getBoundingBox()).volume())).orElse(null);
        if (start != null && start.getStructure() instanceof MegaStructure mega && openMega(player, mega, start, target, entity)) return;
        Selection.Box region;
        String source = "";
        if (start != null && !(start.getStructure() instanceof MegaStructure)) {
            region = box(start.getBoundingBox());
            var key = level.registryAccess().registryOrThrow(Registries.STRUCTURE).getKey(start.getStructure());
            if (key != null) source = key.toString();
        } else {
            region = SelectionStore.get(player).region();
            if (region == null || !region.contains(target.getX(), target.getY(), target.getZ())) {
                StructureEditorNetwork.result(player, KineticI18n.translatable("msg.realmcontrol.structure.inspect.selection"), false);
                return;
            }
        }
        String id = newId(source.isEmpty() ? "selection" : source);
        BlockPos min = region.min();
        var focusedEntity = entity == null ? null : entity.getUUID();
        ScanService.start(player, id, source.isEmpty() ? id : source, level, region, List.of(), source, ready -> {
            StructureProject project = ProjectStore.load(id);
            if (project == null) return;
            PlacedDrafts.record(level, project, min);
            if (ready.level() != level) return;
            open(ready, id, min, target, focusedEntity == null ? null : level.getEntity(focusedEntity));
        });
    }

    private static boolean openMega(ServerPlayer player, MegaStructure mega, StructureStart start, BlockPos target,
                                    @Nullable Entity entity) {
        ServerLevel level = player.serverLevel();
        StructureProject project = ProjectStore.load(mega.tiles.toString());
        if (project == null || project.size[0] != mega.layout.sizeX || project.size[1] != mega.layout.sizeY
                || project.size[2] != mega.layout.sizeZ) return false;
        var placement = mega.placement(level.registryAccess());
        ChunkPos group = start.getChunkPos();
        var match = placement == null ? null : placement.match(level.getSeed(), MegaLayout.chunkX(group), MegaLayout.chunkZ(group));
        if (match == null) return false;
        var chunks = level.getChunkSource();
        int bottom = mega.bottom(chunks.getGenerator(), level, chunks.randomState(), match.anchor(), match.rotation());
        BlockPos anchor = new BlockPos(match.anchor().getMinBlockX(), bottom, match.anchor().getMinBlockZ());
        BlockPos local = mega.layout.local(anchor, match.rotation(), target);
        if (local.getX() < 0 || local.getX() >= project.size[0] || local.getY() < 0 || local.getY() >= project.size[1]
                || local.getZ() < 0 || local.getZ() >= project.size[2]) return false;
        openAt(player, project.id, local, entity, null);
        return true;
    }

    private static Selection.Box box(BoundingBox b) {
        return new Selection.Box(new BlockPos(b.minX(), b.minY(), b.minZ()), new BlockPos(b.maxX(), b.maxY(), b.maxZ()));
    }

    private static void open(ServerPlayer player, String id, BlockPos min, BlockPos world, @Nullable Entity entity) {
        openAt(player, id, world.subtract(min), entity, min);
    }

    private static void openAt(ServerPlayer player, String id, BlockPos pos, @Nullable Entity entity, @Nullable BlockPos min) {
        Markers markers = ProjectStore.markers(id);
        int x = pos.getX(), y = pos.getY(), z = pos.getZ();
        String coord = x + "," + y + "," + z;
        String focus;
        if (entity != null) {
            String type = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
            Markers.EntityMark mark = markers.entities.stream().filter(m -> entity.getUUID().toString().equals(m.sourceUuid)).findFirst().orElse(null);
            if (mark == null && min != null)
                mark = markers.entity(type, entity.getX() - min.getX(), entity.getY() - min.getY(), entity.getZ() - min.getZ());
            if (mark == null) {
                var matches = markers.entities.stream().filter(m -> m.type.equals(type) && Math.floor(m.x) == x
                        && Math.floor(m.y) == y && Math.floor(m.z) == z).limit(2).toList();
                if (matches.size() == 1) mark = matches.get(0);
            }
            focus = mark == null ? "entities" : mark.key();
        } else if (markers.container(x,y,z) != null) focus = "c:" + coord;
        else if (markers.spawner(x,y,z) != null) focus = "s:" + coord;
        else focus = "block:" + coord;
        StructureEditorNetwork.sendProjects(player, id, true);
        StructureEditorNetwork.sendMarkers(player, id, focus, true);
    }

    private WorldStructureInspector() {}
}
