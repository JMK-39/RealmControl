package dev.xyat.realmcontrol.structure;

import dev.xyat.kineticcore.api.event.KineticEventPriority;
import dev.xyat.kineticcore.api.registry.KineticItems;
import dev.xyat.kineticcore.api.registry.KineticRegistryHandle;
import dev.xyat.kineticcore.api.resource.KineticResourceIds;
import dev.xyat.kineticcore.api.runtime.KineticCreativeTabs;
import dev.xyat.kineticcore.api.runtime.KineticPlatform;
import dev.xyat.kineticcore.api.server.event.KineticServerEvents;
import dev.xyat.realmcontrol.structure.command.StructureCommand;
import dev.xyat.realmcontrol.structure.network.StructureEditorNetwork;
import dev.xyat.realmcontrol.structure.project.ScanService;
import dev.xyat.realmcontrol.structure.selection.SelectionStore;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;

/**
 * The structure editor: the controller item that selects a region in the world, scanning it into a draft, editing
 * the draft (cut-outs, generation rules) and publishing it as a vanilla data pack structure.
 */
public final class StructureEditorModule {
    public static final String MODID = "realmcontrol";
    public static final KineticRegistryHandle<Item> CONTROLLER = KineticItems.register(
            KineticResourceIds.of(MODID, "structure_controller"), () -> new Item(controllerProperties()));

    public StructureEditorModule() {
        dev.xyat.realmcontrol.structure.mega.MegaTypes.install();
        StructureEditorNetwork.register();
        StructureCommand.install();
        KineticCreativeTabs.onBuildContents(context -> {
            if (context.tabKey().equals(CreativeModeTabs.TOOLS_AND_UTILITIES) || context.tabKey().equals(CreativeModeTabs.OP_BLOCKS)) {
                context.accept(CONTROLLER.get());
            }
        });
        KineticServerEvents.onStarted(KineticEventPriority.NORMAL, SelectionStore::load);
        KineticServerEvents.onStopping(KineticEventPriority.NORMAL, server -> {
            ScanService.stop(server);
            dev.xyat.realmcontrol.structure.publish.TestPlacement.stop(server);
            SelectionStore.save(server);
        });
        KineticServerEvents.onTick(KineticEventPriority.NORMAL, KineticServerEvents.TickPhase.END, ScanService::tick);
        KineticServerEvents.onTick(KineticEventPriority.NORMAL, KineticServerEvents.TickPhase.END, dev.xyat.realmcontrol.structure.publish.TestPlacement::tick);
        KineticServerEvents.onPlayerLogin(KineticEventPriority.NORMAL, SelectionStore::sync);
        KineticServerEvents.onPlayerChangedDimension(KineticEventPriority.NORMAL, (player, from, to) -> SelectionStore.sync(player));
        KineticPlatform.runOnClient(() -> dev.xyat.realmcontrol.structure.client.ControllerClient::install);
    }

    private static Item.Properties controllerProperties() {
        Item.Properties properties = new Item.Properties().stacksTo(1);
        //? if >=26.1 {
        /*properties.setId(ResourceKey.create(Registries.ITEM, KineticResourceIds.of(MODID, "structure_controller")));
        *///?}
        return properties;
    }

    /** Whether a player may use the structure editor: game master permission. */
    public static boolean allowed(net.minecraft.server.level.ServerPlayer player) {
        return player.hasPermissions(2);
    }
}
