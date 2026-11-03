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
    @Nullable private UUID controller;
    @Nullable private BlockPos controlMonitor;
    private long lastControl = Long.MIN_VALUE / 2;
    private float viewYaw, viewPitch;
    private boolean fire;
    @Nullable private Vec3 aimPoint;

    public CameraBlockEntity(BlockPos pos, BlockState state) {
        super(FcsRegistry.CAMERA_BE.get(), pos, state);
    }

    public Direction facing() {
        BlockState st = getBlockState();
        return st.hasProperty(CameraBlock.FACING) ? st.getValue(CameraBlock.FACING) : Direction.NORTH;
    }

    /** Глаз камеры в мире: перед объективом (на корабле Sable - через позу корабля). */
    public Vec3 eye() {
        Vec3 local = Vec3.atCenterOf(worldPosition).add(Vec3.atLowerCornerOf(facing().getNormal()).scale(0.55));
        return level == null ? local : SableCompat.toWorld(level, local);
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
        float[] v = defaultView();
        viewYaw = v[0];
        viewPitch = v[1];
        fire = false;
        aimPoint = null;
        return true;
    }

    /** Команда игрока: куда смотрит камера и зажат ли огонь. */
    public void control(@Nullable Entity player, float yaw, float pitch, boolean fire) {
        if (!(level instanceof ServerLevel sl)) return;
        lastControl = sl.getGameTime();
        viewYaw = yaw;
        viewPitch = Math.max(-90, Math.min(90, pitch));
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
        return new ManualAim(new Vec(aimPoint.x, aimPoint.y, aimPoint.z), fire, displayName().getString());
    }

    @Nullable
    public Vec3 aimPoint() {
        return aimPoint;
    }

    /** Точка взгляда: первая сущность или блок на луче (только по загруженным чанкам), иначе конец луча. */
    private Vec3 lookPoint(ServerLevel sl, @Nullable Entity viewer) {
        Vec3 eye = eye();
        Vec3 dir = Vec3.directionFromRotation(viewPitch, viewYaw);
        Vec3 end = eye.add(dir.scale(RANGE));
        BlockHitResult bh = loadedOnly(sl).clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE,
                CollisionContext.empty()));
        Vec3 stop = bh.getType() == HitResult.Type.MISS ? end : bh.getLocation();
        EntityHitResult eh = ProjectileUtil.getEntityHitResult(sl, viewer, eye, stop, new AABB(eye, stop).inflate(1.0),
                e -> e != viewer && !e.isSpectator() && e.isPickable(), 0.3f);
        return eh != null ? eh.getEntity().getBoundingBox().getCenter() : stop;
    }

    /** Мир для луча: незагруженные чанки - пустота (луч не грузит чанки). */
    private static BlockGetter loadedOnly(ServerLevel sl) {
        return new BlockGetter() {
            @Nullable
            @Override
            public BlockEntity getBlockEntity(BlockPos pos) {
                return sl.isLoaded(pos) ? sl.getBlockEntity(pos) : null;
            }

            @Override
            public BlockState getBlockState(BlockPos pos) {
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
        return true;
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (!name.isEmpty()) tag.putString("Name", name);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        name = tag.getString("Name");
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
