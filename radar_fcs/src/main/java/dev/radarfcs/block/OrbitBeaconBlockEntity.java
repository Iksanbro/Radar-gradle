package dev.radarfcs.block;

import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Маяк орбиты: в радарной сети моста автопилота - центр орбиты, по которой корабль летает без цели (режим моста
 * «Орбита вокруг маяка»). Может стоять и на корабле - тогда орбита идёт за ним. Настроек нет: радиус, высота и
 * направление - в мосту.
 */
public class OrbitBeaconBlockEntity extends BlockEntity implements IHaveGoggleInformation {
    public OrbitBeaconBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        tooltip.add(Component.literal("    ").append(Component.translatable("block.radar_fcs.orbit_beacon")).withStyle(ChatFormatting.GOLD));
        tooltip.add(Component.literal("    ").append(Component.translatable("radar_fcs.orbit_beacon.goggles")).withStyle(ChatFormatting.GRAY));
        return true;
    }
}
