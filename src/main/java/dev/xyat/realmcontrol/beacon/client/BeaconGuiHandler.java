package dev.xyat.realmcontrol.beacon.client;

import dev.xyat.realmcontrol.beacon.util.ColorText;
import dev.xyat.realmcontrol.beacon.BeaconModule;
import dev.xyat.realmcontrol.beacon.config.BeaconConfig;
import dev.xyat.realmcontrol.beacon.config.BeaconConfigGui;
import dev.xyat.realmcontrol.beacon.mixin.LevelAccess;
import dev.xyat.realmcontrol.beacon.network.BeaconNetwork;
import dev.xyat.realmcontrol.beacon.util.IBeaconAccess;
import dev.xyat.kineticcore.api.client.overlay.GuiOverlay;
import dev.xyat.kineticcore.api.client.screen.KineticScreen;
import dev.xyat.kineticcore.api.client.widget.KineticWidgets;
import dev.xyat.kineticcore.config.client.KTConfigApi;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.BeaconScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.BeaconBlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = BeaconModule.MODID, value = Dist.CLIENT)
public class BeaconGuiHandler {

    @SubscribeEvent
    public static void onInitGui(ScreenEvent.Init.Post event) {
        if (event.getScreen() instanceof BeaconScreen screen) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null || !(mc.hitResult instanceof BlockHitResult blockHit)) return;

            BlockPos pos = blockHit.getBlockPos();
            if (!(mc.level.getBlockEntity(pos) instanceof BeaconBlockEntity beacon) || !(beacon instanceof IBeaconAccess accessor)) return;

            int guiLeft = (screen.width - 214) / 2;
            int guiTop = (screen.height - 108) / 2;
            int startX = guiLeft - 90;

            int maxRad = BeaconConfig.getBeaconRadius(((LevelAccess) beacon).realmcontrol_beacon$getLevels());
            int maxPreventRad = maxRad >= 0 ? maxRad + 1 : -1;

            boolean[] states = { accessor.realmcontrol_beacon$isChunkLoadEnabled(), accessor.realmcontrol_beacon$isSpawnPreventEnabled() };
            int[] typeState = { accessor.realmcontrol_beacon$getSpawnPreventType() };
            if (typeState[0] > 2) typeState[0] = 0; // 兼容旧配置清理

            Button clBtn = KineticWidgets.createButton(
                    startX, guiTop - 55, 80,
                    getToggleText(0, states[0]),
                    ColorText.translatable("gui.realmcontrol.beacon.beacon.tt.cl_btn"),
                    b -> {
                        states[0] = !states[0];
                        b.setMessage(getToggleText(0, states[0]));
                    }
            );

            KineticWidgets.ValidationEditBox clBox = KineticWidgets.createValidatingCompactTextField(
                    mc.font, startX, guiTop - 33, 80, Component.empty(),
                    ColorText.translatable("gui.realmcontrol.beacon.beacon.tt.cl_rad", maxRad)
            );
            clBox.setValue(accessor.realmcontrol_beacon$getChunkLoadRadius() == -1 ? "" : String.valueOf(accessor.realmcontrol_beacon$getChunkLoadRadius()));
            clBox.setFilter(value -> value.isEmpty() || value.matches("-?\\d+"));

            Button spBtn = KineticWidgets.createButton(
                    startX, guiTop - 17, 80,
                    getToggleText(1, states[1]),
                    ColorText.translatable("gui.realmcontrol.beacon.beacon.tt.sp_btn"),
                    b -> {
                        states[1] = !states[1];
                        b.setMessage(getToggleText(1, states[1]));
                    }
            );

            KineticWidgets.ValidationEditBox spBox = KineticWidgets.createValidatingCompactTextField(
                    mc.font, startX, guiTop + 5, 80, Component.empty(),
                    ColorText.translatable("gui.realmcontrol.beacon.beacon.tt.sp_rad", maxPreventRad)
            );
            spBox.setValue(accessor.realmcontrol_beacon$getSpawnPreventRadius() == -1 ? "" : String.valueOf(accessor.realmcontrol_beacon$getSpawnPreventRadius()));
            spBox.setFilter(value -> value.isEmpty() || value.matches("-?\\d+"));

            Button typeBtn = KineticWidgets.createButton(
                    startX, guiTop + 21, 80,
                    ColorText.translatable("gui.realmcontrol.beacon.beacon.btn_type", getTypeText(typeState[0])),
                    ColorText.translatable("gui.realmcontrol.beacon.beacon.tt.sp_target"),
                    b -> {
                        typeState[0] = (typeState[0] + 1) % 3;
                        b.setMessage(ColorText.translatable("gui.realmcontrol.beacon.beacon.btn_type", getTypeText(typeState[0])));
                    }
            );

            EditBox codeBox = KineticWidgets.createCompactTextField(
                    mc.font, startX, guiTop + 43, 80, Component.empty(),
                    ColorText.translatable("gui.realmcontrol.beacon.beacon.tt.sp_code")
            );
            codeBox.setMaxLength(32);
            codeBox.setValue(accessor.realmcontrol_beacon$getSpawnPreventCodes());
            codeBox.setFilter(value -> value.isEmpty() || value.matches("[a-hA-H]*"));
            codeBox.setResponder(value -> {
                if (!value.equals(value.toUpperCase())) {
                    codeBox.setValue(value.toUpperCase());
                }
            });

            Button applyBtn = KineticWidgets.createButton(
                    startX, guiTop + 59, 80,
                    ColorText.translatable("gui.realmcontrol.beacon.beacon.apply"),
                    ColorText.translatable("gui.realmcontrol.beacon.beacon.tt.apply"),
                    b -> {
                        int cr = -1, sr = -1;
                        boolean hasError = false;

                        if (!clBox.getValue().isEmpty() && !clBox.getValue().equals("-1")) {
                            try { cr = Integer.parseInt(clBox.getValue()); } catch (Exception ignored) { }
                            if (cr < -1 || cr > maxRad) {
                                clBox.showError();
                                hasError = true;
                                if (mc.player != null) mc.player.displayClientMessage(ColorText.translatable("msg.realmcontrol.beacon.beacon.error.cl", maxRad), false);
                            }
                        }

                        if (!spBox.getValue().isEmpty() && !spBox.getValue().equals("-1")) {
                            try { sr = Integer.parseInt(spBox.getValue()); } catch (Exception ignored) { }
                            if (sr < -1 || sr > maxPreventRad) {
                                spBox.showError();
                                hasError = true;
                                if (mc.player != null) mc.player.displayClientMessage(ColorText.translatable("msg.realmcontrol.beacon.beacon.error.sp", maxPreventRad), false);
                            }
                        }

                        if (hasError) return;

                        String cd = codeBox.getValue().toUpperCase();
                        BeaconNetwork.CHANNEL.sendToServer(new BeaconNetwork.BeaconConfigPacket(states[0], cr, states[1], sr, typeState[0], cd));
                    }
            );

            event.addListener(clBtn);
            event.addListener(clBox);
            event.addListener(spBtn);
            event.addListener(spBox);
            event.addListener(typeBtn);
            event.addListener(codeBox);
            event.addListener(applyBtn);
        }
    }

    public static void handleSaveResult(boolean success) {
        if (success) {
            KTConfigApi.notifySaved(BeaconConfigGui.PAGE_ID);
        } else {
            GuiOverlay.toast(Component.translatable("gui.kineticcore.config.save_failed"));
        }
    }

    private static Component getToggleText(int type, boolean state) {
        if (type == 0) {
            return ColorText.translatable(state ? "gui.realmcontrol.beacon.beacon.chunk_load.on" : "gui.realmcontrol.beacon.beacon.chunk_load.off");
        } else {
            return ColorText.translatable(state ? "gui.realmcontrol.beacon.beacon.spawn_prevent.on" : "gui.realmcontrol.beacon.beacon.spawn_prevent.off");
        }
    }

    private static Component getTypeText(int type) {
        return ColorText.translatable("gui.realmcontrol.beacon.beacon.type." + type);
    }
}
