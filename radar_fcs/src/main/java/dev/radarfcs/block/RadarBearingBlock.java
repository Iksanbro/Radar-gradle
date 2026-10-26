package dev.radarfcs.block;

import com.simibubi.create.content.contraptions.bearing.BearingBlock;
import com.simibubi.create.foundation.block.IBE;
import dev.radarfcs.radar.RadarInfo;
import dev.radarfcs.registry.FcsRegistry;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Радарный подшипник: подшипник Create, который вращает антенну из облучателя и отражателей.
 * Антенна собирается сама, когда подшипник получает вращение. ПКМ красителем - канал.
 */
public class RadarBearingBlock extends BearingBlock implements IBE<RadarBearingBlockEntity> {
    public RadarBearingBlock(Properties props) {
        super(props);
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
                                              InteractionHand hand, BlockHitResult hit) {
        if (!player.mayBuild()) return ItemInteractionResult.FAIL;
        DyeColor dye = RadarInfo.dye(stack);
        if (dye != null) {
            if (!level.isClientSide) withBlockEntityDo(level, pos, be -> be.setChannel(dye, player));
            return ItemInteractionResult.sidedSuccess(level.isClientSide);
        }
        if (player.isShiftKeyDown() || !stack.isEmpty()) return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        if (!level.isClientSide) withBlockEntityDo(level, pos, RadarBearingBlockEntity::toggleAssembly);
        return ItemInteractionResult.SUCCESS;
    }

    @Override
    public Class<RadarBearingBlockEntity> getBlockEntityClass() {
        return RadarBearingBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends RadarBearingBlockEntity> getBlockEntityType() {
        return FcsRegistry.RADAR_BEARING_BE.get();
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext ctx, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("radar_fcs.tooltip.radar_bearing").withStyle(ChatFormatting.GRAY));
    }
}
