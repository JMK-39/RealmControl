//? if <1.21 {
package dev.xyat.realmcontrolvalidation;

/** Forge 1.20.1 entry of the GUI capture: it runs inside an installed modpack, where the 1.21 checks cannot. */
@net.minecraftforge.fml.common.Mod("realmcontrol_gui_capture")
public final class GuiCaptureForgeEntry {
    public GuiCaptureForgeEntry() {
        if (Boolean.getBoolean("realmcontrol.guiValidation")) {
            dev.xyat.kineticcore.api.runtime.KineticPlatform.runOnClient(() -> GuiCaptureValidation::install);
        }
    }
}
//?}
