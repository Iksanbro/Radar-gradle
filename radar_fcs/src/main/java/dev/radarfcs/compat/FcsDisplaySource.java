package dev.radarfcs.compat;

import com.simibubi.create.api.behaviour.display.DisplaySource;
import com.simibubi.create.content.redstone.displayLink.DisplayLinkContext;
import com.simibubi.create.content.redstone.displayLink.target.DisplayTargetStats;
import dev.radarfcs.block.FireControlBlockEntity;
import dev.radarfcs.fcs.Status;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * Источник для Display Link (Create): список активных целей вычислителя на табло, мониторах и т.п.
 * Свои и неактивные цели не выводятся.
 */
public class FcsDisplaySource extends DisplaySource {

    @Override
    public List<MutableComponent> provideText(DisplayLinkContext context, DisplayTargetStats stats) {
        if (!(context.getSourceBlockEntity() instanceof FireControlBlockEntity be)) return EMPTY;
        CompoundTag s = be.summary();
        List<MutableComponent> out = new ArrayList<>();
        ListTag tl = s.getList("List", Tag.TAG_COMPOUND);
        int firing = 0;
        ListTag ml = s.getList("Mounts", Tag.TAG_COMPOUND);
        for (int i = 0; i < ml.size(); i++) if (Status.byOrdinal(ml.getCompound(i).getInt("S")) == Status.FIRE) firing++;
        if (!s.getBoolean("RadarOk")) {
            out.add(Component.translatable("radar_fcs.display.no_radar"));
            return out;
        }
        out.add(Component.translatable("radar_fcs.display.header", s.getInt("Targets"), firing, ml.size()));
        for (int i = 0; i < tl.size() && out.size() < Math.max(1, stats.maxRows()); i++) {
            CompoundTag t = tl.getCompound(i);
            out.add(Component.literal(String.format(Locale.ROOT, "%s%s %.0fm", t.getBoolean("E") ? "> " : "  ",
                    t.getString("N"), t.getFloat("D"))));
        }
        return out;
    }

    @Override
    public int getPassiveRefreshTicks() {
        return 20;
    }

    @Override
    protected String getTranslationKey() {
        return "fire_control";
    }
}
