package dev.aeroschematics.nbt;

import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.StringTag;

/** Helpers producing NBT exactly in the shape the mods write it (Minecraft 1.21.1 codecs). */
final class TestNbt {
    static final Set<String> ITEMS = Set.of(
            "minecraft:stone", "minecraft:white_wool", "minecraft:redstone_torch", "minecraft:item_frame", "minecraft:diamond_chestplate",
            "createbigcannons:he_shell", "createbigcannons:ap_shell", "createbigcannons:powder_charge", "createbigcannons:impact_fuze",
            "createbigcannons:timed_fuze", "createbigcannons:autocannon_cartridge", "createbigcannons:ap_autocannon_round",
            "createbigcannons:tracer_tip", "createbigcannons:autocannon_cartridge_sheet", "createbigcannons:big_cartridge",
            "cbc_firepower_components:large_autocannon_ap_round", "cbc_firepower_components:large_autocannon_he_round",
            "create:linked_controller", "create:redstone_link", "create:andesite_alloy", "create:wrench",
            "create_flight_control:navigation_profile", "drivebywire:wire", "create_afn:frequency_card", "offroad:tire");
    static final Set<String> FLUIDS = Set.of("minecraft:water", "minecraft:lava", "create_flight_control:kerosene", "createbigcannons:molten_cast_iron");

    static final NbtSanitizer.Lookup LOOKUP = new NbtSanitizer.Lookup() {
        @Override
        public boolean isItem(String id) {
            return ITEMS.contains(id);
        }

        @Override
        public boolean isFluid(String id) {
            return FLUIDS.contains(id);
        }
    };

    static NbtSanitizer sanitizer() {
        return new NbtSanitizer(SanitizerRules.defaults(), LOOKUP);
    }

    private TestNbt() {
    }

    static CompoundTag stack(String id, int count) {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", id);
        tag.putInt("count", count);
        return tag;
    }

    static CompoundTag stackWithComponent(String id, int count, String componentKey, CompoundTag component) {
        CompoundTag tag = stack(id, count);
        CompoundTag components = new CompoundTag();
        components.put(componentKey, component);
        tag.put("components", components);
        return tag;
    }

    static CompoundTag fluid(String id, int amount) {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", id);
        tag.putInt("amount", amount);
        return tag;
    }

    static CompoundTag slotted(int slot, CompoundTag stack) {
        CompoundTag tag = stack.copy();
        tag.putByte("Slot", (byte) slot);
        return tag;
    }

    static CompoundTag blockState(String name) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Name", name);
        CompoundTag props = new CompoundTag();
        props.putString("facing", "north");
        tag.put("Properties", props);
        return tag;
    }

    static ListTag strings(String... values) {
        ListTag list = new ListTag();
        for (String value : values) {
            list.add(StringTag.valueOf(value));
        }
        return list;
    }

    static LongTag pos(BlockPos pos) {
        return LongTag.valueOf(pos.asLong());
    }

    static CompoundTag kineticRuntime(CompoundTag tag, BlockPos source) {
        tag.putFloat("Speed", 64f);
        tag.put("Source", NbtUtils.writeBlockPos(source));
        CompoundTag network = new CompoundTag();
        network.putLong("Id", 12345L);
        network.putFloat("Stress", 8f);
        network.putFloat("Capacity", 256f);
        tag.put("Network", network);
        return tag;
    }
}
