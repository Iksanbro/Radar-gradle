package dev.radarfcs.block;

import com.simibubi.create.content.contraptions.AssemblyException;
import com.simibubi.create.content.contraptions.IDisplayAssemblyExceptions;
import com.simibubi.create.content.kinetics.base.DirectionalKineticBlock;
import com.simibubi.create.content.kinetics.base.IRotate;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.transmission.sequencer.SequencerInstructions;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.CenteredSideValueBoxTransform;
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.INamedIconOptions;
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollOptionBehaviour;
import com.simibubi.create.foundation.gui.AllIcons;
import dev.radarfcs.FcsMod;
import dev.radarfcs.compat.Mods;
import dev.radarfcs.compat.SableCompat;
import dev.radarfcs.compat.sable.SableRotor;
import dev.radarfcs.core.Aim;
import dev.simulated_team.simulated.util.extra_kinetics.ExtraKinetics;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * Подшипник орудия - поворотный подшипник Create Aeronautics, но сервопривод гасит инерцию. Как у Aeronautics:
 * встроенное кольцо-шестерня (вторая кинетика блока через ExtraKinetics Simulated: зацепляется с шестернями рядом, без
 * нагрузки), угол накручивается от скорости кольца (convertToAngular, не быстрее предела подшипника Aeronautics),
 * секвенсорный редуктор поворачивает на заданный угол; кольцо закрутилось у несобранного - сборка; ПКМ - собрать или
 * разобрать; режимы блокировки (всегда / без сигнала / с сигналом / никогда) - без блокировки поворотная часть
 * свободна (только трение); при включении блокировки угол берётся текущий. Поворотная часть - на шарнире Sable, мотор
 * шарнира тянет её к углу (плавно между тиками). Отличие: демпфирование критическое (у Aeronautics - вдвое меньше,
 * часть раскачивается), жёсткость - от момента инерции всего, что поворачивается (вместе с подшипниками на части), и
 * цель с упреждением по скорости - часть не отстаёт на ходу и не перелетает угол; упёрлась - угол не накручивается.
 */
public class GunBearingBlockEntity extends KineticBlockEntity implements ExtraKinetics, IDisplayAssemblyExceptions {
    /** Собранные подшипники загруженного мира - для сервопривода на каждом шаге физики. */
    private static final Set<GunBearingBlockEntity> ACTIVE = Collections.newSetFromMap(new WeakHashMap<>());
    /** Собственная частота сервопривода (рад/с; у Aeronautics жёсткость 1600 = 40²) и демпфирование: 1 - критическое. */
    static final double OMEGA = 40, ZETA = 1;
    /** Трение свободного (не заблокированного) подшипника - как у Aeronautics. */
    static final double FRICTION = 0.3;
    /** Насколько угол может уйти вперёд фактического, градусов: дальше - часть упёрлась, кольцо проскальзывает. */
    private static final double MAX_LEAD = 10;

    /** Режим блокировки (панель сбоку): заблокирован - сервопривод держит угол, нет - часть крутится свободно. */
    public enum LockMode implements INamedIconOptions {
        LOCKED_ALWAYS(AllIcons.I_CONFIG_LOCKED, "locked_always"),
        LOCKED_DEFAULT(AllIcons.I_CONFIG_LOCKED, "locked_default"),
        UNLOCKED_DEFAULT(AllIcons.I_CONFIG_UNLOCKED, "unlocked_default"),
        UNLOCKED_ALWAYS(AllIcons.I_CONFIG_UNLOCKED, "unlocked_always");

        private final AllIcons icon;
        private final String key;

        LockMode(AllIcons icon, String key) {
            this.icon = icon;
            this.key = "radar_fcs.bearing.lock." + key;
        }

        /** Заблокирован ли подшипник при сигнале редстоуна signal. */
        public boolean shouldLock(int signal) {
            return switch (this) {
                case LOCKED_ALWAYS -> true;
                case UNLOCKED_ALWAYS -> false;
                case LOCKED_DEFAULT -> signal == 0;
                case UNLOCKED_DEFAULT -> signal > 0;
            };
        }

        @Override
        public AllIcons getIcon() {
            return icon;
        }

        @Override
        public String getTranslationKey() {
            return key;
        }
    }

    /** Встроенное кольцо-шестерня: вторая кинетика блока (Simulated ведёт её сеть, уровень и сохранение). */
    public static class Cog extends KineticBlockEntity implements ExtraKinetics.ExtraKineticsBlockEntity {
        private final GunBearingBlockEntity parent;

        public Cog(BlockEntityType<?> type, BlockPos pos, BlockState state, GunBearingBlockEntity parent) {
            // позиция - ExtraBlockPos: по ней Simulated отличает вторую кинетику блока (зацепление шестерён)
            super(type, new dev.simulated_team.simulated.util.extra_kinetics.ExtraBlockPos(pos), state);
            this.parent = parent;
        }

        @Override
        public KineticBlockEntity getParentBlockEntity() {
            return parent;
        }

        /**
         * Кольцо закрутилось у несобранного подшипника - собрать (кроме разобранного вручную: его собирают только ПКМ);
         * секвенсорный редуктор - поворот на заданный угол.
         */
        @Override
        public void onSpeedChanged(float previousSpeed) {
            super.onSpeedChanged(previousSpeed);
            if (speed != 0 && !parent.assembled() && !parent.holdDisassembled) parent.assembleNextTick = true;
            parent.sequencedAngleLimit = -1;
            if (sequenceContext != null && sequenceContext.instruction() == SequencerInstructions.TURN_ANGLE) {
                parent.sequencedAngleLimit = sequenceContext.getEffectiveValue(getTheoreticalSpeed());
            }
        }

        @Override
        protected boolean canPropagateDiagonally(IRotate block, BlockState state) {
            return true;
        }

        @Override
        public float calculateStressApplied() {
            return 0;
        }
    }

    private final Cog cog;
    /** Собрать (или разобрать) в следующем тике: ПКМ, ПКМ по пластине, закрутившееся кольцо. */
    public boolean assembleNextTick;
    /**
     * Разобран вручную: от кольца сам не собирается, только ПКМ. Иначе привод, который ведёт наводку, в следующем же
     * тике собрал бы постройку обратно (разборка и сборка постройки Sable в соседних тиках сбивали её у игроков и
     * обучение приводов орудия).
     */
    boolean holdDisassembled;
    /** Блок уезжает в собираемую постройку (то, на чём он стоит): пластину не трогать. */
    boolean moving;
    @Nullable private AssemblyException lastException;
    @Nullable private UUID subLevel;
    @Nullable private BlockPos platePos;
    /** Угол сервопривода сейчас и тиком раньше (градусы от положения при сборке). */
    private double target, lastTarget;
    /** Сколько ещё можно повернуть по команде секвенсорного редуктора, градусов (-1 - без предела). */
    double sequencedAngleLimit = -1;
    private boolean locked;
    private long stepTick = Long.MIN_VALUE;
    @Nullable private Object handle;
    private ScrollOptionBehaviour<LockMode> lockMode;
    /** Поворачиваемые под-уровни и их момент инерции (пересчёт раз в тик). */
    private final List<UUID> parts = new java.util.ArrayList<>();
    private double inertia = 10;
    private long partsTick = Long.MIN_VALUE;

    public GunBearingBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
        cog = new Cog(type, pos, state, this);
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
        super.addBehaviours(behaviours);
        lockMode = new ScrollOptionBehaviour<>(LockMode.class, Component.translatable("radar_fcs.bearing.lock_mode"), this,
                new CenteredSideValueBoxTransform((state, dir) -> dir.getAxis() != state.getValue(DirectionalKineticBlock.FACING).getAxis()));
        lockMode.value = LockMode.LOCKED_DEFAULT.ordinal();
        behaviours.add(lockMode);
    }

    // ---- ExtraKinetics (кольцо-шестерня) ----

    @Override
    public KineticBlockEntity getExtraKinetics() {
        return cog;
    }

    /** Кольцо - отдельная кинетика: с валом насквозь не связано (как у Aeronautics). */
    @Override
    public boolean shouldConnectExtraKinetics() {
        return false;
    }

    @Override
    public String getExtraKineticsSaveName() {
        return "Cogwheel";
    }

    // ---- состояние ----

    public Direction facing() {
        return getBlockState().getValue(DirectionalKineticBlock.FACING);
    }

    public boolean assembled() {
        return subLevel != null;
    }

    @Nullable
    public UUID subLevel() {
        return subLevel;
    }

    /** Пластина подшипника (в плоте поворотной части); null - не собран. */
    @Nullable
    public BlockPos platePos() {
        return platePos;
    }

    /** Первый блок поворотной части (перед пластиной), куда идёт вал насквозь; null - не собран. */
    @Nullable
    public BlockPos plotFront() {
        return platePos == null ? null : platePos.relative(facing());
    }

    /** Скорость кольца-шестерни, об/мин. */
    public float ringSpeed() {
        return cog.getSpeed();
    }

    public boolean locked() {
        return locked;
    }

    /** Угол сервопривода (градусы от положения при сборке). */
    public double target() {
        return target;
    }

    /** Фактический угол поворотной части относительно основания (NaN - не собран или под-уровня нет). */
    public double angle() {
        if (!(level instanceof ServerLevel sl) || subLevel == null || !Mods.SABLE) return Double.NaN;
        return SableRotor.angleDeg(sl, worldPosition, facing(), subLevel);
    }

    /** Предел скорости кольца - как у поворотного подшипника Aeronautics (их конфиг сервера), об/мин. */
    private static float maxSpeed() {
        try {
            return dev.simulated_team.simulated.service.SimConfigService.INSTANCE.server().blocks.maxSwivelBearingSpeed.getF();
        } catch (RuntimeException e) {
            return 256;
        }
    }

    private boolean tooFast() {
        return Math.abs(cog.getSpeed()) > maxSpeed();
    }

    /** Поворот за тик, градусов вокруг FACING: от кольца (не быстрее предела), с пределом секвенсорного редуктора. */
    public double degPerTick() {
        float max = maxSpeed();
        double d = convertToAngular(Math.max(-max, Math.min(max, cog.getSpeed())));
        if (facing().getAxisDirection() == Direction.AxisDirection.NEGATIVE) d = -d;
        if (sequencedAngleLimit >= 0) d = Math.max(-sequencedAngleLimit, Math.min(sequencedAngleLimit, d));
        return d;
    }

    // ---- тик ----

    @Override
    public void tick() {
        super.tick();
        cog.tick();
        if (level == null) return;
        if (level.isClientSide) {
            if (tooFast()) grinding();
            return;
        }
        if (!(level instanceof ServerLevel sl) || !Mods.SABLE) return;
        if (assembleNextTick) {
            assembleNextTick = false;
            if (assembled()) {
                holdDisassembled = true;                                       // до разборки: она переподключает кинетику
                disassemble(sl);
            } else {
                assemble(sl);
            }
        }
        if (!assembled()) return;
        if (!SableRotor.valid(handle)) {
            // после загрузки мира шарнир не сохранён: подключить снова, как только под-уровень загружен; пластины нет -
            // подшипник разобран
            if (platePos != null && sl.isLoaded(platePos) && !(sl.getBlockState(platePos).getBlock() instanceof GunBearingPlateBlock)
                    && SableRotor.exists(sl, subLevel)) {
                forgetAssembly(sl);
                return;
            }
            if (SableRotor.exists(sl, subLevel)) reattach(sl);
            if (!SableRotor.valid(handle)) return;
        }
        // блокировка: режим и сигнал редстоуна; включилась - угол берётся текущий (часть не рванёт к старому)
        boolean lock = lockMode.get().shouldLock(sl.getBestNeighborSignal(worldPosition));
        if (lock != locked) {
            locked = lock;
            if (getBlockState().getValue(GunBearingBlock.POWERED) != lock) {
                sl.setBlock(worldPosition, getBlockState().setValue(GunBearingBlock.POWERED, lock), 3);
            }
            if (lock) {
                double a = angle();
                if (!Double.isNaN(a)) target = lastTarget = a;
            }
            setChanged();
        }
        // физика Sable спит, пока постройки стоят: кольцо крутится - разбудить; шаг физики в этом тике не прошёл -
        // посчитать поворот здесь
        if (cog.getSpeed() != 0) {
            SableRotor.wake(sl, subLevel);
        }
        if (sl.getGameTime() - stepTick > 1) integrate(sl, sl.getGameTime());
        // вал насквозь: пластина в плоте подшипнику не сосед - если её постройка без вращения, а вал крутится,
        // переподключить кинетику (поставили новый блок, загрузился плот)
        if (sl.getGameTime() % 20 == 7 && platePos != null && getSpeed() != 0 && sl.isLoaded(platePos)
                && sl.getBlockEntity(platePos) instanceof KineticBlockEntity k && k.getSpeed() == 0) {
            detachKinetics();
            attachKinetics();
        }
    }

    /** Поворот за тик now (один раз за тик): угол накручивается от кольца; упёрлась часть - не дальше MAX_LEAD. */
    private void integrate(ServerLevel sl, long now) {
        if (now == stepTick) return;
        stepTick = now;
        lastTarget = target;
        if (!locked || SableRotor.paused(sl)) return;
        double step = degPerTick();
        if (step != 0) {
            target = Aim.wrap180(target + step);
            if (sequencedAngleLimit >= 0) sequencedAngleLimit = Math.max(0, sequencedAngleLimit - Math.abs(step));
            double actual = angle();
            if (!Double.isNaN(actual)) {
                double lead = Aim.wrap180(target - actual);
                if (Math.abs(lead) > MAX_LEAD) target = Aim.wrap180(actual + Math.copySign(MAX_LEAD, lead));
            }
            setChanged();
        }
    }

    /** Шаг физики: сервопривод к углу между прошлым и этим тиком, с упреждением по скорости; не заблокирован - трение. */
    private void physicsStep() {
        if (!(level instanceof ServerLevel sl) || subLevel == null || !SableRotor.valid(handle)) return;
        integrate(sl, sl.getGameTime());
        if (!locked) {
            SableRotor.free(handle, FRICTION);
            return;
        }
        double pt = Math.max(0, Math.min(1, SableRotor.partialPhysicsTick(sl)));
        double delta = Aim.wrap180(target - lastTarget);
        double goal = lastTarget + delta * pt;
        // что поворачивается и его момент инерции - раз в тик (поиск вложенных подшипников и масс - не на каждом шаге)
        if (partsTick != stepTick) {
            partsTick = stepTick;
            parts.clear();
            parts.add(subLevel);
            for (int i = 0; i < parts.size() && parts.size() < 32; i++) parts.addAll(childSubs(sl, parts.get(i)));
            inertia = Math.max(10, SableRotor.inertia(sl, worldPosition, facing(), parts));
        }
        double k = OMEGA * OMEGA * inertia, c = 2 * ZETA * OMEGA * inertia;
        // упреждение: на ходу демпфер тянет назад на c/k·скорость - цель на столько же вперёд
        double rate = Math.toRadians(delta) * 20;
        double goalRad = Math.toRadians(goal) + rate * c / k;
        // цель - рядом с текущим углом (без скачка через ±180°)
        double now = angle();
        if (!Double.isNaN(now)) goalRad = Math.toRadians(now) + Math.IEEEremainder(goalRad - Math.toRadians(now), 2 * Math.PI);
        SableRotor.servo(handle, goalRad, k, c);
        for (UUID id : parts) SableRotor.wake(sl, id);
    }

    // ---- сборка ----

    /** Собрать сейчас, если ещё не собран (игровые тесты; в игре - ПКМ или закрутившееся кольцо, в следующем тике). */
    public void assembleNow() {
        if (level instanceof ServerLevel sl && !assembled()) assemble(sl);
    }

    private void assemble(ServerLevel sl) {
        holdDisassembled = false;
        Direction f = facing();
        BlockState plate = GunBearings.PLATE.get().defaultBlockState().setValue(DirectionalKineticBlock.FACING, f);
        SableRotor.Assembled a;
        try {
            a = SableRotor.assemble(sl, worldPosition, f, plate);
        } catch (AssemblyException e) {
            lastException = e;
            sendData();
            return;
        }
        lastException = null;
        subLevel = a.subLevel();
        platePos = a.platePos();
        target = lastTarget = 0;
        locked = false;
        sequencedAngleLimit = -1;
        if (sl.getBlockEntity(platePos) instanceof GunBearingPlateBlockEntity p) p.setParent(worldPosition);
        sl.setBlock(worldPosition, getBlockState().setValue(GunBearingBlock.ASSEMBLED, true), 3);
        reattach(sl);
        // вал насквозь: соседом стала пластина в плоте
        detachKinetics();
        attachKinetics();
        setChanged();
        sendData();
    }

    private void disassemble(ServerLevel sl) {
        if (subLevel == null) return;
        if (SableRotor.containedIn(sl, worldPosition, subLevel)) {
            lastException = new AssemblyException(Component.translatable("radar_fcs.bearing.same_sublevel"));
            sendData();
            return;
        }
        UUID id = subLevel;
        BlockPos plate = platePos;
        SableRotor.remove(handle);
        handle = null;
        ACTIVE.remove(this);
        if (plate != null && sl.isLoaded(plate)) {
            if (sl.getBlockEntity(plate) instanceof GunBearingPlateBlockEntity p) p.detaching = true;
            if (sl.getBlockState(plate).getBlock() instanceof GunBearingPlateBlock) sl.removeBlock(plate, false);
        }
        if (plate != null && !SableRotor.disassemble(sl, id, plate, worldPosition)) {
            sl.playSound(null, worldPosition, SoundEvents.ANVIL_LAND, SoundSource.BLOCKS, 0.3f, 1.5f);
        }
        forgetAssembly(sl);
    }

    /** Подшипник больше не собран (разобрали, пластину потеряли). */
    private void forgetAssembly(ServerLevel sl) {
        SableRotor.remove(handle);
        handle = null;
        ACTIVE.remove(this);
        subLevel = null;
        platePos = null;
        target = lastTarget = 0;
        locked = false;
        BlockState st = getBlockState();
        if (st.getValue(GunBearingBlock.ASSEMBLED) || st.getValue(GunBearingBlock.POWERED)) {
            sl.setBlock(worldPosition, st.setValue(GunBearingBlock.ASSEMBLED, false).setValue(GunBearingBlock.POWERED, false), 3);
        }
        detachKinetics();
        attachKinetics();
        setChanged();
        sendData();
    }

    private void reattach(ServerLevel sl) {
        if (subLevel == null || platePos == null) return;
        SableRotor.remove(handle);
        handle = SableRotor.attach(sl, worldPosition, facing(), subLevel, platePos);
        if (handle != null) ACTIVE.add(this);
    }

    /** Подшипник переехал (собрали то, на чём он стоит): пластина узнаёт новое место, шарнир - сразу на новое основание. */
    void afterMove(ServerLevel sl) {
        moving = false;
        if (platePos != null && sl.isLoaded(platePos) && sl.getBlockEntity(platePos) instanceof GunBearingPlateBlockEntity p) {
            p.setParent(worldPosition);
        }
        if (subLevel != null && SableRotor.exists(sl, subLevel)) reattach(sl);
    }

    /** Пластина переехала. */
    void plateMoved(BlockPos newPlate) {
        platePos = newPlate.immutable();
        if (level instanceof ServerLevel sl) reattach(sl);
        setChanged();
    }

    /** Блок сломан (не переезд): шарнир и пластина снимаются - поворотная часть остаётся свободной постройкой. */
    @Override
    public void remove() {
        if (level instanceof ServerLevel sl && !moving && platePos != null && sl.isLoaded(platePos)) {
            if (sl.getBlockEntity(platePos) instanceof GunBearingPlateBlockEntity p) p.detaching = true;
            if (sl.getBlockState(platePos).getBlock() instanceof GunBearingPlateBlock) sl.removeBlock(platePos, false);
        }
        super.remove();
    }

    /** Блок снят, перенесён в под-уровень или чанк выгружен: шарнир снимается (новый BE подключит его снова). */
    @Override
    public void invalidate() {
        super.invalidate();
        if (Mods.SABLE && level != null && !level.isClientSide) SableRotor.remove(handle);
        handle = null;
        ACTIVE.remove(this);
    }

    // ---- вал насквозь: подшипник - пластина (в плоте, не сосед) ----

    @Override
    public List<BlockPos> addPropagationLocations(IRotate block, BlockState state, List<BlockPos> neighbours) {
        if (platePos != null && subLevel != null) neighbours.add(platePos);
        return super.addPropagationLocations(block, state, neighbours);
    }

    @Override
    public float propagateRotationTo(KineticBlockEntity target, BlockState stateFrom, BlockState stateTo, BlockPos diff,
                                     boolean connectedViaAxes, boolean connectedViaCogs) {
        if (platePos != null && subLevel != null && target.getBlockPos().equals(platePos)) return 1;
        return super.propagateRotationTo(target, stateFrom, stateTo, diff, connectedViaAxes, connectedViaCogs);
    }

    @Override
    public boolean isCustomConnection(KineticBlockEntity other, BlockState state, BlockState otherState) {
        return platePos != null && subLevel != null && other.getBlockPos().equals(platePos) || super.isCustomConnection(other, state, otherState);
    }

    @Override
    public float calculateStressApplied() {
        return 0;
    }

    // ---- шаги физики ----

    /** Сколько подшипников под этим (0 - стоит на земле или корабле). */
    private int depth(List<GunBearingBlockEntity> all) {
        if (!(level instanceof ServerLevel sl)) return 0;
        int d = 0;
        BlockPos at = worldPosition;
        java.util.Set<GunBearingBlockEntity> seen = new java.util.HashSet<>();
        while (d < 8) {
            UUID on = SableCompat.containingId(sl, at);
            if (on == null) break;
            GunBearingBlockEntity parent = null;
            for (GunBearingBlockEntity o : all) if (on.equals(o.subLevel) && seen.add(o)) parent = o;
            if (parent == null) break;
            d++;
            at = parent.worldPosition;
        }
        return d;
    }

    /** Под-уровни подшипников, стоящих на под-уровне parent (их поворотные части держатся на нём). */
    private static List<UUID> childSubs(ServerLevel sl, UUID parent) {
        List<UUID> out = new java.util.ArrayList<>();
        for (GunBearingBlockEntity be : List.copyOf(ACTIVE)) {
            if (be.isRemoved() || be.subLevel == null || be.level != sl) continue;
            if (parent.equals(SableCompat.containingId(sl, be.worldPosition))) out.add(be.subLevel);
        }
        return out;
    }

    /** Собранные подшипники (для тестов). */
    public static List<GunBearingBlockEntity> active() {
        return List.copyOf(ACTIVE);
    }

    public static void physicsStepAll(ServerLevel stepping, double dt) {
        // сначала внешние подшипники (на земле или корабле), потом стоящие на их поворотных частях
        List<GunBearingBlockEntity> order = new java.util.ArrayList<>();
        for (GunBearingBlockEntity be : ACTIVE) if (be.level == stepping || be.isRemoved()) order.add(be);
        java.util.Map<GunBearingBlockEntity, Integer> depth = new java.util.HashMap<>();
        for (GunBearingBlockEntity be : order) depth.put(be, be.depth(order));
        order.sort(java.util.Comparator.comparingInt(depth::get));
        for (GunBearingBlockEntity be : order) {
            if (be.isRemoved() || be.subLevel == null) {
                ACTIVE.remove(be);
                continue;
            }
            try {
                be.physicsStep();
            } catch (RuntimeException e) {
                FcsMod.LOG.warn("[radar_fcs] gun bearing servo failed at {}: {}", be.getBlockPos(), e.toString());
                ACTIVE.remove(be);
            }
        }
    }

    // ---- клиент, подсказки ----

    /** Кольцо крутят быстрее предела: скрежет и искры (как у Aeronautics). */
    private void grinding() {
        Level l = level;
        if (l == null || l.random.nextInt(4) != 0) return;
        var c = worldPosition.getCenter();
        for (int i = 0; i < 2; i++) {
            l.addParticle(ParticleTypes.CRIT, c.x + (l.random.nextDouble() - 0.5), c.y + (l.random.nextDouble() - 0.5),
                    c.z + (l.random.nextDouble() - 0.5), 0, 0.05, 0);
        }
        if (l.random.nextInt(5) == 0) l.playLocalSound(worldPosition, SoundEvents.GRINDSTONE_USE, SoundSource.BLOCKS, 0.25f, 1.6f, false);
    }

    @Override
    @Nullable
    public AssemblyException getLastAssemblyException() {
        return lastException;
    }

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        tooltip.add(Component.literal("    ").append(Component.translatable("block.radar_fcs.gun_bearing").withStyle(ChatFormatting.GRAY)));
        if (!assembled()) {
            tooltip.add(Component.literal("    ").append(Component.translatable("radar_fcs.bearing.not_assembled").withStyle(ChatFormatting.GOLD)));
        } else {
            tooltip.add(Component.literal("    ").append(Component.translatable("radar_fcs.bearing.angle",
                    String.format(Locale.ROOT, "%.1f", target)).withStyle(ChatFormatting.AQUA)));
            tooltip.add(Component.literal("    ").append(Component.translatable(locked ? "radar_fcs.bearing.locked"
                    : "radar_fcs.bearing.unlocked").withStyle(ChatFormatting.GRAY)));
        }
        if (tooFast()) {
            tooltip.add(Component.literal("    ").append(Component.translatable("radar_fcs.bearing.too_fast",
                    String.format(Locale.ROOT, "%.0f", maxSpeed())).withStyle(ChatFormatting.RED)));
        }
        addExceptionToTooltip(tooltip);
        super.addToGoggleTooltip(tooltip, isPlayerSneaking);
        return true;
    }

    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        if (subLevel != null) tag.putUUID("SubLevel", subLevel);
        if (platePos != null) tag.put("Plate", NbtUtils.writeBlockPos(platePos));
        tag.putDouble("Target", target);
        tag.putDouble("SequencedAngleLimit", sequencedAngleLimit);
        tag.putBoolean("Locked", locked);
        if (holdDisassembled) tag.putBoolean("HoldDisassembled", true);
        AssemblyException.write(tag, registries, lastException);
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        subLevel = tag.hasUUID("SubLevel") ? tag.getUUID("SubLevel") : null;
        platePos = NbtUtils.readBlockPos(tag, "Plate").orElse(null);
        target = lastTarget = tag.getDouble("Target");
        sequencedAngleLimit = tag.contains("SequencedAngleLimit") ? tag.getDouble("SequencedAngleLimit") : -1;
        locked = tag.getBoolean("Locked");
        holdDisassembled = tag.getBoolean("HoldDisassembled");
        lastException = AssemblyException.read(tag, registries);
    }
}
