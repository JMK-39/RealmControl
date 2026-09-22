package dev.xyat.realmcontrol.beacon.client;

import dev.xyat.realmcontrol.beacon.util.ColorText;
import dev.xyat.realmcontrol.beacon.config.BeaconConfig;
import dev.xyat.realmcontrol.beacon.config.BeaconConfigGui;
import dev.xyat.realmcontrol.beacon.mixin.LevelAccess;
import dev.xyat.realmcontrol.beacon.network.BeaconNetwork;
import dev.xyat.realmcontrol.beacon.util.IBeaconAccess;
import dev.xyat.kineticcore.api.client.event.KineticClientEvents;
import dev.xyat.kineticcore.api.client.overlay.KineticOverlays;
import dev.xyat.kineticcore.api.client.widget.KineticWidgets;
import dev.xyat.kineticcore.api.client.widget.button.KineticButtons.StateButton;
import dev.xyat.kineticcore.api.client.widget.input.KineticTextFields.KineticEditBox;
import dev.xyat.kineticcore.api.config.client.KTConfigApi;
import dev.xyat.kineticcore.api.runtime.KineticClientRuntime;
import net.minecraft.client.gui.screens.inventory.BeaconScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.BeaconBlockEntity;
import net.minecraft.world.phys.BlockHitResult;

public final class BeaconGuiHandler {
    private static boolean installed;

    private BeaconGuiHandler() {
    }

    public static void install() {
        if (installed) return;
        installed = true;
        KineticClientEvents.onScreenInitAfter(BeaconGuiHandler::onInitGui);
    }

    private static void onInitGui(KineticClientEvents.ScreenInitContext event) {
        if (event.screen() instanceof BeaconScreen screen) {
            var level = KineticClientRuntime.currentLevel();
            if (level == null || !(KineticClientRuntime.hitResult() instanceof BlockHitResult blockHit)) return;

            BlockPos pos = blockHit.getBlockPos();
            if (!(level.getBlockEntity(pos) instanceof BeaconBlockEntity beacon) || !(beacon instanceof IBeaconAccess accessor)) return;

            int guiLeft = (screen.width - 214) / 2;
            int guiTop = (screen.height - 108) / 2;
            int startX = guiLeft - 90;

            int maxRad = BeaconConfig.getBeaconRadius(((LevelAccess) beacon).realmcontrol_beacon$getLevels());
            int maxPreventRad = maxRad >= 0 ? maxRad + 1 : -1;

            boolean[] states = { accessor.realmcontrol_beacon$isChunkLoadEnabled(), accessor.realmcontrol_beacon$isSpawnPreventEnabled() };
            int[] typeState = { accessor.realmcontrol_beacon$getSpawnPreventType() };
            if (typeState[0] > 2) typeState[0] = 0; // 兼容旧配置清理

            StateButton clBtn = KineticWidgets.createButtonWithHandler(
                    startX, guiTop - 55, 80,
                    getToggleText(0, states[0]),
                    ColorText.translatable("gui.realmcontrol.beacon.beacon.tt.cl_btn"),
                    b -> {
                        states[0] = !states[0];
                        b.setText(getToggleText(0, states[0]));
                    }
            );

            KineticEditBox clBox = KineticWidgets.createTextField(
                    KineticClientRuntime.font(), startX, guiTop - 33, 80, Component.empty(), null,
                    value -> value.isEmpty() || value.matches("-?\\d+"),
                    ColorText.translatable("gui.realmcontrol.beacon.beacon.tt.cl_rad", maxRad)
            );
            clBox.setValue(accessor.realmcontrol_beacon$getChunkLoadRadius() == -1 ? "" : String.valueOf(accessor.realmcontrol_beacon$getChunkLoadRadius()));

            StateButton spBtn = KineticWidgets.createButtonWithHandler(
                    startX, guiTop - 17, 80,
                    getToggleText(1, states[1]),
                    ColorText.translatable("gui.realmcontrol.beacon.beacon.tt.sp_btn"),
                    b -> {
                        states[1] = !states[1];
                        b.setText(getToggleText(1, states[1]));
                    }
            );

            KineticEditBox spBox = KineticWidgets.createTextField(
                    KineticClientRuntime.font(), startX, guiTop + 5, 80, Component.empty(), null,
                    value -> value.isEmpty() || value.matches("-?\\d+"),
                    ColorText.translatable("gui.realmcontrol.beacon.beacon.tt.sp_rad", maxPreventRad)
            );
            spBox.setValue(accessor.realmcontrol_beacon$getSpawnPreventRadius() == -1 ? "" : String.valueOf(accessor.realmcontrol_beacon$getSpawnPreventRadius()));

            StateButton typeBtn = KineticWidgets.createButtonWithHandler(
                    startX, guiTop + 21, 80,
                    ColorText.translatable("gui.realmcontrol.beacon.beacon.btn_type", getTypeText(typeState[0])),
                    ColorText.translatable("gui.realmcontrol.beacon.beacon.tt.sp_target"),
                    b -> {
                        typeState[0] = (typeState[0] + 1) % 3;
                        b.setText(ColorText.translatable("gui.realmcontrol.beacon.beacon.btn_type", getTypeText(typeState[0])));
                    }
            );

            KineticEditBox codeBox = KineticWidgets.createTextField(
                    KineticClientRuntime.font(), startX, guiTop + 43, 80, Component.empty(), null,
                    value -> value.isEmpty() || value.matches("[a-hA-H]*"),
                    ColorText.translatable("gui.realmcontrol.beacon.beacon.tt.sp_code")
            );
            codeBox.setMaxLength(32);
            codeBox.setValue(accessor.realmcontrol_beacon$getSpawnPreventCodes());
            codeBox.setResponder(value -> {
                if (!value.equals(value.toUpperCase())) {
                    codeBox.setValue(value.toUpperCase());
                }
            });

            StateButton applyBtn = KineticWidgets.createButton(
                    startX, guiTop + 59, 80,
                    ColorText.translatable("gui.realmcontrol.beacon.beacon.apply"),
                    ColorText.translatable("gui.realmcontrol.beacon.beacon.tt.apply"),
                    () -> {
                        int cr = -1, sr = -1;
                        boolean hasError = false;

                        if (!clBox.getValue().isEmpty() && !clBox.getValue().equals("-1")) {
                            try { cr = Integer.parseInt(clBox.getValue()); } catch (Exception ignored) { }
                            if (cr < -1 || cr > maxRad) {
                                clBox.flashValidationError();
                                hasError = true;
                                KineticClientRuntime.displayClientMessage(ColorText.translatable("msg.realmcontrol.beacon.beacon.error.cl", maxRad), false);
                            }
                        }

                        if (!spBox.getValue().isEmpty() && !spBox.getValue().equals("-1")) {
                            try { sr = Integer.parseInt(spBox.getValue()); } catch (Exception ignored) { }
                            if (sr < -1 || sr > maxPreventRad) {
                                spBox.flashValidationError();
                                hasError = true;
                                KineticClientRuntime.displayClientMessage(ColorText.translatable("msg.realmcontrol.beacon.beacon.error.sp", maxPreventRad), false);
                            }
                        }

                        if (hasError) return;

                        String cd = codeBox.getValue().toUpperCase();
                        BeaconNetwork.CHANNEL.sendToServer(new BeaconNetwork.BeaconConfigPacket(states[0], cr, states[1], sr, typeState[0], cd));
                    }
            );

            event.addControl(clBtn);
            event.addControl(clBox);
            event.addControl(spBtn);
            event.addControl(spBox);
            event.addControl(typeBtn);
            event.addControl(codeBox);
            event.addControl(applyBtn);
        }
    }

    public static void handleSaveResult(boolean success) {
        if (success) {
            KTConfigApi.notifySaved(BeaconConfigGui.PAGE_ID);
        } else {
            KineticOverlays.toast(Component.translatable("gui.kineticcore.config.save_failed"));
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
