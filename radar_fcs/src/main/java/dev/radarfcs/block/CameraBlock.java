package dev.radarfcs.block;

import com.mojang.serialization.MapCodec;
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
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Камера: ставится на любую грань, смотрит от неё (FACING). Подключается к радарной сети линком; через монитор
 * камер сети игрок смотрит от её лица, а пушки сети наводятся туда, куда она смотрит.
 */
public class CameraBlock extends BaseEntityBlock {
    public static final MapCodec<CameraBlock> CODEC = simpleCodec(CameraBlock::new);
    public static final DirectionProperty FACING = BlockStateProperties.FACING;
    private static final Map<Direction, VoxelShape> SHAPES = new EnumMap<>(Direction.class);

    static {
        // корпус 8x7x13 у задней грани, объектив спереди - модель для FACING=north
        for (Direction d : Direction.values()) {
            SHAPES.put(d, Shapes.or(box(d, 4, 4, 3, 12, 11, 16), box(d, 6, 5.5, 1, 10, 9.5, 3)));
        }
    }

    /** Коробка модели FACING=north, повёрнутая на сторону d. */
    private static VoxelShape box(Direction d, double x0, double y0, double z0, double x1, double y1, double z1) {
        double[][] c = {{x0, y0, z0}, {x1, y1, z1}};
        double minX = 16, minY = 16, minZ = 16, maxX = 0, maxY = 0, maxZ = 0;
        for (double[] p : c) {
            double x = p[0] - 8, y = p[1] - 8, z = p[2] - 8, rx, ry, rz;
            switch (d) {
                case SOUTH -> { rx = -x; ry = y; rz = -z; }
                case EAST -> { rx = -z; ry = y; rz = x; }
                case WEST -> { rx = z; ry = y; rz = -x; }
                case UP -> { rx = x; ry = -z; rz = y; }
                case DOWN -> { rx = x; ry = z; rz = -y; }
                default -> { rx = x; ry = y; rz = z; }
            }
            minX = Math.min(minX, rx + 8); maxX = Math.max(maxX, rx + 8);
            minY = Math.min(minY, ry + 8); maxY = Math.max(maxY, ry + 8);
            minZ = Math.min(minZ, rz + 8); maxZ = Math.max(maxZ, rz + 8);
        }
        return Block.box(minX, minY, minZ, maxX, maxY, maxZ);
    }

    public CameraBlock(Properties props) {
        super(props);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
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
    protected BlockState rotate(BlockState state, Rotation rot) {
        return state.setValue(FACING, rot.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return rotate(state, mirror.getRotation(state.getValue(FACING)));
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
        return new CameraBlockEntity(pos, state);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof CameraBlockEntity be) {
            player.displayClientMessage(Component.translatable("radar_fcs.camera.info", be.displayName()).withStyle(ChatFormatting.AQUA), true);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext ctx, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("radar_fcs.tooltip.camera").withStyle(ChatFormatting.GRAY));
    }

}
