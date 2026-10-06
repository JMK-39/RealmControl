//? if >=1.21 {
/*package dev.xyat.realmcontrolvalidation;

import dev.xyat.realmcontrol.beacon.util.IBeaconAccess;
import dev.xyat.realmcontrol.worldblock.config.WorldBlockConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BeaconBlockEntity;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.fml.common.Mod;

@Mod("realmcontrol_validation")
public final class RuntimeValidation {
    public RuntimeValidation() {
        if (Boolean.getBoolean("realmcontrol.guiValidation")) {
            dev.xyat.kineticcore.api.runtime.KineticPlatform.runOnClient(() -> GuiCaptureValidation::install);
            return;
        }
        // Item components are bound once registries freeze (after mod construction on 26.1), so validate on server start.
        MinecraftForge.EVENT_BUS.addListener((ServerStartedEvent event) -> validate());
    }

    private static void validate() {
        SavedDataRegression.main(new String[0]);
        var actual = WorldBlockConfig.parseItemStack("minecraft:diamond_sword[damage=5,custom_data={extra:1}]");
        SavedDataRegression.require(!actual.isEmpty(), "component stack parsed");
        SavedDataRegression.require(new WorldBlockConfig.ItemRule("minecraft:diamond_sword{Damage:5}").matches(actual),
                "legacy damage rule matches component item");
        SavedDataRegression.require(new WorldBlockConfig.ItemRule("minecraft:diamond_sword[damage=5]").matches(actual),
                "component subset matches");
        SavedDataRegression.require(!new WorldBlockConfig.ItemRule("minecraft:diamond_sword[damage=6]").matches(actual),
                "different damage rejected");
        SavedDataRegression.require(!new WorldBlockConfig.ItemRule("minecraft:diamond_sword[damage=0]").matches(actual),
                "explicit default component remains a constraint");
        SavedDataRegression.require(!new WorldBlockConfig.ItemRule("minecraft:diamond_sword{Damage:0}").matches(actual),
                "legacy default damage remains a constraint");
        SavedDataRegression.require(!new WorldBlockConfig.ItemRule("minecraft:diamond_sword[!custom_data]").matches(actual),
                "explicit component removal remains a constraint");
        SavedDataRegression.require(new WorldBlockConfig.ItemRule("minecraft:diamond_sword{extra:1}").matches(actual),
                "legacy custom NBT upgraded and matched");
        SavedDataRegression.require("minecraft:diamond_sword".equals(WorldBlockConfig.getBaseIdentifier(
                "minecraft:diamond_sword[damage=5]")), "component base ID");
        // Item text only takes this version's component syntax, so stacks with item data are built directly;
        // the rules below still use the 1.20.1 syntax that existing configs carry.
        var gunData = new net.minecraft.nbt.CompoundTag();
        gunData.putString("GunId", "sample:test");
        gunData.putInt("Ammo", 12);
        var gun = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STICK);
        gun.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.of(gunData));
        String identity = WorldBlockConfig.getItemIdentifier(gun);
        SavedDataRegression.require(identity.contains("GunId") && !identity.contains("Ammo"), "stable legacy GunId identity");
        SavedDataRegression.require(new WorldBlockConfig.ItemRule(identity).matches(gun), "GunId rule matches extra data");
        String namedRule = "minecraft:stick{display:{Name:'{\"text\":\"Example\"}'}}";
        var named = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STICK);
        named.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("Example"));
        SavedDataRegression.require(new WorldBlockConfig.ItemRule(namedRule).matches(named),
                "legacy custom name matches after JSON normalization");
        String loreRule = "minecraft:stick{display:{Lore:['{\"text\":\"Line\"}']}}";
        var extraLore = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STICK);
        extraLore.set(net.minecraft.core.component.DataComponents.LORE, new net.minecraft.world.item.component.ItemLore(java.util.List.of(
                net.minecraft.network.chat.Component.literal("Line"), net.minecraft.network.chat.Component.literal("Extra"))));
        SavedDataRegression.require(new WorldBlockConfig.ItemRule(loreRule).matches(extraLore),
                "legacy lore subset matches after JSON normalization");
        SavedDataRegression.require(!new WorldBlockConfig.ItemRule(
                "minecraft:stick{display:{Name:'{\"text\":\"Different\"}'}}").matches(named),
                "different legacy name rejected");
        SavedDataRegression.require(WorldBlockConfig.parseItemStack("minecraft:stick[damage=invalid]").isEmpty(),
                "malformed component rejected");

        var lookup = net.minecraft.data.registries.VanillaRegistries.createLookup();
        var enchantments = lookup.lookup(net.minecraft.core.registries.Registries.ENCHANTMENT).orElseThrow();
        var sharpness = enchantments.getOrThrow(net.minecraft.world.item.enchantment.Enchantments.SHARPNESS);
        var unbreaking = enchantments.getOrThrow(net.minecraft.world.item.enchantment.Enchantments.UNBREAKING);
        var wanted = new net.minecraft.world.item.enchantment.ItemEnchantments.Mutable(
                net.minecraft.world.item.enchantment.ItemEnchantments.EMPTY);
        wanted.set(sharpness, 1);
        var extra = new net.minecraft.world.item.enchantment.ItemEnchantments.Mutable(wanted.toImmutable());
        extra.set(unbreaking, 3);
        var enchanted = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND_SWORD);
        enchanted.set(net.minecraft.core.component.DataComponents.ENCHANTMENTS, extra.toImmutable());
        SavedDataRegression.require(new WorldBlockConfig.ItemRule("minecraft:diamond_sword{Enchantments:[{id:\"minecraft:sharpness\"}]}").matches(enchanted),
                "partial legacy enchantment rule accepts matching ID at any level");
        SavedDataRegression.require(!new WorldBlockConfig.ItemRule("minecraft:diamond_sword{Enchantments:[{id:\"minecraft:smite\"}]}").matches(enchanted),
                "partial legacy enchantment rule rejects absent ID");
        SavedDataRegression.require(new WorldBlockConfig.ItemRule("minecraft:diamond_sword{Enchantments:[{id:\"minecraft:sharpness\",lvl:1s}]}").matches(enchanted),
                "legacy enchantment subset accepts extra enchantments");
        extra.set(sharpness, 2);
        enchanted.set(net.minecraft.core.component.DataComponents.ENCHANTMENTS, extra.toImmutable());
        SavedDataRegression.require(!new WorldBlockConfig.ItemRule("minecraft:diamond_sword{Enchantments:[{id:\"minecraft:sharpness\",lvl:1s}]}").matches(enchanted),
                "legacy enchantment level remains exact");
        var pristine = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND_SWORD);
        SavedDataRegression.require(new WorldBlockConfig.ItemRule("minecraft:diamond_sword[damage=0,!custom_data]").matches(pristine),
                "explicit defaults and removal accept a pristine stack");
        SavedDataRegression.require(new WorldBlockConfig.ItemRule("minecraft:diamond_sword{Damage:0,RepairCost:0}").matches(pristine),
                "legacy defaults accept a pristine stack");
        for (String malformed : java.util.List.of("Damage:\"x\"", "RepairCost:\"x\"", "Unbreakable:\"yes\"")) {
            SavedDataRegression.require(!new WorldBlockConfig.ItemRule("minecraft:diamond_sword{" + malformed + "}").matches(pristine),
                    "legacy scalar type mismatch rejected: " + malformed);
        }
        pristine.set(net.minecraft.core.component.DataComponents.REPAIR_COST, 3);
        SavedDataRegression.require(!new WorldBlockConfig.ItemRule("minecraft:diamond_sword{RepairCost:0}").matches(pristine),
                "legacy repair cost remains a constraint");

        var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        var beacon = new BeaconBlockEntity(BlockPos.ZERO, Blocks.BEACON.defaultBlockState());
        IBeaconAccess access = (IBeaconAccess) beacon;
        var owner = new java.util.UUID(123, 456);
        access.realmcontrol_beacon$setOwner(owner);
        access.realmcontrol_beacon$setChunkLoadEnabled(true);
        access.realmcontrol_beacon$setChunkLoadRadius(4);
        access.realmcontrol_beacon$setSpawnPreventCodes("minecraft:zombie");
        var tag = beacon.saveWithoutMetadata(registries);
        var restored = new BeaconBlockEntity(BlockPos.ZERO, Blocks.BEACON.defaultBlockState());
        restored.loadWithComponents(tag, registries);
        IBeaconAccess loaded = (IBeaconAccess) restored;
        SavedDataRegression.require(owner.equals(loaded.realmcontrol_beacon$getOwner())
                && loaded.realmcontrol_beacon$isChunkLoadEnabled()
                && loaded.realmcontrol_beacon$getChunkLoadRadius() == 4
                && loaded.realmcontrol_beacon$getSpawnPreventCodes().equals("minecraft:zombie"), "actual beacon Mixin NBT round trip");
        com.mojang.logging.LogUtils.getLogger().info("REALMCONTROL_RUNTIME_VALIDATION_PASS: legacy/component item rules, beacon Mixins and saved data");
    }
}
*///?}
