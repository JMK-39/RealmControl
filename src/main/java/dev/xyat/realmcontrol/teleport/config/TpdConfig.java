package dev.xyat.realmcontrol.teleport.config;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.core.io.WritingMode;
import dev.xyat.realmcontrol.teleport.TeleportModule;
import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class TpdConfig {
    private static final Path CONFIG_PATH = FMLPaths.CONFIGDIR.get().resolve("kineticcore/teleport.toml");
    private static CommentedFileConfig configData;

    public static boolean enableTpModify = true;
    public static boolean adminTpBypass = true;
    public static String tpMode = "AUTHORIZED";
    public static String tpDenyCustomMessage = "";

    private TpdConfig() {
    }

    public static void load() {
        try {
            if (configData == null) {
                configData = CommentedFileConfig.builder(CONFIG_PATH)
                        .sync()
                        .preserveInsertionOrder()
                        .writingMode(WritingMode.REPLACE)
                        .build();
            }
            configData.load();
            setupConfig();
            configData.save();
            readValues();
        } catch (Exception exception) {
            TeleportModule.LOGGER.error("TpdConfig Load Failed", exception);
            if (configData != null) {
                try {
                    configData.close();
                } catch (Exception closeException) {
                    TeleportModule.LOGGER.debug("Failed to close broken TPD config", closeException);
                }
                configData = null;
            }
        }
    }

    private static void setupConfig() {
        configData.setComment("teleport", """
         传送功能增强设置 (影响 /tp 和 /teleport 指令)。
         Teleport Command Enhancement (affects /tp and /teleport commands).
         提供了基于权限和授权的传送逻辑控制。
         Provides permission-based and authorized teleport logic control.""");

        define("teleport.enableModify", true, """
         是否修改并增强原版的 /tp 指令功能。
         Whether to modify and enhance the vanilla /tp command functionality.
         若设为 false，则下方所有传送限制将失效。
         If set to false, all teleport restrictions below will be disabled.""");

        define("teleport.adminBypass", true, """
         管理员 (权限等级 2) 是否豁免传送授权限制。
         Whether admins (Permission Level 2) bypass teleport authorization restrictions.
         True: 管理员无需购买次数即可传送。
         True: Admins can teleport without purchasing counts.
         False: 管理员也需要像普通玩家一样购买授权 (权限等级 3/4 的超级管理员始终豁免)。
         False: Admins also need authorization like normal players (Level 3/4 super admins always bypass).""");

        define("teleport.mode", "AUTHORIZED", """
         传送限制模式。
         Teleport Restriction Mode.
         FREE: 自由模式，任何人都可以相互传送。
         FREE: Everyone can teleport freely.
         AUTHORIZED: 授权模式，普通玩家需要获得次数或限时授权。
         AUTHORIZED: Normal players need count-based or timed authorization.""");

        define("teleport.denyCustomMessage", "", """
         当玩家被拒绝传送时显示的自定义提示消息。
         Custom message displayed when a player is denied teleporting.
         可使用 & 颜色代码和 {player} 玩家名占位符；留空使用语言文件默认提示。
         Supports & color codes and the {player} placeholder; leave empty to use the translated default message.""");
    }

    private static void define(String path, Object defaultValue, String comment) {
        if (!configData.contains(path)) configData.set(path, defaultValue);
        configData.setComment(path, " " + comment.trim());
    }

    private static void readValues() {
        enableTpModify = configData.getOrElse("teleport.enableModify", true);
        adminTpBypass = configData.getOrElse("teleport.adminBypass", true);
        tpMode = configData.getOrElse("teleport.mode", "AUTHORIZED");
        tpDenyCustomMessage = configData.getOrElse("teleport.denyCustomMessage", "");
    }

    public static void setTpMode(String value) {
        tpMode = "FREE".equalsIgnoreCase(value) ? "FREE" : "AUTHORIZED";
    }

    public static void setDenyCustomMessage(String value) {
        String message = value == null ? "" : value;
        tpDenyCustomMessage = message.length() <= 512 ? message : message.substring(0, 512);
    }

    public static void save() {
        if (configData == null) {
            throw new IllegalStateException("Teleport config is not loaded");
        }

        Path backupPath = CONFIG_PATH.resolveSibling(CONFIG_PATH.getFileName() + ".save-backup");
        boolean hadOriginal = Files.exists(CONFIG_PATH);
        try {
            if (hadOriginal) {
                Files.copy(CONFIG_PATH, backupPath, StandardCopyOption.REPLACE_EXISTING);
            }

            configData.set("teleport.enableModify", enableTpModify);
            configData.set("teleport.adminBypass", adminTpBypass);
            configData.set("teleport.mode", tpMode);
            configData.set("teleport.denyCustomMessage", tpDenyCustomMessage);
            configData.save();
            try {
                Files.deleteIfExists(backupPath);
            } catch (Exception cleanupException) {
                TeleportModule.LOGGER.debug("Failed to remove teleport config save backup", cleanupException);
            }
        } catch (Exception exception) {
            restoreAfterFailedSave(backupPath, hadOriginal);
            throw new IllegalStateException("Failed to save teleport config", exception);
        }
    }

    private static void restoreAfterFailedSave(Path backupPath, boolean hadOriginal) {
        try {
            if (configData != null) {
                configData.close();
            }
        } catch (Exception closeException) {
            TeleportModule.LOGGER.debug("Failed to close teleport config before rollback", closeException);
        }
        configData = null;

        try {
            if (hadOriginal && Files.exists(backupPath)) {
                Files.move(backupPath, CONFIG_PATH, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.deleteIfExists(backupPath);
            }
        } catch (Exception restoreFileException) {
            TeleportModule.LOGGER.error("Failed to restore teleport config file after save failure", restoreFileException);
        }

        try {
            configData = CommentedFileConfig.builder(CONFIG_PATH)
                    .sync()
                    .preserveInsertionOrder()
                    .writingMode(WritingMode.REPLACE)
                    .build();
            configData.load();
            readValues();
        } catch (Exception reloadException) {
            TeleportModule.LOGGER.error("Failed to reload teleport config after save failure", reloadException);
            if (configData != null) {
                try {
                    configData.close();
                } catch (Exception ignored) {
                }
                configData = null;
            }
        }
    }
}
