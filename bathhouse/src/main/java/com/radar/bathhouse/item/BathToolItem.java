package com.radar.bathhouse.item;

import com.radar.bathhouse.climate.BathType;
import com.radar.bathhouse.registry.ModDamageTypes;
import com.radar.bathhouse.session.BathSessionTracker;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/**
 * A bath ritual tool used on yourself in a ready bath of its own kind: the broom in the steam lodge,
 * the scrubbing mitt in the mist bath. Speeds up the session and heals a little.
 */
public class BathToolItem extends Item {
    public static final double PROGRESS_PER_USE = 4.0;
    private static final int COOLDOWN = 20;

    private final BathType bath;
    @Nullable
    private final BathType forbidden;
    private final SoundEvent sound;
    private final ParticleOptions particle;

    /**
     * @param bath      the bath type the tool belongs to
     * @param forbidden a bath type where using it hurts (the broom dries out and scalds in dry heat), or null
     */
    public BathToolItem(Properties properties, BathType bath, @Nullable BathType forbidden, SoundEvent sound, ParticleOptions particle) {
        super(properties);
        this.bath = bath;
        this.forbidden = forbidden;
        this.sound = sound;
        this.particle = particle;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!(player instanceof ServerPlayer serverPlayer) || !(level instanceof ServerLevel serverLevel)) {
            return InteractionResultHolder.success(stack);
        }
        BathSessionTracker.Session session = BathSessionTracker.active(serverPlayer);
        BathType current = session == null || !session.ready() ? null : session.type();

        if (current != null && current == forbidden) {
            // A broom in dry heat: the leaves crumble and the hot twigs scald.
            stack.hurtAndBreak(8, player, LivingEntity.getSlotForHand(hand));
            player.hurt(player.damageSources().source(ModDamageTypes.OVERHEAT), 1.0F);
            player.getCooldowns().addCooldown(this, COOLDOWN);
            player.displayClientMessage(Component.translatable(getDescriptionId() + ".forbidden").withStyle(ChatFormatting.RED), true);
            return InteractionResultHolder.fail(stack);
        }
        if (current != bath) {
            player.displayClientMessage(Component.translatable(getDescriptionId() + ".not_here").withStyle(ChatFormatting.YELLOW), true);
            return InteractionResultHolder.fail(stack);
        }

        BathSessionTracker.recordTool(serverPlayer, PROGRESS_PER_USE);
        player.heal(1.0F);
        player.getCooldowns().addCooldown(this, COOLDOWN);
        stack.hurtAndBreak(1, player, LivingEntity.getSlotForHand(hand));
        serverLevel.playSound(null, player.blockPosition(), sound, SoundSource.PLAYERS, 1.0F, 0.8F + level.random.nextFloat() * 0.4F);
        serverLevel.sendParticles(particle, player.getX(), player.getY() + 1.0, player.getZ(), 12, 0.3, 0.4, 0.3, 0.05);
        return InteractionResultHolder.consume(stack);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable(getDescriptionId() + ".tooltip").withStyle(ChatFormatting.GRAY));
    }
}
