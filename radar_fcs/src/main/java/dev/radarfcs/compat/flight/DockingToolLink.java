package dev.radarfcs.compat.flight;

import ace.flight.item.FlightControlToolGunItem;
import dev.radarfcs.block.DockingControllerBlockEntity;
import dev.radarfcs.compat.DockingPorts;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * Связь стыковочного контроллера с портом Create Simulated инструментальным пистолетом Flight Control: ПКМ по
 * контроллеру - он выбран, ПКМ по порту на той же постройке - связаны; Shift+ПКМ по контроллеру - связь снята.
 * Выбор помнится по игроку (на сервере и на клиенте отдельно) и забывается через минуту. Пистолет при этом своей
 * работы не делает (событие отменено раньше обработчика Flight Control).
 */
public final class DockingToolLink {
    private record Pick(ResourceKey<Level> dim, BlockPos pos, long until) {}

    private static final Map<UUID, Pick> SERVER = new ConcurrentHashMap<>(), CLIENT = new ConcurrentHashMap<>();
    private static final long TIMEOUT = 20 * 60;

    private DockingToolLink() {}

    public static void init() {
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGH, PlayerInteractEvent.RightClickBlock.class, DockingToolLink::onRightClick);
    }

    private static void onRightClick(PlayerInteractEvent.RightClickBlock e) {
        if (!(e.getItemStack().getItem() instanceof FlightControlToolGunItem)) return;
        Level level = e.getLevel();
        Player p = e.getEntity();
        BlockPos pos = e.getPos();
        Map<UUID, Pick> picks = level.isClientSide ? CLIENT : SERVER;
        long now = level.getGameTime();
        if (level.getBlockEntity(pos) instanceof DockingControllerBlockEntity be) {
            cancel(e);
            if (p.isShiftKeyDown()) {
                picks.remove(p.getUUID());
                if (!level.isClientSide) {
                    be.linkConnector(null);
                    say(p, Component.translatable("radar_fcs.docking.unlinked").withStyle(ChatFormatting.YELLOW));
                }
                return;
            }
            picks.put(p.getUUID(), new Pick(level.dimension(), pos.immutable(), now + TIMEOUT));
            if (!level.isClientSide) say(p, Component.translatable("radar_fcs.docking.pick").withStyle(ChatFormatting.AQUA));
            return;
        }
        Pick pick = picks.get(p.getUUID());
        if (pick == null || !DockingPorts.isConnector(level, pos)) return;
        cancel(e);
        picks.remove(p.getUUID());
        if (level.isClientSide) return;
        if (pick.until() < now || !pick.dim().equals(level.dimension())
                || !(level.getBlockEntity(pick.pos()) instanceof DockingControllerBlockEntity be)) {
            say(p, Component.translatable("radar_fcs.docking.pick_lost").withStyle(ChatFormatting.RED));
            return;
        }
        if (!DockingPorts.linkable(level, pick.pos(), pos, DockingControllerBlockEntity.LINK_RADIUS)) {
            say(p, Component.translatable("radar_fcs.docking.too_far", (int) DockingControllerBlockEntity.LINK_RADIUS).withStyle(ChatFormatting.RED));
            return;
        }
        be.linkConnector(pos.immutable());
        say(p, Component.translatable("radar_fcs.docking.linked").withStyle(ChatFormatting.GREEN));
    }

    private static void cancel(PlayerInteractEvent.RightClickBlock e) {
        e.setCanceled(true);
        e.setCancellationResult(InteractionResult.sidedSuccess(e.getLevel().isClientSide));
    }

    private static void say(Player p, Component c) {
        p.displayClientMessage(c, true);
    }
}
