package dev.xyat.realmcontrol.structure.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Leaves this single-player world, saving it as Save and Quit does, and opens it again: structures, structure sets and
 * template pools are read only when a world loads, so a publish takes effect this way. Not on a server, and not while
 * the world is open to LAN (the others would be dropped).
 */
public final class WorldRejoin {
    private WorldRejoin() {
    }

    /** Whether this client hosts the world alone, so it can leave and enter it again. */
    public static boolean available() {
        IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
        return server != null && !server.isPublished();
    }

    public static void run() {
        Minecraft mc = Minecraft.getInstance();
        IntegratedServer server = mc.getSingleplayerServer();
        if (server == null || server.isPublished() || mc.level == null) return;
        // The world folder's name is the id the world list opens it by.
        String levelId = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().getFileName().toString();
        //? if >=26.1 {
        /*mc.disconnectFromWorld(net.minecraft.client.multiplayer.ClientLevel.DEFAULT_QUIT_MESSAGE);
        *///?} else if >=1.21 {
        /*mc.level.disconnect();
        mc.disconnect(new net.minecraft.client.gui.screens.GenericMessageScreen(dev.xyat.kineticcore.api.text.KineticI18n.translatable("menu.savingLevel")));
        *///?} else {
        mc.level.disconnect();
        mc.clearLevel(new net.minecraft.client.gui.screens.GenericDirtMessageScreen(dev.xyat.kineticcore.api.text.KineticI18n.translatable("menu.savingLevel")));
        //?}
        //? if >=1.21 {
        /*mc.createWorldOpenFlows().openWorld(levelId, () -> dev.xyat.kineticcore.api.runtime.KineticClientRuntime.openScreen(new TitleScreen()));
        *///?} else {
        mc.createWorldOpenFlows().loadLevel(new TitleScreen(), levelId);
        //?}
    }
}
