package dev.radarfcs.radar;

import java.util.List;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.DyeItem;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/** Общие строки очков инженера и сообщения для радаров. Данные приходят с сервера в NBT блока. */
public final class RadarInfo {
    private RadarInfo() {}

    /** Сводка радара для клиента. */
    public static CompoundTag summary(RadarStatus status, double range, int contacts, DyeColor channel, int elements, int periodTicks) {
        CompoundTag t = new CompoundTag();
        t.putInt("S", status.ordinal());
        t.putFloat("R", (float) range);
        t.putInt("C", contacts);
        t.putInt("Ch", channel.getId());
        t.putInt("E", elements);
        t.putInt("P", periodTicks);
        return t;
    }

    /**
     * @param type     ключ типа радара (bearing, array, dome)
     * @param elements число антенных элементов (отражатели, панели) или -1
     */
    public static void tooltip(List<Component> tip, String type, CompoundTag s) {
        RadarStatus st = RadarStatus.byOrdinal(s.getInt("S"));
        DyeColor ch = DyeColor.byId(s.getInt("Ch"));
        tip.add(Component.literal("    ").append(Component.translatable("radar_fcs.radar.type." + type).withStyle(ChatFormatting.AQUA))
                .append(Component.literal("  "))
                .append(Component.translatable(st.key()).withStyle(st.color)));
        tip.add(Component.literal("    ").append(Component.translatable("radar_fcs.radar.range",
                String.format(Locale.ROOT, "%.0f", s.getFloat("R"))).withStyle(ChatFormatting.GRAY))
                .append(Component.literal("  "))
                .append(Component.translatable("radar_fcs.report.channel",
                        Component.translatable("color.minecraft." + ch.getName())).withStyle(ChatFormatting.GRAY)));
        int e = s.getInt("E");
        if (e >= 0 && !"dome".equals(type)) {
            tip.add(Component.literal("    ").append(Component.translatable("radar_fcs.radar.elements." + type, e)
                    .withStyle(ChatFormatting.DARK_GRAY)));
        }
        if (st == RadarStatus.OK) {
            tip.add(Component.literal("    ").append(Component.translatable("radar_fcs.radar.contacts", s.getInt("C"),
                    String.format(Locale.ROOT, "%.1f", s.getInt("P") / 20.0)).withStyle(ChatFormatting.WHITE)));
        }
    }

    @Nullable
    public static DyeColor dye(ItemStack stack) {
        return stack.getItem() instanceof DyeItem d ? d.getDyeColor() : null;
    }

    public static void channelMessage(Player p, DyeColor c) {
        p.displayClientMessage(Component.translatable("radar_fcs.msg.radar_channel",
                Component.translatable("color.minecraft." + c.getName())), true);
    }
}
