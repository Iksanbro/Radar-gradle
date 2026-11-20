package dev.radarfcs.block;

import com.simibubi.create.content.kinetics.base.DirectionalKineticBlock;
import com.simibubi.create.foundation.block.IBE;
import dev.radarfcs.registry.FcsRegistry;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Привод орудия: как коробка передач Create - вал входа сзади, вал выхода спереди (FACING), но скорость выхода
 * (от -1 до 1 входной) задаёт вычислитель. Им можно крутить что угодно: подшипник с поворотной частью, вал станка CBC.
 * PITCH - привод наклона, иначе курса; переключается ПКМ пустой рукой.
 */
public class GunDriveBlock extends DirectionalKineticBlock implements IBE<GunDriveBlockEntity> {
    public static final BooleanProperty PITCH = BooleanProperty.create("pitch");

    public GunDriveBlock(Properties props) {
        super(props);
        registerDefaultState(defaultBlockState().setValue(PITCH, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder.add(PITCH));
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        // выход - от игрока (Shift - к игроку), как ставят коробку передач в линию валов
        Direction f = ctx.getNearestLookingDirection();
        if (ctx.getPlayer() != null && ctx.getPlayer().isShiftKeyDown()) f = f.getOpposite();
        return defaultBlockState().setValue(FACING, f);
    }

    @Override
    public Direction.Axis getRotationAxis(BlockState state) {
        return state.getValue(FACING).getAxis();
    }

    @Override
    public boolean hasShaftTowards(LevelReader world, BlockPos pos, BlockState state, Direction face) {
        return face.getAxis() == state.getValue(FACING).getAxis();
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!level.isClientSide) {
            boolean pitch = !state.getValue(PITCH);
            level.setBlock(pos, state.setValue(PITCH, pitch), 3);
            player.displayClientMessage(Component.translatable(pitch ? "radar_fcs.drive.mode_pitch" : "radar_fcs.drive.mode_yaw")
                    .withStyle(ChatFormatting.AQUA), true);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    public Class<GunDriveBlockEntity> getBlockEntityClass() {
        return GunDriveBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends GunDriveBlockEntity> getBlockEntityType() {
        return FcsRegistry.GUN_DRIVE_BE.get();
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext ctx, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("radar_fcs.tooltip.gun_drive").withStyle(ChatFormatting.GRAY));
    }
}
