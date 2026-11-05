package dev.radarfcs.block;

import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import dev.radarfcs.compat.SableCompat;
import dev.radarfcs.core.Vec;
import dev.radarfcs.fcs.ManualAim;
import dev.radarfcs.registry.FcsRegistry;
import java.util.List;
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
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.jetbrains.annotations.Nullable;

/**
 * Камера: с монитора камер игрок смотрит от её лица. Пока он управляет камерой, вычислители её сети наводят все
 * пушки на точку, куда она смотрит (блок или сущность на луче), и стреляют, пока зажат огонь; автоприцеливание
 * в это время приостановлено. Управление держится, пока клиент присылает команды (не реже раза в TIMEOUT тиков).
 */
public class CameraBlockEntity extends BlockEntity implements IHaveGoggleInformation {
    /** Дальность луча взгляда, блоков (по загруженным чанкам). */
    public static final double RANGE = 384;
    /** Без команд игрока дольше - управление снято, автоприцеливание возвращается. */
    public static final int TIMEOUT = 10;
    public static final int NAME_MAX = 24;

    private String name = "";
    /**
     * Своя радарная сеть камеры (без радарного линка, как фабричный датчик Create): предмет камеры настраивается ПКМ по
     * радарному линку и ставится уже в сети. Занимает одно подключение в лимите сети. null - только через линк.
     */
    @Nullable private UUID network;
    private boolean registered;
    @Nullable private UUID controller;
    @Nullable private BlockPos controlMonitor;
    private long lastControl = Long.MIN_VALUE / 2;
    private float viewYaw, viewPitch;
    /** Взгляд уже задавался игроком (иначе - от грани установки); последний взгляд сохраняется. */
    private boolean hasView;
    private long lastViewSync = Long.MIN_VALUE / 2;
    private boolean viewDirty;
    // клиент: прошлый и новый взгляд для плавного поворота головы
    private float clientPrevYaw, clientPrevPitch;
    private long clientViewTime = Long.MIN_VALUE / 2;
    private boolean fire;
    @Nullable private Vec3 aimPoint;
    /** Скорость точки взгляда (корабль Sable, сущность), блоков/тик: пушки берут упреждение. */
    private Vec3 aimVelocity = Vec3.ZERO;

    public CameraBlockEntity(BlockPos pos, BlockState state) {
        super(FcsRegistry.CAMERA_BE.get(), pos, state);
    }

    public Direction facing() {
        BlockState st = getBlockState();
        return st.hasProperty(CameraBlock.FACING) ? st.getValue(CameraBlock.FACING) : Direction.NORTH;
    }

    /**
     * Центр шара-головы (в координатах блока, на корабле - плот-координаты): под поворотным цилиндром; у камеры на полу
     * (FACING = UP) цилиндр снизу, шар сверху.
     */
    public Vec3 pivot() {
        return Vec3.atCenterOf(worldPosition).add(0, facing() == Direction.UP ? 3.0 / 16 : -3.0 / 16, 0);
    }

    /** Глаз камеры в мире: центр шара-головы (на корабле Sable - через позу корабля). Свой корпус в виде камеры не рисуется. */
    public Vec3 eye() {
        return level == null ? pivot() : SableCompat.toWorld(level, pivot());
    }

    /** Куда смотрит камера сейчас: последний взгляд игрока или от грани установки - {yaw, pitch}. */
    public float[] view() {
        return hasView ? new float[]{viewYaw, viewPitch} : defaultView();
    }

    /** Клиент: взгляд для отрисовки головы - плавно от прошлого к новому за 2 тика. */
    public float[] renderView(float partialTicks) {
        float[] v = view();
        if (level == null) return v;
        float t = Math.max(0, Math.min(1, (level.getGameTime() - clientViewTime + partialTicks) / 2f));
        float dy = net.minecraft.util.Mth.wrapDegrees(v[0] - clientPrevYaw);
        return new float[]{clientPrevYaw + dy * t, clientPrevPitch + (v[1] - clientPrevPitch) * t};
    }

    /** Курс и наклон взгляда по умолчанию - от грани, на которой стоит камера: {yaw, pitch} Minecraft. */
    public float[] defaultView() {
        Direction f = facing();
        if (f == Direction.UP) return new float[]{0, -89};
        if (f == Direction.DOWN) return new float[]{0, 89};
        return new float[]{f.toYRot(), 0};
    }

    public String name() {
        return name;
    }

    /** Имя для списка камер: заданное или «Камера X Y Z». */
    public Component displayName() {
        return name.isEmpty() ? Component.translatable("radar_fcs.camera.default_name", worldPosition.getX(), worldPosition.getY(), worldPosition.getZ())
                : Component.literal(name);
    }

    public void setName(String n) {
        String s = ChatFormatting.stripFormatting(n == null ? "" : n);
        s = s == null ? "" : s.strip();
        name = s.length() > NAME_MAX ? s.substring(0, NAME_MAX) : s;
        setChanged();
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }

    public long lastControl() {
        return lastControl;
    }

    /** Кто сейчас управляет камерой (null - никто). */
    @Nullable
    public UUID controller(long now) {
        return controller != null && now - lastControl <= TIMEOUT ? controller : null;
    }

    @Nullable
    public BlockPos controlMonitor() {
        return controlMonitor;
    }

    /** Начать управление с монитора камер; false - камерой уже управляет другой игрок. */
    public boolean startControl(ServerPlayer p, BlockPos monitor) {
        long now = p.serverLevel().getGameTime();
        UUID c = controller(now);
        if (c != null && !c.equals(p.getUUID())) return false;
        controller = p.getUUID();
        controlMonitor = monitor.immutable();
        lastControl = now;
        float[] v = view();                                        // с последнего положения
        viewYaw = v[0];
        viewPitch = v[1];
        hasView = true;
        fire = false;
        aimPoint = null;
        return true;
    }

    /** Команда игрока: куда смотрит камера и зажат ли огонь. */
    public void control(@Nullable Entity player, float yaw, float pitch, boolean fire) {
        if (!(level instanceof ServerLevel sl)) return;
        lastControl = sl.getGameTime();
        float ny = net.minecraft.util.Mth.wrapDegrees(yaw), np = Math.max(-90, Math.min(90, pitch));
        if (!hasView || Math.abs(net.minecraft.util.Mth.wrapDegrees(ny - viewYaw)) > 0.05f || Math.abs(np - viewPitch) > 0.05f) {
            viewDirty = true;
            setChanged();
        }
        viewYaw = ny;
        viewPitch = np;
        hasView = true;
        // голову видят другие игроки: рассылка не чаще раза в 2 тика
        if (viewDirty && sl.getGameTime() - lastViewSync >= 2) {
            viewDirty = false;
            lastViewSync = sl.getGameTime();
            sl.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
        this.fire = fire;
        aimPoint = lookPoint(sl, player);
    }

    /** Задать управление без игрока (игровые тесты). */
    public void controlForTest(UUID who, float yaw, float pitch, boolean fire) {
        controller = who;
        control(null, yaw, pitch, fire);
    }

    public void stopControl(UUID who) {
        if (who.equals(controller)) {
            controller = null;
            controlMonitor = null;
            fire = false;
            aimPoint = null;
            lastControl = Long.MIN_VALUE / 2;
        }
    }

    /** Ручное наведение для вычислителей сети или null (никто не управляет). */
    @Nullable
    public ManualAim manualAim(long now) {
        if (controller(now) == null || aimPoint == null) return null;
        return new ManualAim(new Vec(aimPoint.x, aimPoint.y, aimPoint.z), fire, displayName().getString(),
                new Vec(aimVelocity.x, aimVelocity.y, aimVelocity.z));
    }

    @Nullable
    public Vec3 aimPoint() {
        return aimPoint;
    }

    /**
     * Точка взгляда: первая сущность, блок постройки Sable (корабль, поворотная часть) или блок мира на луче (только по
     * загруженным чанкам), иначе конец луча. Своя постройка (камера на ней) лучу не мешает. Для корабля и сущности
     * запоминается скорость точки - орудия ведут её с упреждением.
     */
    private Vec3 lookPoint(ServerLevel sl, @Nullable Entity viewer) {
        Vec3 eye = eye();
        Vec3 dir = Vec3.directionFromRotation(viewPitch, viewYaw);
        Vec3 end = eye.add(dir.scale(RANGE));
        BlockGetter blocks = loadedOnly(sl, worldPosition);
        BlockHitResult bh = blocks.clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE,
                CollisionContext.empty()));
        Vec3 stop = bh.getType() == HitResult.Type.MISS ? end : bh.getLocation();
        Vec3 vel = Vec3.ZERO;
        SableCompat.ShipHit ship = SableCompat.clipShips(sl, eye, stop, SableCompat.containingId(sl, worldPosition), blocks, true);
        if (ship != null) {
            stop = ship.point();
            vel = ship.velocity();
        }
        EntityHitResult eh = ProjectileUtil.getEntityHitResult(sl, viewer, eye, stop, new AABB(eye, stop).inflate(1.0),
                e -> e != viewer && !e.isSpectator() && e.isPickable(), 0.3f);
        if (eh != null) {
            Entity e = eh.getEntity();
            // скорость сущности - по её перемещению за прошлый тик (на земле - без вертикали)
            Vec3 d = e.position().subtract(e.xo, e.yo, e.zo);
            aimVelocity = e.onGround() ? new Vec3(d.x, 0, d.z) : d;
            return e.getBoundingBox().getCenter();
        }
        aimVelocity = vel;
        return stop;
    }

    /** Мир для луча: незагруженные чанки - пустота (луч не грузит чанки). */
    private static BlockGetter loadedOnly(ServerLevel sl, BlockPos self) {
        return new BlockGetter() {
            @Nullable
            @Override
            public BlockEntity getBlockEntity(BlockPos pos) {
                return sl.isLoaded(pos) ? sl.getBlockEntity(pos) : null;
            }

            @Override
            public BlockState getBlockState(BlockPos pos) {
                // луч начинается в центре своего блока - сама камера ему не помеха
                if (pos.equals(self)) return Blocks.AIR.defaultBlockState();
                return sl.isLoaded(pos) ? sl.getBlockState(pos) : Blocks.AIR.defaultBlockState();
            }

            @Override
            public FluidState getFluidState(BlockPos pos) {
                return sl.isLoaded(pos) ? sl.getFluidState(pos) : Fluids.EMPTY.defaultFluidState();
            }

            @Override
            public int getHeight() {
                return sl.getHeight();
            }

            @Override
            public int getMinBuildHeight() {
                return sl.getMinBuildHeight();
            }
        };
    }

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        tooltip.add(Component.literal("    ").append(Component.translatable("block.radar_fcs.camera").withStyle(ChatFormatting.GRAY)));
        tooltip.add(Component.literal("    ").append(displayName().copy().withStyle(ChatFormatting.AQUA)));
        if (network != null) tooltip.add(Component.literal("    ").append(Component.translatable("radar_fcs.camera.network",
                RadarLinkBlockEntity.shortName(network)).withStyle(ChatFormatting.GRAY)));
        return true;
    }

    // ------------------------------------------------------------ своя сеть (без линка)

    @Nullable
    public UUID network() {
        return network;
    }

    public void setNetwork(@Nullable UUID net) {
        if (java.util.Objects.equals(net, network)) return;
        unregister();
        network = net;
        setChanged();
        refresh();
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }

    public static void serverTick(net.minecraft.world.level.Level level, BlockPos pos, BlockState state, CameraBlockEntity be) {
        if (be.network != null && (!be.registered || level.getGameTime() % 20 == Math.floorMod(pos.hashCode(), 20))) be.refresh();
    }

    /** Камера сама - участник своей сети (как радарный линк на ней). */
    private void refresh() {
        if (level == null || level.isClientSide || network == null) return;
        dev.radarfcs.link.LinkRegistry.put(level, worldPosition, List.of(new dev.radarfcs.link.LinkRegistry.Member(worldPosition, worldPosition, network)));
        if (!registered) {
            registered = true;
            notifyNetwork(network);
        }
    }

    private void unregister() {
        if (level == null || level.isClientSide || !registered) return;
        dev.radarfcs.link.LinkRegistry.remove(level, worldPosition);
        registered = false;
        if (network != null) notifyNetwork(network);
    }

    /** Вычислители сети пересматривают аппаратуру сразу. */
    private void notifyNetwork(UUID net) {
        for (BlockPos p : dev.radarfcs.link.LinkRegistry.members(level, net)) {
            if (level.isLoaded(p) && level.getBlockEntity(p) instanceof FireControlBlockEntity fcs) fcs.requestDiscover();
        }
    }

    @Override
    public void onLoad() {
        super.onLoad();
        refresh();
    }

    @Override
    public void setRemoved() {
        unregister();
        super.setRemoved();
    }

    @Override
    public void onChunkUnloaded() {
        unregister();
        super.onChunkUnloaded();
    }

    /** Сеть камеры - в предмете при поломке (и обратно при установке). */
    @Override
    protected void collectImplicitComponents(net.minecraft.core.component.DataComponentMap.Builder components) {
        super.collectImplicitComponents(components);
        if (network != null) {
            CompoundTag t = new CompoundTag();
            t.putUUID("RadarNetwork", network);
            components.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.of(t));
        }
    }

    @Override
    protected void applyImplicitComponents(BlockEntity.DataComponentInput input) {
        super.applyImplicitComponents(input);
        var data = input.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
        if (data != null) {
            CompoundTag t = data.copyTag();
            if (t.hasUUID("RadarNetwork")) network = t.getUUID("RadarNetwork");
        }
    }

    @Override
    public void removeComponentsFromTag(CompoundTag tag) {
        super.removeComponentsFromTag(tag);
        tag.remove("Network");
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (!name.isEmpty()) tag.putString("Name", name);
        if (network != null) tag.putUUID("Network", network);
        if (hasView) {
            tag.putFloat("ViewYaw", viewYaw);
            tag.putFloat("ViewPitch", viewPitch);
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        name = tag.getString("Name");
        network = tag.hasUUID("Network") ? tag.getUUID("Network") : null;
        boolean had = hasView;
        float[] before = level != null && level.isClientSide && had ? renderView(0) : view();
        hasView = tag.contains("ViewYaw");
        if (hasView) {
            viewYaw = tag.getFloat("ViewYaw");
            viewPitch = tag.getFloat("ViewPitch");
        }
        if (level != null && level.isClientSide) {
            // плавный поворот от того, что видно сейчас
            float[] shown = clientViewTime == Long.MIN_VALUE / 2 ? view() : before;
            clientPrevYaw = shown[0];
            clientPrevPitch = shown[1];
            clientViewTime = level.getGameTime();
        }
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = super.getUpdateTag(registries);
        saveAdditional(tag, registries);
        return tag;
    }

    @Nullable
    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
