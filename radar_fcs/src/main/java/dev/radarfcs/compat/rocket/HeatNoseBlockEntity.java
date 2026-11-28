package dev.radarfcs.compat.rocket;

import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import dev.radarfcs.core.EngageSettings.Category;
import java.util.List;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Тепловая головка самонаведения (нос ракеты CBC Enchanced Shells): какие типы целей она ищет после пуска - из списка
 * типов целей вычислителя. Ищет и захватывает цель сама (без сети); захваченную цель не меняет.
 */
public class HeatNoseBlockEntity extends BlockEntity implements IHaveGoggleInformation {
    /** По умолчанию - корабли и мобы. */
    public static final int DEFAULT = bit(Category.SHIPS) | bit(Category.MOBS);
    public static final int ALL = (1 << Category.values().length) - 1;
    private int categories = DEFAULT;

    public HeatNoseBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    public static int bit(Category c) {
        return 1 << c.ordinal();
    }

    public int categories() {
        return categories;
    }

    public void setCategories(int mask) {
        categories = mask & ALL;
        setChanged();
        if (level != null && !level.isClientSide) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }

    /** Список типов целей для подсказок. */
    public static MutableComponent describe(int mask) {
        MutableComponent out = Component.empty();
        boolean first = true;
        for (Category c : Category.values()) {
            if ((mask & bit(c)) == 0) continue;
            if (!first) out.append(", ");
            out.append(Component.translatable("radar_fcs.gui.type_short." + c.name().toLowerCase(Locale.ROOT)));
            first = false;
        }
        return first ? Component.translatable("radar_fcs.rocket_nose.no_types") : out;
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("Targets", categories);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        categories = tag.contains("Targets") ? tag.getInt("Targets") & ALL : DEFAULT;
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag t = super.getUpdateTag(registries);
        t.putInt("Targets", categories);
        return t;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    // настройка сохраняется в предмете: сломали и поставили - те же типы целей
    @Override
    protected void collectImplicitComponents(net.minecraft.core.component.DataComponentMap.Builder components) {
        super.collectImplicitComponents(components);
        CompoundTag t = new CompoundTag();
        t.putInt("Targets", categories);
        components.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.of(t));
    }

    @Override
    protected void applyImplicitComponents(BlockEntity.DataComponentInput input) {
        super.applyImplicitComponents(input);
        var data = input.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
        if (data != null) {
            CompoundTag t = data.copyTag();
            if (t.contains("Targets")) categories = t.getInt("Targets") & ALL;
        }
    }

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        tooltip.add(Component.literal("    ").append(Component.translatable("block.radar_fcs.heat_seeker_nose")).withStyle(ChatFormatting.GOLD));
        tooltip.add(Component.literal("    ").append(Component.translatable("radar_fcs.rocket_nose.targets", describe(categories)))
                .withStyle(ChatFormatting.AQUA));
        tooltip.add(Component.literal("    ").append(Component.translatable("radar_fcs.rocket_nose.heat_goggles")).withStyle(ChatFormatting.GRAY));
        return true;
    }
}
