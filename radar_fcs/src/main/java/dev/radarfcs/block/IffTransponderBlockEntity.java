package dev.radarfcs.block;

import dev.radarfcs.FcsConfig;
import dev.radarfcs.compat.SableCompat;
import dev.radarfcs.core.EngageSettings;
import dev.radarfcs.iff.IffAccess;
import dev.radarfcs.iff.IffRegistry;
import dev.radarfcs.iff.TransponderMode;
import dev.radarfcs.registry.FcsRegistry;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.scores.Team;
import org.jetbrains.annotations.Nullable;

/**
 * Транспондер «свой»: код (цифры), режим выделения (сублевел, на котором стоит, или радиус) и радиус.
 * Регистрируется в IffRegistry, пока блок загружен. Код не уходит на клиенты в обновлениях блока: его видят и меняют
 * только владелец, его команда и операторы (экран открывается пакетом с сервера).
 */
public class IffTransponderBlockEntity extends BlockEntity {
    @Nullable private UUID owner;
    private String ownerTeam = "";
    private String code = "";
    /** null - по месту: на сублевеле «сублевел», иначе «радиус». */
    @Nullable private TransponderMode mode;
    private int radius = defaultRadius();

    public IffTransponderBlockEntity(BlockPos pos, BlockState state) {
        super(FcsRegistry.IFF_BE.get(), pos, state);
    }

    private static int defaultRadius() {
        try {
            return (int) Math.round(FcsConfig.IFF_RADIUS.get());
        } catch (IllegalStateException e) {
            return 12;                                                    // конфиг сервера ещё не загружен (клиент)
        }
    }

    public static int maxRadius() {
        try {
            return FcsConfig.MAX_TRANSPONDER_RADIUS.get();
        } catch (IllegalStateException e) {
            return 64;
        }
    }

    @Override
    public void onLoad() {
        super.onLoad();
        register();
    }

    private void register() {
        if (level != null && !level.isClientSide) {
            IffRegistry.put(level, worldPosition, new IffRegistry.Beacon(code, mode, Math.min(radius, maxRadius())));
        }
    }

    @Override
    public void onChunkUnloaded() {
        if (level != null && !level.isClientSide) IffRegistry.remove(level, worldPosition);
        super.onChunkUnloaded();
    }

    @Override
    public void setRemoved() {
        if (level != null && !level.isClientSide) IffRegistry.remove(level, worldPosition);
        super.setRemoved();
    }

    // ------------------------------------------------------------ настройки

    public String code() { return code; }

    public int radius() { return radius; }

    /** Режим с учётом места. */
    public TransponderMode effectiveMode() {
        return level == null ? (mode == null ? TransponderMode.RADIUS : mode) : IffRegistry.modeAt(level, worldPosition, mode);
    }

    /** Стоит на сублевеле (корабле Sable / Create Aeronautics). */
    public boolean onSublevel() {
        return level != null && SableCompat.containingId(level, worldPosition) != null;
    }

    public void setOwner(Player p) {
        owner = p.getUUID();
        Team t = p.getTeam();
        ownerTeam = t != null ? t.getName() : "";
        setChanged();
    }

    public boolean canEdit(Player p) {
        return IffAccess.canEdit(p, owner, ownerTeam);
    }

    /**
     * Новые настройки (экран транспондера, проверяются здесь); p - кто меняет (null - без проверки доступа).
     * @return приняты ли
     */
    public boolean apply(String newCode, TransponderMode newMode, int newRadius, @Nullable Player p) {
        if (p != null && !canEdit(p)) {
            p.displayClientMessage(Component.translatable("radar_fcs.msg.transponder_locked").withStyle(ChatFormatting.RED), true);
            return false;
        }
        if (p != null && owner == null) setOwner(p);
        code = EngageSettings.cleanCode(newCode);
        mode = newMode == null ? TransponderMode.RADIUS : newMode;
        radius = Math.max(1, Math.min(maxRadius(), newRadius));
        setChanged();
        register();
        if (p != null) p.displayClientMessage(Component.translatable("radar_fcs.msg.transponder_saved").withStyle(ChatFormatting.GREEN), true);
        return true;
    }

    // ------------------------------------------------------------ NBT

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (owner != null) tag.putUUID("Owner", owner);
        tag.putString("OwnerTeam", ownerTeam);
        tag.putString("Code", code);
        if (mode != null) tag.putString("Mode", mode.name());
        tag.putInt("Radius", radius);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        owner = tag.hasUUID("Owner") ? tag.getUUID("Owner") : null;
        ownerTeam = tag.getString("OwnerTeam");
        code = EngageSettings.cleanCode(tag.getString("Code"));
        mode = modeOf(tag.getString("Mode"));
        radius = tag.contains("Radius") ? Math.max(1, tag.getInt("Radius")) : defaultRadius();
        register();
    }

    @Nullable
    public static TransponderMode modeOf(String name) {
        try {
            return name == null || name.isEmpty() ? null : TransponderMode.valueOf(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
