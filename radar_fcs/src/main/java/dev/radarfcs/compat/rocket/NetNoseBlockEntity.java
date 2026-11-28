package dev.radarfcs.compat.rocket;

import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import dev.radarfcs.block.RadarLinkBlockEntity;
import dev.radarfcs.compat.SableCompat;
import dev.radarfcs.core.EngageSettings.Category;
import dev.radarfcs.fcs.NetworkTargets;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * Программируемый нос ракеты (CBC Enchanced Shells): в радарной сети, как камера (сеть - из предмета, настроенного ПКМ
 * по передатчику); ракета летит на выбранную цель сети (назначенную на мониторе, иначе цель вычислителя), захваченную
 * в момент пуска, и не меняет её, даже если сеть её больше не ведёт. Режимы: автопуск (когда у сети есть цель
 * допустимого типа - любая или один тип), автопуск только по цели, назначенной вручную (на мониторе сети - кликом по
 * отметке или лазерным целеуказателем), и пуск по сигналу редстоуна (на нос; сигнал на стартовую площадку тоже
 * пускает - штатно).
 */
public class NetNoseBlockEntity extends BlockEntity implements IHaveGoggleInformation {
    /** Автопуск по цели сети; автопуск только по цели, назначенной вручную (монитор, целеуказатель); по редстоуну. */
    public enum Mode {
        AUTO, AUTO_DESIGNATED, REDSTONE;

        public boolean auto() {
            return this != REDSTONE;
        }

        public String key() {
            return "radar_fcs.rocket_nose.mode_" + name().toLowerCase(Locale.ROOT);
        }
    }

    /** Сводка для очков инженера. */
    public enum State {
        NO_NETWORK, NO_LAUNCHER, NO_TARGET, NO_DESIGNATION, WRONG_TYPE, READY, LAUNCH_FAILED;

        public String key() {
            return "radar_fcs.rocket_nose.state." + name().toLowerCase(Locale.ROOT);
        }
    }

    /** Повтор неудачного автопуска - не чаще раза в 2 с. */
    private static final int RETRY_TICKS = 40;

    @Nullable private UUID network;
    private Mode mode = Mode.REDSTONE;
    /** Допустимая цель автопуска: -1 - любая, иначе номер типа целей вычислителя. */
    private int trigger = -1;

    private State state = State.NO_NETWORK;
    private String targetName = "";
    /** Цель, захваченная при пуске этим носом (уходит в ракету). */
    @Nullable private String pendingLock;
    private long nextTry = Long.MIN_VALUE / 2;
    private boolean powered;

    public NetNoseBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Nullable
    public UUID network() {
        return network;
    }

    public void setNetwork(@Nullable UUID net) {
        if (Objects.equals(net, network)) return;
        network = net;
        changed();
    }

    public Mode mode() {
        return mode;
    }

    public int trigger() {
        return trigger;
    }

    public State state() {
        return state;
    }

    public void apply(Mode m, int trig) {
        mode = m == null ? Mode.REDSTONE : m;
        trigger = trig < 0 || trig >= Category.values().length ? -1 : trig;
        changed();
    }

    public boolean accepts(Category c) {
        return trigger < 0 || c.ordinal() == trigger;
    }

    public Direction facing() {
        return getBlockState().getValue(DirectionalBlock.FACING);
    }

    /** Настройки для ракеты: сеть и цель, захваченная при пуске (если пуск был этим носом). */
    public NoseData noseData() {
        UUID ship = level == null ? null : SableCompat.containingId(level, worldPosition);
        return new NoseData(NoseData.Kind.NETWORK, 0, network, pendingLock, ship);
    }

    public static void serverTick(Level level, BlockPos pos, BlockState st, NetNoseBlockEntity be) {
        if (level instanceof ServerLevel sl && sl.getGameTime() % 5 == Math.floorMod(pos.hashCode(), 5)) be.update(sl);
    }

    private void update(ServerLevel sl) {
        if (network == null) {
            setState(State.NO_NETWORK, "");
            return;
        }
        if (RocketLaunch.findBase(sl, worldPosition, facing()) == null) {
            setState(State.NO_LAUNCHER, "");
            return;
        }
        // автопуск по назначенной цели - только цель, назначенная вручную на мониторе сети (или целеуказателем)
        boolean manual = mode == Mode.AUTO_DESIGNATED;
        NetworkTargets.Target t = NetworkTargets.of(sl, network, launchPoint(sl), manual);
        if (t == null) {
            setState(manual ? State.NO_DESIGNATION : State.NO_TARGET, "");
            return;
        }
        if (!accepts(t.category())) {
            setState(State.WRONG_TYPE, t.name());
            return;
        }
        setState(State.READY, t.name());
        if (mode.auto() && sl.getGameTime() >= nextTry) launch(sl, t.id());
    }

    /** Сигнал редстоуна на носе: в режиме «по редстоуну» - пуск по фронту сигнала с целью сети в этот момент. */
    void redstone(ServerLevel sl, boolean signal) {
        boolean rising = signal && !powered;
        powered = signal;
        if (!rising || mode != Mode.REDSTONE) return;
        NetworkTargets.Target t = NetworkTargets.of(sl, network, launchPoint(sl));
        launch(sl, t == null ? null : t.id());
    }

    /** Точка пуска в мире (нос может стоять на корабле Sable). */
    private dev.radarfcs.core.Vec launchPoint(ServerLevel sl) {
        net.minecraft.world.phys.Vec3 w = SableCompat.toWorld(sl, net.minecraft.world.phys.Vec3.atCenterOf(worldPosition));
        return new dev.radarfcs.core.Vec(w.x, w.y, w.z);
    }

    private void launch(ServerLevel sl, @Nullable String lock) {
        pendingLock = lock;
        nextTry = sl.getGameTime() + RETRY_TICKS;
        if (!RocketLaunch.launch(sl, worldPosition, facing())) {
            pendingLock = null;
            setState(State.LAUNCH_FAILED, "");
        }
    }

    private void setState(State s, String name) {
        if (s == state && name.equals(targetName)) return;
        state = s;
        targetName = name;
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }

    private void changed() {
        setChanged();
        if (level != null && !level.isClientSide) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }

    private void write(CompoundTag tag) {
        if (network != null) tag.putUUID("RadarNetwork", network);
        tag.putString("Mode", mode.name());
        tag.putInt("Trigger", trigger);
    }

    private void read(CompoundTag tag) {
        network = tag.hasUUID("RadarNetwork") ? tag.getUUID("RadarNetwork") : null;
        try {
            mode = tag.contains("Mode") ? Mode.valueOf(tag.getString("Mode")) : Mode.REDSTONE;
        } catch (IllegalArgumentException e) {
            mode = Mode.REDSTONE;
        }
        trigger = tag.contains("Trigger") ? tag.getInt("Trigger") : -1;
        if (trigger >= Category.values().length) trigger = -1;
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        write(tag);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        read(tag);
        if (tag.contains("State")) {
            try {
                state = State.valueOf(tag.getString("State"));
            } catch (IllegalArgumentException ignored) {
            }
            targetName = tag.getString("TargetName");
        }
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag t = super.getUpdateTag(registries);
        write(t);
        t.putString("State", state.name());
        t.putString("TargetName", targetName);
        return t;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    // сеть и режимы - в предмете (как у камеры): сломали и поставили - нос в той же сети
    @Override
    protected void collectImplicitComponents(net.minecraft.core.component.DataComponentMap.Builder components) {
        super.collectImplicitComponents(components);
        CompoundTag t = new CompoundTag();
        write(t);
        components.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.of(t));
    }

    @Override
    protected void applyImplicitComponents(BlockEntity.DataComponentInput input) {
        super.applyImplicitComponents(input);
        var data = input.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
        if (data != null) read(data.copyTag());
    }

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        tooltip.add(Component.literal("    ").append(Component.translatable("block.radar_fcs.network_nose")).withStyle(ChatFormatting.GOLD));
        tooltip.add(Component.literal("    ").append(network == null ? Component.translatable("radar_fcs.rocket_nose.no_network")
                : Component.translatable("radar_fcs.rocket_nose.network", RadarLinkBlockEntity.shortName(network))).withStyle(ChatFormatting.AQUA));
        Component trig = trigger < 0 ? Component.translatable("radar_fcs.rocket_nose.trigger_any")
                : Component.translatable("radar_fcs.gui.type_short." + Category.values()[trigger].name().toLowerCase(Locale.ROOT));
        tooltip.add(Component.literal("    ").append(mode.auto() ? Component.translatable(mode.key() + "_with", trig)
                : Component.translatable(mode.key())).withStyle(ChatFormatting.AQUA));
        ChatFormatting color = state == State.READY ? ChatFormatting.GREEN : state == State.NO_TARGET ? ChatFormatting.GRAY : ChatFormatting.GOLD;
        tooltip.add(Component.literal("    ").append(Component.translatable(state.key(), targetName)).withStyle(color));
        return true;
    }
}
