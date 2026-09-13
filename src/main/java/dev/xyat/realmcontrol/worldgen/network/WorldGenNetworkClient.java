package dev.xyat.realmcontrol.worldgen.network;

import dev.xyat.kineticcore.api.client.overlay.GuiOverlay;
import dev.xyat.realmcontrol.worldgen.client.gui.BiomeControlScreen;
import dev.xyat.realmcontrol.worldgen.client.gui.WorldGenScreen;
import dev.xyat.realmcontrol.worldgen.data.StructureRuleDescriptor;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.List;

@OnlyIn(Dist.CLIENT)
public class WorldGenNetworkClient {
    public static void handleOpenGui(WorldGenNetwork.OpenWorldGenGuiPacket packet) {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.setScreen(new WorldGenScreen(packet, minecraft.screen));
    }

    public static void handleSaveResult(boolean success) {
        if (Minecraft.getInstance().screen instanceof WorldGenScreen screen) {
            screen.handleSaveResult(success);
        } else if (Minecraft.getInstance().screen instanceof BiomeControlScreen screen) {
            screen.handleSaveResult(success);
        }
    }

    public static void handleOpenBiomeControl(WorldGenNetwork.OpenBiomeControlPacket packet) {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.setScreen(new BiomeControlScreen(packet, minecraft.screen));
    }

    public static void handleStructureActionResult(Component message) {
        if (message != null) {
            GuiOverlay.toast(message);
        }
    }

    public static void handleStructureRegistry(List<String> structures, List<StructureRuleDescriptor> descriptors) {
        if (Minecraft.getInstance().screen instanceof WorldGenScreen screen) {
            screen.handleStructureRegistryRefresh(structures, descriptors);
        }
    }
}
