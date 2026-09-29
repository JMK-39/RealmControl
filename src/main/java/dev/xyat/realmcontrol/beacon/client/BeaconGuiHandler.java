package dev.xyat.realmcontrol.beacon.client;

import dev.xyat.realmcontrol.beacon.config.BeaconConfig;
import dev.xyat.realmcontrol.beacon.config.BeaconConfigGui;
import dev.xyat.realmcontrol.beacon.mixin.LevelAccess;
import dev.xyat.realmcontrol.beacon.network.BeaconNetwork;
import dev.xyat.realmcontrol.beacon.util.IBeaconAccess;
import dev.xyat.kineticcore.api.client.event.KineticClientEvents;
import dev.xyat.kineticcore.api.client.gui.overlay.KineticOverlays;
import dev.xyat.kineticcore.api.client.gui.widget.KineticButton;
import dev.xyat.kineticcore.api.client.gui.widget.KineticTextField;
import dev.xyat.kineticcore.api.config.client.KTConfigApi;
import dev.xyat.kineticcore.api.runtime.KineticClientRuntime;
import dev.xyat.kineticcore.api.text.KineticI18n;
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

            KineticButton[] clBtn = new KineticButton[1];
            clBtn[0] = event.addButton(startX, guiTop - 55, 80,
                    getToggleText(0, states[0]), () -> {
                        states[0] = !states[0];
                        clBtn[0].setText(getToggleText(0, states[0]));
                    });
            clBtn[0].setTooltip(KineticI18n.translatable("gui.realmcontrol.beacon.beacon.tt.cl_btn"));

            KineticTextField clBox = event.addTextField(startX, guiTop - 33, 80);
            clBox.filterText(value -> value.isEmpty() || value.matches("-?\\d+"));
            clBox.setTooltip(KineticI18n.translatable("gui.realmcontrol.beacon.beacon.tt.cl_rad", maxRad));
            clBox.setTextValue(accessor.realmcontrol_beacon$getChunkLoadRadius() == -1 ? "" : String.valueOf(accessor.realmcontrol_beacon$getChunkLoadRadius()));
            clBox.setDefaultText(clBox.textValue());

            KineticButton[] spBtn = new KineticButton[1];
            spBtn[0] = event.addButton(startX, guiTop - 17, 80,
                    getToggleText(1, states[1]), () -> {
                        states[1] = !states[1];
                        spBtn[0].setText(getToggleText(1, states[1]));
                    });
            spBtn[0].setTooltip(KineticI18n.translatable("gui.realmcontrol.beacon.beacon.tt.sp_btn"));

            KineticTextField spBox = event.addTextField(startX, guiTop + 5, 80);
            spBox.filterText(value -> value.isEmpty() || value.matches("-?\\d+"));
            spBox.setTooltip(KineticI18n.translatable("gui.realmcontrol.beacon.beacon.tt.sp_rad", maxPreventRad));
            spBox.setTextValue(accessor.realmcontrol_beacon$getSpawnPreventRadius() == -1 ? "" : String.valueOf(accessor.realmcontrol_beacon$getSpawnPreventRadius()));
            spBox.setDefaultText(spBox.textValue());

            KineticButton[] typeBtn = new KineticButton[1];
            typeBtn[0] = event.addButton(startX, guiTop + 21, 80,
                    KineticI18n.translatable("gui.realmcontrol.beacon.beacon.btn_type", getTypeText(typeState[0])), () -> {
                        typeState[0] = (typeState[0] + 1) % 3;
                        typeBtn[0].setText(KineticI18n.translatable("gui.realmcontrol.beacon.beacon.btn_type", getTypeText(typeState[0])));
                    });
            typeBtn[0].setTooltip(KineticI18n.translatable("gui.realmcontrol.beacon.beacon.tt.sp_target"));

            KineticTextField codeBox = event.addTextField(startX, guiTop + 43, 80);
            codeBox.filterText(value -> value.isEmpty() || value.matches("[a-hA-H]*"));
            codeBox.setTooltip(KineticI18n.translatable("gui.realmcontrol.beacon.beacon.tt.sp_code"));
            codeBox.limitTextLength(32);
            codeBox.setTextValue(accessor.realmcontrol_beacon$getSpawnPreventCodes());
            codeBox.setDefaultText(codeBox.textValue());
            codeBox.onTextChange(value -> {
                if (!value.equals(value.toUpperCase())) {
                    codeBox.setTextValue(value.toUpperCase());
                }
            });

            KineticButton applyBtn = event.addButton(startX, guiTop + 59, 80,
                    KineticI18n.translatable("gui.realmcontrol.beacon.beacon.apply"), () -> {
                        int cr = -1, sr = -1;
                        boolean hasError = false;

                        if (!clBox.textValue().isEmpty() && !clBox.textValue().equals("-1")) {
                            try { cr = Integer.parseInt(clBox.textValue()); } catch (Exception ignored) { }
                            if (cr < -1 || cr > maxRad) {
                                clBox.flashValidationError();
                                hasError = true;
                                KineticClientRuntime.displayClientMessage(KineticI18n.translatable("msg.realmcontrol.beacon.beacon.error.cl", maxRad), false);
                            }
                        }

                        if (!spBox.textValue().isEmpty() && !spBox.textValue().equals("-1")) {
                            try { sr = Integer.parseInt(spBox.textValue()); } catch (Exception ignored) { }
                            if (sr < -1 || sr > maxPreventRad) {
                                spBox.flashValidationError();
                                hasError = true;
                                KineticClientRuntime.displayClientMessage(KineticI18n.translatable("msg.realmcontrol.beacon.beacon.error.sp", maxPreventRad), false);
                            }
                        }

                        if (hasError) return;

                        String cd = codeBox.textValue().toUpperCase();
                        BeaconNetwork.CHANNEL.sendToServer(new BeaconNetwork.BeaconConfigPacket(states[0], cr, states[1], sr, typeState[0], cd));
                    });
            applyBtn.setTooltip(KineticI18n.translatable("gui.realmcontrol.beacon.beacon.tt.apply"));
        }
    }

    public static void handleSaveResult(boolean success) {
        if (success) {
            KTConfigApi.notifySaved(BeaconConfigGui.PAGE_ID);
        } else {
            KineticOverlays.toast(KineticI18n.translatable("gui.kineticcore.config.save_failed"));
        }
    }

    private static Component getToggleText(int type, boolean state) {
        if (type == 0) {
            return KineticI18n.translatable(state ? "gui.realmcontrol.beacon.beacon.chunk_load.on" : "gui.realmcontrol.beacon.beacon.chunk_load.off");
        } else {
            return KineticI18n.translatable(state ? "gui.realmcontrol.beacon.beacon.spawn_prevent.on" : "gui.realmcontrol.beacon.beacon.spawn_prevent.off");
        }
    }

    private static Component getTypeText(int type) {
        return KineticI18n.translatable("gui.realmcontrol.beacon.beacon.type." + type);
    }
}
