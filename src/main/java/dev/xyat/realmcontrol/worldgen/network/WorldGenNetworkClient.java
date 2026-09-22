package dev.xyat.realmcontrol.worldgen.network;

import dev.xyat.kineticcore.api.client.overlay.KineticOverlays;
import dev.xyat.kineticcore.api.runtime.KineticClientRuntime;
import dev.xyat.realmcontrol.worldgen.client.gui.BiomeControlScreen;
import dev.xyat.realmcontrol.worldgen.client.gui.WorldGenScreen;
import dev.xyat.realmcontrol.worldgen.data.StructureRuleDescriptor;
import net.minecraft.network.chat.Component;

import java.util.List;

public class WorldGenNetworkClient {
    public static void handleOpenGui(WorldGenNetwork.OpenWorldGenGuiPacket packet) {
        KineticClientRuntime.openScreen(new WorldGenScreen(packet, KineticClientRuntime.currentScreen()));
    }

    public static void handleSaveResult(boolean success) {
        if (KineticClientRuntime.currentScreen() instanceof WorldGenScreen screen) {
            screen.handleSaveResult(success);
        } else if (KineticClientRuntime.currentScreen() instanceof BiomeControlScreen screen) {
            screen.handleSaveResult(success);
        }
    }

    public static void handleOpenBiomeControl(WorldGenNetwork.OpenBiomeControlPacket packet) {
        KineticClientRuntime.openScreen(new BiomeControlScreen(packet, KineticClientRuntime.currentScreen()));
    }

    public static void handleStructureActionResult(Component message) {
        if (message != null) {
            KineticOverlays.toast(message);
        }
    }

    public static void handleStructureRegistry(List<String> structures, List<StructureRuleDescriptor> descriptors) {
        if (KineticClientRuntime.currentScreen() instanceof WorldGenScreen screen) {
            screen.handleStructureRegistryRefresh(structures, descriptors);
        }
    }
}
