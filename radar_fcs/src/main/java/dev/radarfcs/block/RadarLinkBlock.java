package dev.radarfcs.block;

import com.mojang.serialization.MapCodec;
import dev.radarfcs.registry.FcsRegistry;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
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
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * Радарный линк: стоит на грани одной аппаратуры (FACING - от неё наружу) и связывает её с другой
 * (вычислитель - станок, радар, монитор; монитор - радар). Ставится предметом линка после выбора первой аппаратуры.
 */
public class RadarLinkBlock extends BaseEntityBlock {
    public static final MapCodec<RadarLinkBlock> CODEC = simpleCodec(RadarLinkBlock::new);
    public static final DirectionProperty FACING = BlockStateProperties.FACING;

    private static final Map<Direction, VoxelShape> SHAPES = new EnumMap<>(Direction.class);

    static {
        for (Direction d : Direction.values()) {
            // плата 12x3x12 и антенна 2x4x2 - модель для FACING=UP, повёрнутая на грань
            SHAPES.put(d, Shapes.or(box(d, 2, 0, 2, 14, 3, 14), box(d, 7, 3, 7, 9, 7, 9)));
        }
    }

    /** Коробка модели, заданной для FACING=UP, повёрнутая на сторону d. */
    private static VoxelShape box(Direction d, double x0, double y0, double z0, double x1, double y1, double z1) {
        return switch (d) {
            case UP -> Block.box(x0, y0, z0, x1, y1, z1);
            case DOWN -> Block.box(x0, 16 - y1, z0, x1, 16 - y0, z1);
            case NORTH -> Block.box(x0, z0, 16 - y1, x1, z1, 16 - y0);
            case SOUTH -> Block.box(x0, z0, y0, x1, z1, y1);
            case WEST -> Block.box(16 - y1, z0, x0, 16 - y0, z1, x1);
            case EAST -> Block.box(y0, z0, x0, y1, z1, x1);
        };
    }

    public RadarLinkBlock(Properties props) {
        super(props);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.UP));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        return defaultBlockState().setValue(FACING, ctx.getClickedFace());
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return SHAPES.get(state.getValue(FACING));
    }

    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        return !level.getBlockState(pos.relative(state.getValue(FACING).getOpposite())).isAir();
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction dir, BlockState neighbor, LevelAccessor level, BlockPos pos,
                                     BlockPos neighborPos) {
        // аппаратуру убрали - линк отваливается
        if (dir == state.getValue(FACING).getOpposite() && !canSurvive(state, level, pos)) return Blocks.AIR.defaultBlockState();
        return super.updateShape(state, dir, neighbor, level, pos, neighborPos);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof RadarLinkBlockEntity be) {
            player.displayClientMessage(be.describe(), true);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new RadarLinkBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide ? null
                : createTickerHelper(type, FcsRegistry.RADAR_LINK_BE.get(), RadarLinkBlockEntity::serverTick);
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext ctx, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("radar_fcs.tooltip.radar_link").withStyle(ChatFormatting.GRAY));
    }
}
