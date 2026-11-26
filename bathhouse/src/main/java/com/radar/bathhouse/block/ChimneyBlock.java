package com.radar.bathhouse.block;

import com.mojang.serialization.MapCodec;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import com.radar.bathhouse.stove.FireboxBlock;
import com.radar.bathhouse.stove.StoveStructure;

/**
 * Brick flue, the top part of a stove. Stacked on the stove (on the firebox, heater or boiler, possibly with a damper
 * and a water tank in between) it carries the smoke away: a "white" bath. Built into the ceiling or wall of a room whose
 * stove has no chimney of its own, it draws the smoke through the room: a "gray" bath.
 */
public class ChimneyBlock extends Block {
    public static final MapCodec<ChimneyBlock> CODEC = simpleCodec(ChimneyBlock::new);
    private static final VoxelShape SHAPE = Block.box(4, 0, 4, 12, 16, 12);

    public ChimneyBlock(Properties properties) {
        super(properties);
    }

    public static boolean isChimney(BlockState state) {
        return state.getBlock() instanceof ChimneyBlock;
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        BlockState above = level.getBlockState(pos.above());
        if (StoveStructure.isFlue(above) || above.isSolidRender(level, pos.above()) || random.nextInt(2) != 0) {
            return;
        }
        // Smoke only rises from the top of a flue that stands on a burning wood stove.
        BlockPos core = StoveStructure.findCore(level, pos);
        if (core == null) {
            return;
        }
        BlockState firebox = level.getBlockState(core);
        if (firebox.getBlock() instanceof FireboxBlock block && block.type().burnsFuel() && firebox.getValue(FireboxBlock.LIT)) {
            level.addAlwaysVisibleParticle(ParticleTypes.CAMPFIRE_COSY_SMOKE, true,
                    pos.getX() + 0.5 + (random.nextDouble() - 0.5) * 0.2, pos.getY() + 1.0,
                    pos.getZ() + 0.5 + (random.nextDouble() - 0.5) * 0.2, 0.0, 0.06, 0.0);
        }
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("block.bathhouse.chimney.tooltip").withStyle(ChatFormatting.GRAY));
    }
}
