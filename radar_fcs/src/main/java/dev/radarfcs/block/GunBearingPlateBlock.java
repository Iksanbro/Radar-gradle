package dev.radarfcs.block;

import com.simibubi.create.content.kinetics.base.DirectionalKineticBlock;
import com.simibubi.create.foundation.block.IBE;
import net.createmod.catnip.math.VoxelShaper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Пластина подшипника орудия - верхняя часть подшипника на его поворотной части (как пластина поворотного подшипника
 * Aeronautics): стоит в плоте поворотной части в клетке подшипника, к ней крепится постройка, через неё проходит вал.
 * ПКМ пустой рукой - разобрать подшипник; сломать пластину - сломать подшипник. Отдельным предметом не бывает.
 */
public class GunBearingPlateBlock extends DirectionalKineticBlock implements IBE<GunBearingPlateBlockEntity>,
        dev.ryanhcode.sable.api.block.BlockSubLevelAssemblyListener {
    /** Рамка пластины: верх клетки, 12..16 по оси. */
    private static final VoxelShaper SHAPE = VoxelShaper.forDirectional(Block.box(0, 12, 0, 16, 16, 16), Direction.UP);

    public GunBearingPlateBlock(Properties props) {
        super(props);
    }

    @Override
    public Direction.Axis getRotationAxis(BlockState state) {
        return state.getValue(FACING).getAxis();
    }

    /** Вал - вперёд, в постройку (сзади вал приходит от подшипника, он не сосед). */
    @Override
    public boolean hasShaftTowards(LevelReader world, BlockPos pos, BlockState state, Direction face) {
        return face == state.getValue(FACING);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return SHAPE.get(state.getValue(FACING));
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!player.mayBuild() || player.isShiftKeyDown()) return InteractionResult.PASS;
        if (!level.isClientSide) withBlockEntityDo(level, pos, GunBearingPlateBlockEntity::requestParentToggle);
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    public InteractionResult onWrenched(BlockState state, UseOnContext ctx) {
        return InteractionResult.PASS;
    }

    @Override
    public ItemStack getCloneItemStack(LevelReader level, BlockPos pos, BlockState state) {
        return new ItemStack(GunBearings.ITEM.get());
    }

    @Override
    public void beforeMove(ServerLevel oldLevel, ServerLevel newLevel, BlockState state, BlockPos oldPos, BlockPos newPos) {
        if (oldLevel.getBlockEntity(oldPos) instanceof GunBearingPlateBlockEntity be) be.moving = true;
    }

    /** Пластина переехала (её постройку собрали в другую): подшипник узнаёт её новое место. */
    @Override
    public void afterMove(ServerLevel oldLevel, ServerLevel newLevel, BlockState state, BlockPos oldPos, BlockPos newPos) {
        if (newLevel.getBlockEntity(newPos) instanceof GunBearingPlateBlockEntity be) be.afterMove();
    }

    @Override
    public Class<GunBearingPlateBlockEntity> getBlockEntityClass() {
        return GunBearingPlateBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends GunBearingPlateBlockEntity> getBlockEntityType() {
        return GunBearings.PLATE_BE.get();
    }
}
