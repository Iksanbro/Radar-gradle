package dev.radarfcs.block;

import com.simibubi.create.content.kinetics.base.DirectionalKineticBlock;
import com.simibubi.create.content.kinetics.base.IRotate;
import com.simibubi.create.content.kinetics.simpleRelays.ICogWheel;
import com.simibubi.create.foundation.block.IBE;
import dev.simulated_team.simulated.util.extra_kinetics.ExtraKinetics;
import java.util.List;
import net.createmod.catnip.math.VoxelShaper;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Подшипник орудия - как поворотный подшипник Create Aeronautics: FACING - сторона, к которой крепится поворотная часть.
 * ПКМ пустой рукой - собрать постройку перед ним (клей, «липкие» блоки) в поворотную часть на пластине подшипника,
 * повторно (или ПКМ по пластине) - разобрать обратно. Поворачивает его встроенное кольцо-шестерня (в зацеплении с
 * шестернями рядом, как у Aeronautics); вал проходит насквозь в постройку. Собранный - ниже на верхнюю часть (её место
 * занимает пластина на поворотной части). Режим блокировки - на панели сбоку (как у Aeronautics, с редстоуном).
 * Отличие - сервопривод гасит инерцию: критическое демпфирование и упреждение, поворотная часть не раскачивается.
 */
public class GunBearingBlock extends DirectionalKineticBlock implements IBE<GunBearingBlockEntity>, ExtraKinetics.ExtraKineticsBlock,
        dev.ryanhcode.sable.api.block.BlockSubLevelAssemblyListener {
    public static final BooleanProperty ASSEMBLED = BooleanProperty.create("assembled");
    public static final BooleanProperty POWERED = BlockStateProperties.POWERED;
    /** Собранный подшипник: нижняя часть до 11.9/16 (выше - пластина на поворотной части). */
    private static final VoxelShaper ASSEMBLED_SHAPE = VoxelShaper.forDirectional(Block.box(0, 0, 0, 16, 11.9, 16), Direction.UP);

    /** Встроенное кольцо-шестерня: малая шестерня на оси подшипника, без вала. */
    private static final ICogWheel COG = new ICogWheel() {
        @Override
        public boolean hasShaftTowards(LevelReader world, BlockPos pos, BlockState state, Direction face) {
            return false;
        }

        @Override
        public Direction.Axis getRotationAxis(BlockState state) {
            return state.getValue(FACING).getAxis();
        }
    };

    public GunBearingBlock(Properties props) {
        super(props);
        registerDefaultState(defaultBlockState().setValue(ASSEMBLED, false).setValue(POWERED, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder.add(ASSEMBLED, POWERED));
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        return defaultBlockState().setValue(FACING, ctx.getClickedFace());
    }

    @Override
    public Direction.Axis getRotationAxis(BlockState state) {
        return state.getValue(FACING).getAxis();
    }

    /** Вал насквозь: несобранный - с обеих сторон оси; собранный - сзади (спереди вал идёт в пластину). */
    @Override
    public boolean hasShaftTowards(LevelReader world, BlockPos pos, BlockState state, Direction face) {
        Direction f = state.getValue(FACING);
        if (state.getValue(ASSEMBLED)) return face == f.getOpposite();
        return face.getAxis() == f.getAxis();
    }

    @Override
    public IRotate getExtraKineticsRotationConfiguration() {
        return COG;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return state.getValue(ASSEMBLED) ? ASSEMBLED_SHAPE.get(state.getValue(FACING)) : Shapes.block();
    }

    /** ПКМ пустой рукой (без Shift) - собрать или разобрать (как у Aeronautics). */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!player.mayBuild() || player.isShiftKeyDown()) return InteractionResult.PASS;
        if (!level.isClientSide) withBlockEntityDo(level, pos, be -> be.assembleNextTick = true);
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    /** Собранный подшипник ключом не поворачивается. */
    @Override
    public InteractionResult onWrenched(BlockState state, UseOnContext ctx) {
        if (state.getValue(ASSEMBLED)) return InteractionResult.PASS;
        return super.onWrenched(state, ctx);
    }

    /** Подшипник уезжает в постройку (собирают то, на чём он стоит): связь с пластиной не рвать. */
    @Override
    public void beforeMove(ServerLevel oldLevel, ServerLevel newLevel, BlockState state, BlockPos oldPos, BlockPos newPos) {
        if (oldLevel.getBlockEntity(oldPos) instanceof GunBearingBlockEntity be) be.moving = true;
    }

    /** Подшипник переехал: пластина узнаёт его новое место, шарнир - сразу на новое основание. */
    @Override
    public void afterMove(ServerLevel oldLevel, ServerLevel newLevel, BlockState state, BlockPos oldPos, BlockPos newPos) {
        if (newLevel.getBlockEntity(newPos) instanceof GunBearingBlockEntity be) be.afterMove(newLevel);
    }

    @Override
    public Class<GunBearingBlockEntity> getBlockEntityClass() {
        return GunBearingBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends GunBearingBlockEntity> getBlockEntityType() {
        return GunBearings.BE.get();
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext ctx, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("radar_fcs.tooltip.gun_bearing").withStyle(ChatFormatting.GRAY));
    }
}
