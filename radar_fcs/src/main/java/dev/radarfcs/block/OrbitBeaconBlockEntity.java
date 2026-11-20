package dev.radarfcs.block;

import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Маяк орбиты: центр орбиты, по которой корабль летает без цели (режим моста «орбита вокруг маяка»). Маяк - в
 * радарной сети моста (передатчиком); мост с кодом берёт маяк сети с этим кодом, без кода - первый маяк сети.
 * Может стоять и на корабле - тогда орбита идёт за ним. Радиус, высота и направление - в мосту.
 */
public class OrbitBeaconBlockEntity extends BlockEntity implements IHaveGoggleInformation {
    private String code = "";

    public OrbitBeaconBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    public String code() {
        return code;
    }

    public void setCode(String c) {
        code = cleanCode(c);
        setChanged();
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }

    /** Код маяка: без пробелов по краям и служебных символов, не длиннее 16. */
    public static String cleanCode(@org.jetbrains.annotations.Nullable String c) {
        if (c == null) return "";
        String s = c.replaceAll("\\p{Cntrl}", "").trim();
        return s.length() > 16 ? s.substring(0, 16) : s;
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putString("Code", code);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        code = cleanCode(tag.getString("Code"));
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag t = super.getUpdateTag(registries);
        t.putString("Code", code);
        return t;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        tooltip.add(Component.literal("    ").append(Component.translatable("block.radar_fcs.orbit_beacon")).withStyle(ChatFormatting.GOLD));
        tooltip.add(Component.literal("    ").append(code.isEmpty() ? Component.translatable("radar_fcs.orbit_beacon.no_code")
                : Component.translatable("radar_fcs.orbit_beacon.code", code)).withStyle(ChatFormatting.AQUA));
        tooltip.add(Component.literal("    ").append(Component.translatable("radar_fcs.orbit_beacon.goggles")).withStyle(ChatFormatting.GRAY));
        return true;
    }
}
