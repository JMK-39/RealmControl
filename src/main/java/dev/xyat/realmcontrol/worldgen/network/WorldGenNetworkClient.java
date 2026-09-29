package dev.xyat.realmcontrol.worldgen.network;

import dev.xyat.kineticcore.api.client.gui.overlay.KineticOverlays;
import dev.xyat.kineticcore.api.client.gui.KineticGui;
import dev.xyat.realmcontrol.worldgen.client.gui.BiomeControlPage;
import dev.xyat.realmcontrol.worldgen.client.gui.WorldGenPage;
import dev.xyat.realmcontrol.worldgen.data.StructureRuleDescriptor;
import net.minecraft.network.chat.Component;

import java.util.List;

public class WorldGenNetworkClient {
    public static void handleOpenGui(WorldGenNetwork.OpenWorldGenGuiPacket packet) {
        KineticGui.openChild(new WorldGenPage(packet));
    }

    public static void handleSaveResult(boolean success) {
        if (KineticGui.currentPage() instanceof WorldGenPage page) {
            page.handleSaveResult(success);
        } else if (KineticGui.currentPage() instanceof BiomeControlPage page) {
            page.handleSaveResult(success);
        }
    }

    public static void handleOpenBiomeControl(WorldGenNetwork.OpenBiomeControlPacket packet) {
        KineticGui.openChild(new BiomeControlPage(packet));
    }

    public static void handleStructureActionResult(Component message) {
        if (message != null) {
            KineticOverlays.toast(message);
        }
    }

    public static void handleStructureRegistry(List<String> structures, List<StructureRuleDescriptor> descriptors) {
        WorldGenPage page = KineticGui.currentPage(WorldGenPage.class);
        if (page != null) {
            page.handleStructureRegistryRefresh(structures, descriptors);
        }
    }
}
