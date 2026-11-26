package dev.xyat.realmcontrol.structure.network;

import com.google.gson.JsonParser;
import dev.xyat.kineticcore.api.network.NetworkCodec;
import dev.xyat.kineticcore.api.network.PacketChannel;
import dev.xyat.realmcontrol.structure.StructureEditorModule;
import dev.xyat.realmcontrol.structure.project.ProjectStore;
import dev.xyat.realmcontrol.structure.project.StructureProject;
import dev.xyat.realmcontrol.structure.publish.TestPlacement;
import dev.xyat.realmcontrol.worldgen.preview.StructureImportService;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

/** Open a registered layout in the editor or place a draft in the player's current world. */
final class LayoutNetwork {
    record Generate(String id, BlockPos min) {}
    record Inspect(BlockPos pos, int entityId) {}
    record UseLayout(String source, int variant, boolean place) {}
    private record OpenPlacement(String project) {}

    static void register(PacketChannel channel) {
        channel.registerServerbound(15, Generate.class, NetworkCodec.of((b, m) -> {
            b.writeUtf(m.id(), 256);
            b.writeBlockPos(m.min());
        }, b -> new Generate(b.readUtf(256), b.readBlockPos())), (m, ctx) -> {
            if (StructureEditorModule.allowed(ctx.sender()) && StructureProject.validId(m.id()))
                TestPlacement.generate(ctx.sender(), m.id(), m.min());
        });
        channel.registerServerbound(16, Inspect.class, NetworkCodec.of((b, m) -> {
            b.writeBlockPos(m.pos());
            b.writeVarInt(m.entityId());
        }, b -> new Inspect(b.readBlockPos(), b.readVarInt())), (m, ctx) -> {
            if (StructureEditorModule.allowed(ctx.sender())) WorldStructureInspector.inspect(ctx.sender(), m.pos(), m.entityId());
        });
        channel.registerServerbound(17, UseLayout.class, NetworkCodec.of((b, m) -> {
            b.writeUtf(m.source(), 256);
            b.writeVarInt(m.variant());
            b.writeBoolean(m.place());
        }, b -> new UseLayout(b.readUtf(256), b.readVarInt(), b.readBoolean())), (m, ctx) -> {
            ServerPlayer player = ctx.sender();
            if (!StructureEditorModule.allowed(player) || !StructureProject.validId(m.source()) || m.variant() < 0) return;
            StructureProject own = ProjectStore.load(m.source());
            if (own != null) {
                open(player, own, m.place());
                return;
            }
            String id = WorldStructureInspector.newId(m.source());
            var server = player.getServer();
            var owner = player.getUUID();
            StructureImportService.request(player, m.source(), m.variant(), id, result -> {
                ServerPlayer online = server.getPlayerList().getPlayer(owner);
                if (online == null || !StructureEditorModule.allowed(online)) return;
                if (result.project() == null) {
                    if (result.error() != null) StructureEditorNetwork.result(online, result.error(), false);
                } else {
                    open(online, result.project(), m.place());
                }
            });
        });
        channel.registerClientbound(18, OpenPlacement.class, NetworkCodec.of((b, m) -> b.writeUtf(m.project(), 262144),
                b -> new OpenPlacement(b.readUtf(262144))), m -> dev.xyat.realmcontrol.structure.client.EditorClient.openPlacement(
                        StructureProject.fromJson(JsonParser.parseString(m.project()).getAsJsonObject())));
    }

    private static void open(ServerPlayer player, StructureProject project, boolean place) {
        StructureEditorNetwork.sendProjects(player, project.id, true);
        if (place) {
            var json = project.toJson();
            json.addProperty("origin_in_current_world", dev.xyat.realmcontrol.structure.project.PlacedDrafts.worldKey(player.serverLevel()).equals(project.sourceWorld));
            StructureEditorNetwork.CHANNEL.sendToPlayer(player, new OpenPlacement(json.toString()));
        } else StructureEditorNetwork.sendMarkers(player, project.id, "", true);
    }

    private LayoutNetwork() {}
}
