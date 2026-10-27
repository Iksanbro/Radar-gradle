package dev.radarfcs.gametest;

import dev.radarfcs.FcsConfig;
import dev.radarfcs.FcsDev;
import dev.radarfcs.FcsMod;
import dev.radarfcs.block.FireControlBlockEntity;
import dev.radarfcs.fcs.MountState;
import dev.radarfcs.fcs.Status;
import dev.radarfcs.registry.FcsRegistry;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import rbasamoyai.createbigcannons.cannons.autocannon.breech.AbstractAutocannonBreechBlockEntity;
import rbasamoyai.createbigcannons.cannons.ICannonBlockEntity;
import rbasamoyai.createbigcannons.cannons.big_cannons.IBigCannonBlockEntity;
import rbasamoyai.createbigcannons.config.CBCConfigs;
import rbasamoyai.createbigcannons.munitions.AbstractCannonProjectile;

/**
 * Игровые тесты на настоящем сервере с Create, CBC и Create Radars:
 * сборка пушки, наведение, выстрел и попадание по движущейся цели; отсев неподвижных целей;
 * транспондер «свой»; цели с радара Create Radars; автопушка.
 */
@GameTestHolder(FcsMod.MODID)
@PrefixGameTestTemplate(false)
public class FcsGameTests {

    private static final String ARENA = "arena";

    // ----------------------------------------------------------------- настройка

    static void commonConfig() {
        FcsConfig.MIN_RANGE.set(4.0);
        FcsConfig.GLOW_TARGETS.set(true);
        FcsConfig.MARK_TARGETS.set(true);
        FcsConfig.REQUIRE_LOS.set(true);
        FcsConfig.LOG_SHOTS.set(true);
        try {
            CBCConfigs.server().failure.disableAllFailure.set(true);   // без случайных разрывов стволов
        } catch (Throwable t) {
            FcsMod.LOG.warn("[radar_fcs test] could not disable CBC failures: {}", t.toString());
        }
    }

    /** Вычислители, созданные тестами: после партии их убираем, чтобы они не стреляли по соседним тестам. */
    static final java.util.List<BlockPos> CREATED = new java.util.ArrayList<>();

    /** Чанки, загруженные тестами принудительно (трассы быстрых целей выходят за площадку теста); остаются загруженными. */
    static final java.util.List<long[]> FORCED = new java.util.ArrayList<>();

    static void forceArea(GameTestHelper h, BlockPos fromRel, BlockPos toRel) {
        BlockPos a = h.absolutePos(fromRel), b = h.absolutePos(toRel);
        ServerLevel l = h.getLevel();
        for (int cx = Math.min(a.getX(), b.getX()) >> 4; cx <= Math.max(a.getX(), b.getX()) >> 4; cx++) {
            for (int cz = Math.min(a.getZ(), b.getZ()) >> 4; cz <= Math.max(a.getZ(), b.getZ()) >> 4; cz++) {
                if (l.setChunkForced(cx, cz, true)) FORCED.add(new long[]{cx, cz});
            }
        }
    }

    static void cleanup(ServerLevel level) {
        // принудительную загрузку не снимаем: те же чанки могут уже принадлежать площадке следующего теста
        FORCED.clear();
        // блоки мода (вычислители, радары, мониторы): иначе они работают и в соседних тестах
        for (BlockPos p : CREATED) {
            if (FcsMod.MODID.equals(BuiltInRegistries.BLOCK.getKey(level.getBlockState(p).getBlock()).getNamespace())) {
                level.setBlock(p, Blocks.AIR.defaultBlockState(), 3);
            }
        }
        CREATED.clear();
        java.util.List<net.minecraft.world.entity.Entity> doomed = new java.util.ArrayList<>();
        for (var e : level.getAllEntities()) {
            if (e instanceof net.minecraft.world.entity.monster.Husk || e instanceof Projectile
                    || e instanceof net.minecraft.world.entity.animal.Cow || e instanceof net.minecraft.world.entity.vehicle.Boat) doomed.add(e);
        }
        doomed.forEach(net.minecraft.world.entity.Entity::discard);
        TargetGameTests.removeMockPlayers(level);
    }

    /** Тесты баллистики: цели без радара (FcsDev.radarless), чтобы не зависеть от обзора радара. */
    static void direct(ServerLevel level) {
        cleanup(level);
        commonConfig();
        FcsDev.radarless = true;
        FcsDev.radarlessRange = 80;
    }

    static void direct(ServerLevel level, double range) {
        direct(level);
        FcsDev.radarlessRange = range;
    }

    @BeforeBatch(batch = "big") public static void b1(ServerLevel l) { direct(l); }
    @AfterBatch(batch = "big") public static void a1(ServerLevel l) { cleanup(l); }
    @BeforeBatch(batch = "stationary") public static void b2(ServerLevel l) { direct(l); }
    @AfterBatch(batch = "stationary") public static void a2(ServerLevel l) { cleanup(l); }
    @BeforeBatch(batch = "iff") public static void b3(ServerLevel l) { direct(l); }
    @AfterBatch(batch = "iff") public static void a3(ServerLevel l) { cleanup(l); }
    @BeforeBatch(batch = "auto") public static void b4(ServerLevel l) { direct(l); }
    @AfterBatch(batch = "auto") public static void a4(ServerLevel l) { cleanup(l); }

    @BeforeBatch(batch = "rocket") public static void b6(ServerLevel l) { direct(l); }
    @AfterBatch(batch = "rocket") public static void a6(ServerLevel l) { cleanup(l); }

    @BeforeBatch(batch = "ship") public static void b7(ServerLevel l) { direct(l); }
    @AfterBatch(batch = "ship") public static void a7(ServerLevel l) { cleanup(l); }
    @BeforeBatch(batch = "shipiff") public static void b8(ServerLevel l) { direct(l); }
    @AfterBatch(batch = "shipiff") public static void a8(ServerLevel l) { cleanup(l); }

    @BeforeBatch(batch = "fastauto") public static void b9(ServerLevel l) { direct(l, 160); }
    @AfterBatch(batch = "fastauto") public static void a9(ServerLevel l) { cleanup(l); }
    @BeforeBatch(batch = "fastauto100") public static void b12(ServerLevel l) { direct(l, 160); }
    @AfterBatch(batch = "fastauto100") public static void a12(ServerLevel l) { cleanup(l); }
    @BeforeBatch(batch = "fastdive") public static void b13(ServerLevel l) { direct(l, 160); }
    @AfterBatch(batch = "fastdive") public static void a13(ServerLevel l) { cleanup(l); }
    @BeforeBatch(batch = "fastship") public static void b10(ServerLevel l) { direct(l, 160); }
    @AfterBatch(batch = "fastship") public static void a10(ServerLevel l) { cleanup(l); }
    @BeforeBatch(batch = "nopower") public static void b11(ServerLevel l) { direct(l); }
    @AfterBatch(batch = "nopower") public static void a11(ServerLevel l) { cleanup(l); }

    @BeforeBatch(batch = "radar")
    public static void beforeRadar(ServerLevel level) {
        cleanup(level);
        commonConfig();
        FcsDev.radarless = false;
    }

    @AfterBatch(batch = "radar") public static void a5(ServerLevel l) { cleanup(l); }

    // ------------------------------------------------------------------ строители

    static Block block(String id) {
        return BuiltInRegistries.BLOCK.get(ResourceLocation.parse(id));
    }

    static void floor(GameTestHelper h) {
        BlockState stone = Blocks.SMOOTH_STONE.defaultBlockState();
        for (int x = 0; x < 64; x++) for (int z = 0; z < 64; z++) h.setBlock(new BlockPos(x, 0, z), stone);
    }

    /**
     * Большая чугунная пушка на станке: казённик, chambers каморы с порохом, barrels стволов, ядро в первом стволе.
     * Пушка смотрит в сторону dir. Станок в mount, ось вращения на 2 блока выше.
     */
    static void bigCannon(GameTestHelper h, BlockPos mount, Direction dir, int chambers, int barrels, boolean load) {
        bigCannon(h, mount, dir, chambers, barrels, load, true);
    }

    /**
     * Контроллеры наводки у станка (горизонтальной - под ним, вертикальной - сбоку) и творческие моторы на их валах,
     * rpm об/мин. Без контроллеров и вращения вычислитель не наводит (requireRotation).
     */
    static void powerMount(GameTestHelper h, BlockPos mount, Direction dir, int rpm) {
        controllers(h, mount, dir);
        motors(h, mount, dir, rpm);
    }

    /** Контроллеры наводки без вращения. */
    static void controllers(GameTestHelper h, BlockPos mount, Direction dir) {
        h.setBlock(mount.below(), FcsRegistry.YAW_CONTROLLER.get().defaultBlockState()
                .setValue(com.simibubi.create.content.kinetics.base.DirectionalKineticBlock.FACING, Direction.UP));
        Direction side = dir.getClockWise();
        h.setBlock(mount.relative(side), FcsRegistry.PITCH_CONTROLLER.get().defaultBlockState()
                .setValue(com.simibubi.create.content.kinetics.base.DirectionalKineticBlock.FACING, side.getOpposite()));
        CREATED.add(h.absolutePos(mount.below()));
        CREATED.add(h.absolutePos(mount.relative(side)));
    }

    /** Моторы на валы контроллеров наводки: под горизонтальным и за вертикальным. */
    static void motors(GameTestHelper h, BlockPos mount, Direction dir, int rpm) {
        motor(h, mount.below(2), Direction.UP, rpm);
        Direction side = dir.getClockWise();
        motor(h, mount.relative(side, 2), side.getOpposite(), rpm);
    }

    static void motor(GameTestHelper h, BlockPos rel, Direction facing, int rpm) {
        h.setBlock(rel, block("create:creative_motor").defaultBlockState().setValue(BlockStateProperties.FACING, facing));
        if (h.getLevel().getBlockEntity(h.absolutePos(rel))
                instanceof com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity m) {
            m.generatedSpeed.setValue(rpm);
        } else {
            throw new GameTestAssertException("creative motor missing at " + rel);
        }
    }

    static void bigCannon(GameTestHelper h, BlockPos mount, Direction dir, int chambers, int barrels, boolean load, boolean power) {
        h.setBlock(mount, block("createbigcannons:cannon_mount").defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, dir));
        if (power) powerMount(h, mount, dir, 256);
        else controllers(h, mount, dir);
        BlockPos pivot = mount.above(2);
        Direction facing = dir.getOpposite();
        h.setBlock(pivot.relative(dir, -chambers), block("createbigcannons:cast_iron_cannon_end").defaultBlockState()
                .setValue(BlockStateProperties.FACING, facing));
        for (int i = 0; i < chambers; i++) {
            h.setBlock(pivot.relative(dir, -i), block("createbigcannons:cast_iron_cannon_chamber").defaultBlockState()
                    .setValue(BlockStateProperties.FACING, facing));
        }
        for (int i = 1; i <= barrels; i++) {
            h.setBlock(pivot.relative(dir, i), block("createbigcannons:cast_iron_cannon_barrel").defaultBlockState()
                    .setValue(BlockStateProperties.FACING, facing));
        }
        connect(h, pivot.relative(dir, -chambers), dir, chambers + barrels + 1);
        if (!load) return;
        BlockState powder = block("createbigcannons:powder_charge").defaultBlockState()
                .setValue(BlockStateProperties.AXIS, dir.getAxis());
        for (int i = 0; i < chambers; i++) loadInto(h, pivot.relative(dir, -i), powder);
        loadInto(h, pivot.relative(dir, 1), block("createbigcannons:solid_shot").defaultBlockState()
                .setValue(BlockStateProperties.FACING, dir));
    }

    /** Соединить соседние блоки пушки вдоль dir (как при установке игроком). */
    private static void connect(GameTestHelper h, BlockPos fromRel, Direction dir, int count) {
        for (int i = 0; i + 1 < count; i++) {
            BlockPos a = h.absolutePos(fromRel.relative(dir, i)), b = h.absolutePos(fromRel.relative(dir, i + 1));
            if (h.getLevel().getBlockEntity(a) instanceof ICannonBlockEntity<?> ca
                    && h.getLevel().getBlockEntity(b) instanceof ICannonBlockEntity<?> cb) {
                ca.cannonBehavior().setConnectedFace(dir, true);
                cb.cannonBehavior().setConnectedFace(dir.getOpposite(), true);
                h.getLevel().getBlockEntity(a).setChanged();
                h.getLevel().getBlockEntity(b).setChanged();
            } else {
                throw new GameTestAssertException("cannon blocks missing at " + fromRel.relative(dir, i));
            }
        }
    }

    private static void loadInto(GameTestHelper h, BlockPos rel, BlockState munition) {
        BlockPos abs = h.absolutePos(rel);
        if (!(h.getLevel().getBlockEntity(abs) instanceof IBigCannonBlockEntity cbe)) {
            throw new GameTestAssertException("no cannon block entity at " + rel);
        }
        cbe.cannonBehavior().loadBlock(new StructureBlockInfo(abs, munition, new CompoundTag()));
        h.getLevel().getBlockEntity(abs).setChanged();
    }

    /**
     * Чугунная автопушка как в ponder-сцене CBC: казённик позади оси, пружина отката на оси, затем стволы.
     * В казённик кладутся пулемётные патроны.
     */
    static void autocannon(GameTestHelper h, BlockPos mount, Direction dir, int barrels, int rounds) {
        autocannon(h, mount, dir, "cast_iron", barrels, rounds, "createbigcannons:machine_gun_round");
    }

    private static void autocannon(GameTestHelper h, BlockPos mount, Direction dir, String material, int barrels, int rounds,
                                   String ammo) {
        h.setBlock(mount, block("createbigcannons:cannon_mount").defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, dir));
        powerMount(h, mount, dir, 256);
        BlockPos pivot = mount.above(2);
        h.setBlock(pivot.relative(dir, -1), block("createbigcannons:" + material + "_autocannon_breech").defaultBlockState()
                .setValue(BlockStateProperties.FACING, dir));
        h.setBlock(pivot, block("createbigcannons:" + material + "_autocannon_recoil_spring").defaultBlockState()
                .setValue(BlockStateProperties.FACING, dir));
        for (int i = 1; i <= barrels; i++) {
            h.setBlock(pivot.relative(dir, i), block("createbigcannons:" + material + "_autocannon_barrel").defaultBlockState()
                    .setValue(BlockStateProperties.FACING, dir));
        }
        connect(h, pivot.relative(dir, -1), dir, barrels + 2);
        BlockPos abs = h.absolutePos(pivot.relative(dir, -1));
        if (h.getLevel().getBlockEntity(abs) instanceof AbstractAutocannonBreechBlockEntity breech) {
            for (int i = 0; i < rounds; i++) breech.getInputBuffer().add(autocannonAmmo(ammo));
            breech.setChanged();
        } else {
            throw new GameTestAssertException("no autocannon breech at " + pivot.relative(dir, -1));
        }
    }

    /** Патрон автопушки: пулемётный - как есть, снаряд автопушки (бронебойный и др.) - в снаряженной гильзе. */
    static net.minecraft.world.item.ItemStack autocannonAmmo(String id) {
        var item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(id));
        if (id.endsWith("machine_gun_round")) return new net.minecraft.world.item.ItemStack(item);
        var cart = new net.minecraft.world.item.ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse("createbigcannons:autocannon_cartridge")));
        rbasamoyai.createbigcannons.munitions.autocannon.AutocannonCartridgeItem.writeProjectile(new net.minecraft.world.item.ItemStack(item), cart);
        return cart;
    }

    // ------------------------------------------------------------ пушки из сборки пользователя

    /**
     * Роторная пушка CBC Neo Warfare: казённик позади оси, подшипник на оси, затем стволы; патроны - в казённик.
     * @return false, если мода нет
     */
    static boolean rotaryCannon(GameTestHelper h, BlockPos mount, Direction dir, String material, int barrels, int rounds) {
        Block breech = block("cbcmodernwarfare:" + material + "_rotarycannon_breech");
        if (breech == Blocks.AIR) return false;
        h.setBlock(mount, block("createbigcannons:cannon_mount").defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, dir));
        powerMount(h, mount, dir, 256);
        BlockPos pivot = mount.above(2);
        h.setBlock(pivot.relative(dir, -1), breech.defaultBlockState().trySetValue(BlockStateProperties.FACING, dir));
        h.setBlock(pivot, block("cbcmodernwarfare:" + material + "_rotarycannon_bearing").defaultBlockState()
                .trySetValue(BlockStateProperties.FACING, dir));
        for (int i = 1; i <= barrels; i++) {
            h.setBlock(pivot.relative(dir, i), block("cbcmodernwarfare:" + material + "_rotarycannon_barrel").defaultBlockState()
                    .trySetValue(BlockStateProperties.FACING, dir));
        }
        connect(h, pivot.relative(dir, -1), dir, barrels + 2);
        var be = h.getLevel().getBlockEntity(h.absolutePos(pivot.relative(dir, -1)));
        if (!(dev.radarfcs.compat.Reflect.call0(be, "getInputBuffer") instanceof java.util.Deque<?> q))
            throw new GameTestAssertException("no rotary cannon breech input");
        @SuppressWarnings("unchecked") java.util.Deque<net.minecraft.world.item.ItemStack> qq = (java.util.Deque<net.minecraft.world.item.ItemStack>) q;
        var item = BuiltInRegistries.ITEM.get(ResourceLocation.parse("createbigcannons:machine_gun_round"));
        for (int i = 0; i < rounds; i++) qq.add(new net.minecraft.world.item.ItemStack(item));
        be.setChanged();
        return true;
    }

    /** Снаряженный патрон средней пушки Neo Warfare (гильза с бронебойным снарядом). */
    static net.minecraft.world.item.ItemStack mediumCartridge() {
        var cart = BuiltInRegistries.ITEM.get(ResourceLocation.parse("cbcmodernwarfare:ap_mediumcannon_cartridge"));
        Object st = dev.radarfcs.compat.Reflect.call0(cart, "getCreativeTabCartridgeItem");
        return st instanceof net.minecraft.world.item.ItemStack is ? is : new net.minecraft.world.item.ItemStack(cart);
    }

    /** Средняя пушка Neo Warfare: казённик позади оси, откатный ствол на оси, затем стволы. */
    static boolean mediumCannon(GameTestHelper h, BlockPos mount, Direction dir, String material, int barrels) {
        Block breech = block("cbcmodernwarfare:" + material + "_mediumcannon_breech");
        if (breech == Blocks.AIR) return false;
        h.setBlock(mount, block("createbigcannons:cannon_mount").defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, dir));
        powerMount(h, mount, dir, 256);
        BlockPos pivot = mount.above(2);
        h.setBlock(pivot.relative(dir, -1), breech.defaultBlockState().trySetValue(BlockStateProperties.FACING, dir));
        h.setBlock(pivot, block("cbcmodernwarfare:" + material + "_mediumcannon_recoil_barrel").defaultBlockState()
                .trySetValue(BlockStateProperties.FACING, dir));
        for (int i = 1; i <= barrels; i++) {
            h.setBlock(pivot.relative(dir, i), block("cbcmodernwarfare:" + material + "_mediumcannon_barrel").defaultBlockState()
                    .trySetValue(BlockStateProperties.FACING, dir));
        }
        connect(h, pivot.relative(dir, -1), dir, barrels + 2);
        var be = h.getLevel().getBlockEntity(h.absolutePos(pivot.relative(dir, -1)));
        setMediumInput(be, mediumCartridge());
        return true;
    }

    static void setMediumInput(Object breech, net.minecraft.world.item.ItemStack stack) {
        try {
            breech.getClass().getMethod("setInputBuffer", net.minecraft.world.item.ItemStack.class).invoke(breech, stack);
        } catch (ReflectiveOperationException e) {
            throw new GameTestAssertException("cannot load medium cannon: " + e);
        }
    }

    /** Автомат заряжания средней пушки: через reloadTicks после выстрела кладёт новый патрон в казённик. */
    static void mediumAutoloader(GameTestHelper h, BlockPos mountRel, int reloadTicks) {
        AtomicInteger since = new AtomicInteger();
        h.onEachTick(() -> {
            var cm = dev.radarfcs.compat.CbcMount.of(h.getLevel().getBlockEntity(h.absolutePos(mountRel)));
            var c = cm == null ? null : cm.cannon();
            if (c == null) return;
            Object breech = c.presentBlockEntities.get(c.getStartPos());
            Object in = dev.radarfcs.compat.Reflect.call0(breech, "getInputBuffer");
            boolean loaded = in instanceof net.minecraft.world.item.ItemStack st && !st.isEmpty()
                    && dev.radarfcs.compat.Reflect.call0(st.getItem(), "getCreativeTabCartridgeItem") != null
                    && BuiltInRegistries.ITEM.getKey(st.getItem()).getPath().startsWith("ap_");
            if (loaded) { since.set(0); return; }
            if (since.incrementAndGet() >= reloadTicks) setMediumInput(breech, mediumCartridge());
        });
    }

    /**
     * Большая пушка со скорострельным затвором: затвор позади оси, на оси ствол с картриджем, дальше
     * нарезные стволы CBC-AT (снаряд в первом). Картридж - полный (сила 4).
     */
    private static boolean qfRifledCannon(GameTestHelper h, BlockPos mount, Direction dir, String material, int rifled) {
        Block rifledBlock = block("cbc_at:" + material + "_rifled_barrel");
        if (rifledBlock == Blocks.AIR) return false;
        h.setBlock(mount, block("createbigcannons:cannon_mount").defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, dir));
        powerMount(h, mount, dir, 256);
        BlockPos pivot = mount.above(2);
        Direction facing = dir.getOpposite();
        h.setBlock(pivot.relative(dir, -1), block("createbigcannons:" + material + "_quickfiring_breech").defaultBlockState()
                .trySetValue(BlockStateProperties.FACING, facing));
        h.setBlock(pivot, block("createbigcannons:" + material + "_cannon_barrel").defaultBlockState().setValue(BlockStateProperties.FACING, facing));
        for (int i = 1; i <= rifled; i++) h.setBlock(pivot.relative(dir, i), rifledBlock.defaultBlockState().trySetValue(BlockStateProperties.FACING, facing));
        connect(h, pivot.relative(dir, -1), dir, rifled + 2);
        loadQf(h.getLevel(), h.absolutePos(pivot), h.absolutePos(pivot.relative(dir, 1)), dir, true);
        return true;
    }

    static StructureBlockInfo fullCartridge(BlockPos pos, Direction dir) {
        BlockState st = block("createbigcannons:big_cartridge").defaultBlockState()
                .trySetValue(BlockStateProperties.FACING, dir)
                .trySetValue(rbasamoyai.createbigcannons.munitions.big_cannon.propellant.BigCartridgeBlock.FILLED, true);
        CompoundTag nbt = new CompoundTag();
        nbt.putInt("Power", 4);                                        // CBC 5.11.7
        CompoundTag comps = new CompoundTag();
        comps.putInt("createbigcannons:power", 4);                     // CBC 5.11.3
        nbt.put("components", comps);
        return new StructureBlockInfo(pos, st, nbt);
    }

    /** Зарядить картридж и снаряд в блоки пушки: в мире (world = true) или в собранном контрапшене (локальные позиции). */
    static void loadQf(ServerLevel level, BlockPos cartridgePos, BlockPos shellPos, Direction dir, boolean world) {
        if (!(level.getBlockEntity(cartridgePos) instanceof IBigCannonBlockEntity c) || !(level.getBlockEntity(shellPos) instanceof IBigCannonBlockEntity sh))
            throw new GameTestAssertException("no cannon blocks for quick-firing load");
        c.cannonBehavior().loadBlock(fullCartridge(cartridgePos, dir));
        sh.cannonBehavior().loadBlock(new StructureBlockInfo(shellPos, block("createbigcannons:solid_shot").defaultBlockState()
                .setValue(BlockStateProperties.FACING, dir), new CompoundTag()));
        level.getBlockEntity(cartridgePos).setChanged();
        level.getBlockEntity(shellPos).setChanged();
    }

    /** Автомат заряжания скорострельной пушки: через reloadTicks после выстрела - новый картридж и снаряд. */
    static void qfAutoloader(GameTestHelper h, BlockPos mountRel, Direction dir, int reloadTicks) {
        AtomicInteger since = new AtomicInteger();
        h.onEachTick(() -> {
            var cm = dev.radarfcs.compat.CbcMount.of(h.getLevel().getBlockEntity(h.absolutePos(mountRel)));
            var c = cm == null ? null : cm.cannon();
            if (c == null) return;
            BlockPos cartLocal = BlockPos.ZERO, shellLocal = BlockPos.ZERO.relative(dir);
            if (!(c.presentBlockEntities.get(cartLocal) instanceof IBigCannonBlockEntity cart)
                    || !(c.presentBlockEntities.get(shellLocal) instanceof IBigCannonBlockEntity shell)) return;
            boolean loaded = !shell.cannonBehavior().block().state().isAir();
            if (loaded) { since.set(0); return; }
            if (since.incrementAndGet() < reloadTicks) return;
            cart.cannonBehavior().loadBlock(fullCartridge(cartLocal, dir));
            shell.cannonBehavior().loadBlock(new StructureBlockInfo(shellLocal, block("createbigcannons:solid_shot").defaultBlockState()
                    .setValue(BlockStateProperties.FACING, dir), new CompoundTag()));
        });
    }

    /** Ракетная установка CBC Advanced Technologies: казённик позади оси, направляющие дальше. */
    private static boolean rocketPod(GameTestHelper h, BlockPos mount, Direction dir, int rails, String rocketItem, int rockets) {
        Block breechBlock = block("cbc_at:cast_iron_rocket_pod_breech");
        if (breechBlock == Blocks.AIR) return false;                       // CBC-AT не установлен
        try {
            // CBC-AT 0.1.4c вызывает onRecoil(Vec3, AbstractContraptionEntity), которого нет в CBC 5.11.4+
            rbasamoyai.createbigcannons.cannon_control.ControlPitchContraption.class.getMethod("onRecoil",
                    Vec3.class, com.simibubi.create.content.contraptions.AbstractContraptionEntity.class);
        } catch (NoSuchMethodException e) {
            FcsMod.LOG.warn("[radar_fcs test] rocket: CBC-AT 0.1.4c is binary-incompatible with this CBC version, test skipped");
            return false;
        }
        h.setBlock(mount, block("createbigcannons:cannon_mount").defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, dir));
        powerMount(h, mount, dir, 256);
        BlockPos pivot = mount.above(2);
        h.setBlock(pivot.relative(dir, -1), breechBlock.defaultBlockState().trySetValue(BlockStateProperties.FACING, dir));
        for (int i = 0; i < rails; i++) {
            h.setBlock(pivot.relative(dir, i), block("cbc_at:cast_iron_rocket_pod_rail").defaultBlockState()
                    .trySetValue(BlockStateProperties.FACING, dir));
        }
        connect(h, pivot.relative(dir, -1), dir, rails + 1);
        var be = h.getLevel().getBlockEntity(h.absolutePos(pivot.relative(dir, -1)));
        try {
            @SuppressWarnings("unchecked")
            java.util.Deque<net.minecraft.world.item.ItemStack> q = (java.util.Deque<net.minecraft.world.item.ItemStack>)
                    be.getClass().getMethod("getInputBuffer").invoke(be);
            var item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(rocketItem));
            boolean fix = ModList.get().isLoaded("cbcatfix");
            for (int i = 0; i < rockets; i++) {
                var st = new net.minecraft.world.item.ItemStack(item);
                // исправление CBC-AT включает двигатель, только если в ракете есть топливо (как у собранной на верстаке)
                if (fix) net.minecraft.world.item.component.CustomData.update(net.minecraft.core.component.DataComponents.CUSTOM_DATA, st,
                        tag -> tag.putBoolean("CbcatFixAssemblyFuel", true));
                q.add(st);
            }
            be.setChanged();
        } catch (ReflectiveOperationException e) {
            throw new GameTestAssertException("cannot load rocket pod: " + e);
        }
        return true;
    }

    /** Вычислитель, подключённый радарными линками ко всем станкам площадки (станки ставятся раньше). */
    static FireControlBlockEntity fcs(GameTestHelper h, BlockPos rel) {
        FireControlBlockEntity be = fcsBare(h, rel);
        // GameTestHelper.relativePos считает от другого начала, чем absolutePos: смещение - от absolutePos(0, 0, 0)
        BlockPos origin = h.absolutePos(BlockPos.ZERO);
        for (BlockPos m : mountsInTest(h)) linkMount(h, m.subtract(origin), rel);
        return be;
    }

    /** Вычислитель без линков. */
    static FireControlBlockEntity fcsBare(GameTestHelper h, BlockPos rel) {
        h.setBlock(rel, FcsRegistry.FIRE_CONTROL.get().defaultBlockState());
        if (h.getLevel().getBlockEntity(h.absolutePos(rel)) instanceof FireControlBlockEntity be) {
            CREATED.add(h.absolutePos(rel));
            return be;
        }
        throw new GameTestAssertException("FCS block entity missing");
    }

    /** Станки CBC площадки теста (абсолютные позиции). */
    static java.util.List<BlockPos> mountsInTest(GameTestHelper h) {
        AABB box = h.getBounds();
        java.util.List<BlockPos> out = new java.util.ArrayList<>();
        ServerLevel l = h.getLevel();
        for (int cx = (int) Math.floor(box.minX) >> 4; cx <= (int) Math.floor(box.maxX) >> 4; cx++) {
            for (int cz = (int) Math.floor(box.minZ) >> 4; cz <= (int) Math.floor(box.maxZ) >> 4; cz++) {
                var chunk = l.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) continue;
                for (var be : java.util.List.copyOf(chunk.getBlockEntities().values())) {
                    if (dev.radarfcs.compat.CbcMount.of(be) != null && box.contains(Vec3.atCenterOf(be.getBlockPos()))) out.add(be.getBlockPos());
                }
            }
        }
        return out;
    }

    /** Радарный линк станок - вычислитель на свободной боковой грани станка. */
    static void linkMount(GameTestHelper h, BlockPos mountRel, BlockPos fcsRel) {
        BlockState st = h.getBlockState(mountRel);
        Direction f = st.hasProperty(BlockStateProperties.HORIZONTAL_FACING) ? st.getValue(BlockStateProperties.HORIZONTAL_FACING) : Direction.NORTH;
        for (Direction d : new Direction[]{f.getCounterClockWise(), f.getOpposite(), f, f.getClockWise()}) {
            if (h.getBlockState(mountRel.relative(d)).isAir()) {
                link(h, mountRel, d, fcsRel);
                return;
            }
        }
        throw new GameTestAssertException("no free face for a radar link at mount " + mountRel);
    }

    /**
     * Связать attachedRel с otherRel радарной сетью: линк на грани face блока attachedRel встаёт в сеть линка на otherRel;
     * если на otherRel линка нет - на его свободную грань ставится линк новой сети (как игрок предметом).
     */
    static dev.radarfcs.block.RadarLinkBlockEntity link(GameTestHelper h, BlockPos attachedRel, Direction face, BlockPos otherRel) {
        java.util.UUID net = networkAt(h, otherRel);
        if (net == null) {
            net = java.util.UUID.randomUUID();
            placeLink(h, otherRel, freeFace(h, otherRel), net);
        }
        return placeLink(h, attachedRel, face, net);
    }

    /** Линк сети net на грани face блока attachedRel. */
    static dev.radarfcs.block.RadarLinkBlockEntity placeLink(GameTestHelper h, BlockPos attachedRel, Direction face, java.util.UUID net) {
        BlockPos at = attachedRel.relative(face);
        h.setBlock(at, FcsRegistry.RADAR_LINK.get().defaultBlockState().setValue(dev.radarfcs.block.RadarLinkBlock.FACING, face));
        CREATED.add(h.absolutePos(at));
        if (!(h.getLevel().getBlockEntity(h.absolutePos(at)) instanceof dev.radarfcs.block.RadarLinkBlockEntity be)) {
            throw new GameTestAssertException("no radar link at " + at);
        }
        be.setNetwork(net);
        return be;
    }

    /** Сеть первого линка, стоящего на блоке rel, или null. */
    @org.jetbrains.annotations.Nullable
    static java.util.UUID networkAt(GameTestHelper h, BlockPos rel) {
        BlockPos abs = h.absolutePos(rel);
        for (Direction d : Direction.values()) {
            if (h.getLevel().getBlockEntity(abs.relative(d)) instanceof dev.radarfcs.block.RadarLinkBlockEntity be
                    && be.attached().equals(abs) && be.network() != null) return be.network();
        }
        return null;
    }

    static Direction freeFace(GameTestHelper h, BlockPos rel) {
        for (Direction d : new Direction[]{Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, Direction.DOWN}) {
            if (h.getBlockState(rel.relative(d)).isAir()) return d;
        }
        throw new GameTestAssertException("no free face for a radar link at " + rel);
    }

    /** Цель без ИИ, которую тест двигает сам: туда-обратно вдоль оси Z с заданной скоростью. */
    static Mob movingTarget(GameTestHelper h, EntityType<? extends Mob> type, Vec3 startRel, double speedPerTick,
                                    double span) {
        Mob mob = h.spawn(type, startRel);
        mob.setNoAi(true);
        mob.setPersistenceRequired();
        Vec3 start = h.absoluteVec(startRel);
        AtomicInteger tick = new AtomicInteger();
        h.onEachTick(() -> {
            if (!mob.isAlive() || speedPerTick == 0) return;
            int t = tick.getAndIncrement();
            double period = span / speedPerTick;
            double phase = (t % (2 * period));
            double off = phase < period ? phase * speedPerTick : (2 * period - phase) * speedPerTick;
            mob.setPos(start.x, start.y, start.z + off);
            mob.setDeltaMovement(Vec3.ZERO);
        });
        return mob;
    }

    static void logEvery(GameTestHelper h, FireControlBlockEntity be, String name, int period) {
        AtomicInteger tick = new AtomicInteger();
        h.onEachTick(() -> {
            int t = tick.getAndIncrement();
            if (t % period != 0) return;
            StringBuilder sb = new StringBuilder();
            for (MountState m : be.mountStates()) {
                sb.append(String.format(" [%s %s yaw=%.1f pitch=%.1f err=%.2f miss=%.2f allowed=%.2f dist=%.1f learned=%d shots=%d muzzle=%.2f ammo=%s note=%s speedx=%.3f]",
                        m.mount == null ? "?" : m.mount.typeName(), m.status, m.cmdYaw, m.cmdPitch, m.aimError, m.predictedMiss, m.tolerance,
                        m.distance, m.correction.shots(), m.shotsSeen, m.learnedMuzzle,
                        m.estimate == null ? "-" : m.estimate.ammo() + (m.estimate.loaded() ? "" : "(empty)") + " v=" + String.format("%.2f", m.estimate.model().muzzleSpeed())
                                + " spread=" + String.format("%.2f", m.estimate.spread()) + " reach=" + String.format("%.2f", m.estimate.reach()),
                        m.note, m.correction.speedRatio()));
            }
            FcsMod.LOG.info("[radar_fcs test] {} t={} targets={} tracks={}{}", name, t, be.sensor().active().size(),
                    be.sensor().all().size(), sb);
        });
    }

    /** Следит за снарядами CBC и пишет в лог минимальный промах каждого по цели (расстояние до её габарита). */
    static void trackMiss(GameTestHelper h, Mob target, String name) {
        java.util.Map<Integer, double[]> best = new java.util.HashMap<>();
        java.util.Map<Integer, Vec3> lastPos = new java.util.HashMap<>();
        AtomicInteger tick = new AtomicInteger();
        h.onEachTick(() -> {
            int t = tick.getAndIncrement();
            AABB box = target.getBoundingBox();
            Vec3 c = box.getCenter();
            for (AbstractCannonProjectile p : h.getLevel().getEntitiesOfClass(AbstractCannonProjectile.class,
                    new AABB(c, c).inflate(80))) {
                Vec3 prev = lastPos.getOrDefault(p.getId(), p.position());
                Vec3 cur = p.position();
                double d = Double.MAX_VALUE;
                for (int i = 0; i <= 10; i++) {
                    Vec3 q = prev.lerp(cur, i / 10.0);
                    double dx = Math.max(0, Math.max(box.minX - q.x, q.x - box.maxX));
                    double dy = Math.max(0, Math.max(box.minY - q.y, q.y - box.maxY));
                    double dz = Math.max(0, Math.max(box.minZ - q.z, q.z - box.maxZ));
                    d = Math.min(d, Math.sqrt(dx * dx + dy * dy + dz * dz));
                }
                lastPos.put(p.getId(), cur);
                double[] b = best.computeIfAbsent(p.getId(), k -> new double[]{Double.MAX_VALUE, t});
                if (d < b[0]) b[0] = d;
                if (t % 5 == 0 || p.isInGround()) FcsMod.LOG.info("[radar_fcs test] {} proj#{} t={} pos={} vel={} target={} miss={}",
                        name, p.getId(), t, fmt(cur), fmt(p.getDeltaMovement()), fmt(c), String.format("%.2f", b[0]));
            }
        });
    }

    private static String fmt(Vec3 v) {
        return String.format("(%.2f %.2f %.2f)", v.x, v.y, v.z);
    }

    private static int projectilesNear(GameTestHelper h, double radius) {
        Vec3 c = h.absoluteVec(new Vec3(32, 4, 32));
        return h.getLevel().getEntitiesOfClass(Projectile.class, new AABB(c, c).inflate(radius)).size();
    }

    // ------------------------------------------------------------------- тесты

    /** Главный тест: пушка сама собирается, наводится с упреждением и попадает в движущуюся цель. */
    @GameTest(template = ARENA, batch = "big", timeoutTicks = 600)
    public static void bigCannonHitsMovingTarget(GameTestHelper h) {
        floor(h);
        bigCannon(h, new BlockPos(10, 1, 32), Direction.EAST, 2, 4, true);
        FireControlBlockEntity be = fcs(h, new BlockPos(6, 1, 28));
        Mob husk = movingTarget(h, EntityType.HUSK, new Vec3(48.5, 1, 22.5), 0.12, 18);
        float max = husk.getMaxHealth();
        logEvery(h, be, "big", 20);
        trackMiss(h, husk, "big");
        h.succeedWhen(() -> {
            if (husk.isAlive() && husk.getHealth() >= max) throw new GameTestAssertException("target not hit yet");
            MountState m = be.mountStates().isEmpty() ? null : be.mountStates().get(0);
            FcsMod.LOG.info("[radar_fcs test] big: HIT, health {} -> {}, shots seen {}", max, husk.getHealth(),
                    m == null ? -1 : m.shotsSeen);
        });
    }

    /** Неподвижная цель неактивна: огня нет, пушка в ожидании. */
    @GameTest(template = ARENA, batch = "stationary", timeoutTicks = 200)
    public static void stationaryTargetIgnored(GameTestHelper h) {
        floor(h);
        bigCannon(h, new BlockPos(10, 1, 32), Direction.EAST, 2, 4, true);
        FireControlBlockEntity be = fcs(h, new BlockPos(6, 1, 28));
        Mob husk = movingTarget(h, EntityType.HUSK, new Vec3(40.5, 1, 32.5), 0, 0);
        float max = husk.getMaxHealth();
        logEvery(h, be, "stationary", 40);
        h.runAtTickTime(160, () -> {
            if (husk.getHealth() < max) h.fail(("stationary target was shot"));
            if (!be.sensor().active().isEmpty()) h.fail(("stationary target is active"));
            for (MountState m : be.mountStates()) {
                if (m.status == Status.FIRE) h.fail(("mount is firing at a stationary target"));
                if (m.status == Status.NOT_ASSEMBLED) h.fail(("mount was not assembled"));
            }
            h.succeed();
        });
    }

    /**
     * Зона наземного транспондера бережёт своё на базе (мирная корова, взятая в цели белым списком, - своя, огня по ней нет),
     * но не прикрывает врага: враждебный моб в той же зоне - цель, по нему огонь.
     */
    @GameTest(template = ARENA, batch = "iff", timeoutTicks = 500)
    public static void transponderProtectsTarget(GameTestHelper h) {
        floor(h);
        autocannon(h, new BlockPos(10, 1, 32), Direction.EAST, 4, 64);
        FireControlBlockEntity be = fcs(h, new BlockPos(6, 1, 28));
        SettingsGameTests.configure(be, s -> s.whitelist.add("minecraft:cow"));
        h.setBlock(new BlockPos(48, 1, 30), FcsRegistry.IFF_TRANSPONDER.get().defaultBlockState());
        Mob cow = SettingsGameTests.tough(movingTarget(h, EntityType.COW, new Vec3(46.5, 1, 22.5), 0.1, 6));
        Mob husk = SettingsGameTests.tough(movingTarget(h, EntityType.HUSK, new Vec3(46.5, 1, 34.5), 0.1, 4));
        AtomicInteger cowHits = countHits(h, cow), huskHits = countHits(h, husk);
        logEvery(h, be, "iff", 40);
        h.onEachTick(() -> {
            if (cowHits.get() > 0) throw new GameTestAssertException("protected cow was shot");
            for (MountState m : be.mountStates()) {
                if (cow.getUUID().toString().equals(m.targetId)) throw new GameTestAssertException("protected cow was picked as a target");
            }
        });
        h.succeedWhen(() -> {
            var t = be.sensor().get(cow.getUUID().toString());
            if (t == null || !t.friendly) throw new GameTestAssertException("cow in the zone is not friendly");
            if (huskHits.get() == 0) throw new GameTestAssertException("hostile mob in the zone not hit yet");
            FcsMod.LOG.info("[radar_fcs test] iff: hostile in the transponder zone hit {} times, protected cow untouched", huskHits.get());
        });
    }

    /** Автопушка (пулемётные патроны) по движущейся цели: огонь очередями и попадание. */
    @GameTest(template = ARENA, batch = "auto", timeoutTicks = 600)
    public static void autocannonHitsMovingTarget(GameTestHelper h) {
        floor(h);
        autocannon(h, new BlockPos(10, 1, 32), Direction.EAST, 4, 64);
        FireControlBlockEntity be = fcs(h, new BlockPos(6, 1, 28));
        Mob husk = movingTarget(h, EntityType.HUSK, new Vec3(40.5, 1, 24.5), 0.15, 16);
        float max = husk.getMaxHealth();
        logEvery(h, be, "auto", 20);
        h.succeedWhen(() -> {
            if (husk.isAlive() && husk.getHealth() >= max) throw new GameTestAssertException("target not hit yet");
            FcsMod.LOG.info("[radar_fcs test] auto: HIT, health {} -> {}", max, husk.getHealth());
        });
    }

    /**
     * Ракеты CBC-AT летят с тягой: до первого пуска модель их не знает. Вычислитель следит за ракетами,
     * учит поправки полёта и после этого попадает.
     */
    @GameTest(template = ARENA, batch = "rocket", timeoutTicks = 900)
    public static void rocketPodLearnsAndHits(GameTestHelper h) {
        floor(h);
        // исправление CBC-AT (cbcatfix) ограничивает пусковую тремя блоками вместе с казёнником
        int rails = ModList.get().isLoaded("cbcatfix") ? 2 : 3;
        if (!rocketPod(h, new BlockPos(10, 1, 32), Direction.EAST, rails, "cbc_at:ap_rocket_item", 96)) {
            h.succeed();                                                       // без CBC-AT тест не применим
            return;
        }
        FireControlBlockEntity be = fcs(h, new BlockPos(6, 1, 28));
        Mob husk = movingTarget(h, EntityType.HUSK, new Vec3(46.5, 1, 24.5), 0.1, 14);
        float max = husk.getMaxHealth();
        logEvery(h, be, "rocket", 20);
        if (Boolean.getBoolean("radarfcs.probe")) {
            Vec3 mc = h.absoluteVec(Vec3.atCenterOf(new BlockPos(10, 3, 32)));
            AtomicInteger t0 = new AtomicInteger(-1), tick = new AtomicInteger();
            h.onEachTick(() -> {
                int t = tick.getAndIncrement();
                for (AbstractCannonProjectile p : h.getLevel().getEntitiesOfClass(AbstractCannonProjectile.class, new AABB(mc, mc).inflate(80))) {
                    if (t0.get() < 0) t0.set(t);
                    if (t - t0.get() > 50) continue;
                    FcsMod.LOG.info("[radar_fcs probe] rocket #{} t={} pos={} vel={} |v|={}", p.getId(), t, p.position().subtract(mc),
                            p.getDeltaMovement(), String.format(java.util.Locale.ROOT, "%.3f", p.getDeltaMovement().length()));
                }
            });
        }
        h.succeedWhen(() -> {
            if (husk.isAlive() && husk.getHealth() >= max) throw new GameTestAssertException("target not hit yet");
            MountState m = be.mountStates().isEmpty() ? null : be.mountStates().get(0);
            FcsMod.LOG.info("[radar_fcs test] rocket: HIT, health {} -> {}, learned shots {}, learned ticks {}, speed x{}",
                    max, husk.getHealth(), m == null ? -1 : m.correction.shots(), m == null ? -1 : m.correction.learnedTicks(),
                    m == null ? -1 : m.correction.speedRatio());
        });
    }

    /** Летящая цель без ИИ и гравитации: туда-обратно по прямой со скоростью velPerTick, legTicks тиков в одну сторону. */
    static Mob linearTarget(GameTestHelper h, EntityType<? extends Mob> type, Vec3 startRel, Vec3 velPerTick, int legTicks) {
        Mob mob = h.spawn(type, startRel);
        mob.setNoAi(true);
        mob.setNoGravity(true);
        mob.setPersistenceRequired();
        Vec3 start = h.absoluteVec(startRel);
        AtomicInteger tick = new AtomicInteger();
        h.onEachTick(() -> {
            if (!mob.isAlive()) return;
            int t = tick.getAndIncrement();
            int ph = t % (2 * legTicks);
            double k = ph < legTicks ? ph : 2 * legTicks - ph;
            Vec3 p = start.add(velPerTick.scale(k));
            mob.setPos(p.x, p.y, p.z);
            mob.setDeltaMovement(Vec3.ZERO);
        });
        return mob;
    }

    /** Снаряд появился у пушки, если впервые замечен ближе этого к станку (дуло + один тик полёта), блоков. */
    static final double MUZZLE_ZONE = 22;

    /** Снаряды, вылетевшие из пушки (новые снаряды CBC рядом со станком). */
    static AtomicInteger countRounds(GameTestHelper h, BlockPos mountRel) {
        AtomicInteger n = new AtomicInteger();
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        Vec3 c = h.absoluteVec(Vec3.atCenterOf(mountRel));
        h.onEachTick(() -> {
            // только выстрелы: снаряд впервые замечен у пушки (осколки бронебойных снарядов появляются у цели)
            for (AbstractCannonProjectile p : h.getLevel().getEntitiesOfClass(AbstractCannonProjectile.class, new AABB(c, c).inflate(160))) {
                if (seen.add(p.getId()) && p.position().distanceTo(c) < MUZZLE_ZONE) n.incrementAndGet();
            }
        });
        return n;
    }

    /**
     * Диагностика: для каждого снаряда - расчётный промах и тик встречи на момент выстрела, фактический путь
     * против положений цели, попадание (по событию урона), ошибка расчётного пути вдоль и поперёк траектории
     * и блок, в который снаряд упёрся при промахе.
     */
    static void shotDirectionProbe(GameTestHelper h, FireControlBlockEntity be, BlockPos mountRel, Mob target) {
        java.util.ArrayDeque<double[]> hist = new java.util.ArrayDeque<>();     // {predictedMiss, predictedTick}
        java.util.Map<Integer, java.util.List<Vec3>> paths = new java.util.HashMap<>();
        java.util.Map<Integer, Integer> spawnTick = new java.util.HashMap<>();
        java.util.Map<Integer, double[]> pred = new java.util.HashMap<>();
        java.util.Map<Integer, AABB> boxes = new java.util.HashMap<>();       // габарит цели в конце тика
        java.util.Map<Integer, java.util.List<dev.radarfcs.core.Vec>> predPath = new java.util.HashMap<>();
        dev.radarfcs.core.Vec[] prevLaunch = new dev.radarfcs.core.Vec[2];
        Vec3 c = h.absoluteVec(Vec3.atCenterOf(mountRel));
        AtomicInteger tick = new AtomicInteger();
        java.util.Map<Integer, String> hitInfo = new java.util.HashMap<>();
        onProjectileHit(target, () -> {
            AbstractCannonProjectile best = nearestProjectile(h, target);
            if (best == null) return;
            java.util.List<Vec3> path = paths.get(best.getId());
            Integer s0 = spawnTick.get(best.getId());
            hitInfo.put(best.getId(), String.format(java.util.Locale.ROOT, "HIT in tick %d (seg %d, path %s) at %s v %s box %s", tick.get(),
                    s0 == null ? -1 : tick.get() - s0, path == null ? "?" : String.valueOf(path.size()), best.position(),
                    best.getDeltaMovement(), target.getBoundingBox()));
        });
        h.onEachTick(() -> {
            int t = tick.getAndIncrement();
            if (be.mountStates().isEmpty()) return;
            MountState m = be.mountStates().get(0);
            boxes.put(t, target.getBoundingBox());
            for (AbstractCannonProjectile p : h.getLevel().getEntitiesOfClass(AbstractCannonProjectile.class, new AABB(c, c).inflate(120))) {
                paths.computeIfAbsent(p.getId(), k -> {
                    spawnTick.put(k, t);
                    double[] prev = hist.peekFirst();
                    pred.put(k, prev == null ? new double[]{-1, -1} : prev.clone());
                    predPath.put(k, prevLaunch[0] == null ? null : dev.radarfcs.core.BallisticSolver.positions(prevLaunch[0], prevLaunch[1], m.flightModel(), 12));
                    return new java.util.ArrayList<>();
                }).add(p.position());
            }
            paths.entrySet().removeIf(en -> {
                if (h.getLevel().getEntity(en.getKey()) != null) return false;
                int s0 = spawnTick.get(en.getKey());
                java.util.List<Vec3> path = en.getValue();
                // сегмент k (из позиции конца тика s0+k-1 в s0+k) против цели в конце тика s0+k-1+d, d = -1..1
                StringBuilder sb = new StringBuilder();
                for (int d = -1; d <= 1; d++) {
                    double best = Double.MAX_VALUE;
                    int bestK = -1;
                    for (int k = 1; k < path.size(); k++) {
                        AABB box = boxes.get(s0 + k - 1 + d);
                        if (box == null) continue;
                        Vec3 a = path.get(k - 1), b = path.get(k);
                        for (int i = 0; i <= 20; i++) {
                            Vec3 q = a.lerp(b, i / 20.0);
                            double dx = Math.max(0, Math.max(box.minX - q.x, q.x - box.maxX));
                            double dy = Math.max(0, Math.max(box.minY - q.y, q.y - box.maxY));
                            double dz = Math.max(0, Math.max(box.minZ - q.z, q.z - box.maxZ));
                            double g = Math.sqrt(dx * dx + dy * dy + dz * dz);
                            if (g < best) { best = g; bestK = k; }
                        }
                    }
                    sb.append(String.format(java.util.Locale.ROOT, " d=%d gap %.2f (seg %d);", d, best, bestK));
                }
                double[] pr = pred.get(en.getKey());
                FcsMod.LOG.info("[radar_fcs test] probe round #{} spawned t={}: predicted miss {} at {} |{} {}", en.getKey(), s0,
                        String.format(java.util.Locale.ROOT, "%.2f", pr[0]), String.format(java.util.Locale.ROOT, "%.2f", pr[1]), sb,
                        hitInfo.getOrDefault(en.getKey(), "miss"));
                java.util.List<dev.radarfcs.core.Vec> pp = predPath.get(en.getKey());
                if (pp != null) {
                    // ошибка расчётного положения снаряда: вдоль траектории и поперёк, по тикам (k=0 - точка появления)
                    StringBuilder eb = new StringBuilder();
                    for (int k = 0; k < Math.min(path.size(), pp.size()); k++) {
                        Vec3 a = path.get(k);
                        dev.radarfcs.core.Vec q = pp.get(k);
                        Vec3 dq = new Vec3(a.x - q.x(), a.y - q.y(), a.z - q.z());
                        Vec3 dirk = k + 1 < pp.size() ? new Vec3(pp.get(k + 1).x() - q.x(), pp.get(k + 1).y() - q.y(), pp.get(k + 1).z() - q.z()).normalize()
                                : new Vec3(q.x() - pp.get(k - 1).x(), q.y() - pp.get(k - 1).y(), q.z() - pp.get(k - 1).z()).normalize();
                        double along = dq.dot(dirk), across = dq.subtract(dirk.scale(along)).length();
                        eb.append(String.format(java.util.Locale.ROOT, " %d:%+.2f/%.2f", k, along, across));
                    }
                    FcsMod.LOG.info("[radar_fcs test] probe   path error along/across:{}", eb);
                    if (!hitInfo.containsKey(en.getKey()) && path.size() >= 2) {
                        Vec3 a = path.get(path.size() - 2), b = path.get(path.size() - 1);
                        Vec3 far = a.add(b.subtract(a).normalize().scale(12));
                        var bh = h.getLevel().clip(new net.minecraft.world.level.ClipContext(a, far,
                                net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE,
                                net.minecraft.world.phys.shapes.CollisionContext.empty()));
                        FcsMod.LOG.info("[radar_fcs test] probe   miss end {} -> block {} at {} ({}), target box {}", b, bh.getType(),
                                bh.getLocation(), h.getLevel().getBlockState(bh.getBlockPos()), target.getBoundingBox());
                    }
                }
                return true;
            });
            hist.addFirst(new double[]{m.predictedMiss, m.predictedMissTick});
            prevLaunch[0] = m.predictedLaunch;
            prevLaunch[1] = m.predictedDir;
            while (hist.size() > 4) hist.removeLast();
        });
    }

    /** Попадания по цели: события урона от снарядов CBC. */
    static AtomicInteger countHits(GameTestHelper h, Mob target) {
        AtomicInteger hits = new AtomicInteger();
        HITS.put(target, src -> hits.incrementAndGet());
        return hits;
    }

    /** Слушатели урона по тестовым целям. */
    static final java.util.Map<net.minecraft.world.entity.Entity, java.util.function.Consumer<net.minecraft.world.entity.Entity>> HITS =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /** Обработчики попадания снаряда CBC по цели (вызываются во время тика снаряда - он ещё рядом с целью). */
    static final java.util.Map<net.minecraft.world.entity.Entity, java.util.List<Runnable>> PROBE_HITS =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    static void onProjectileHit(net.minecraft.world.entity.Entity target, Runnable r) {
        PROBE_HITS.computeIfAbsent(target, k -> new java.util.ArrayList<>()).add(r);
    }

    /** Снаряд CBC, ближайший к цели (в момент урона это тот, что попал). */
    @org.jetbrains.annotations.Nullable
    static AbstractCannonProjectile nearestProjectile(GameTestHelper h, net.minecraft.world.entity.Entity target) {
        AbstractCannonProjectile best = null;
        for (AbstractCannonProjectile p : h.getLevel().getEntitiesOfClass(AbstractCannonProjectile.class, target.getBoundingBox().inflate(12))) {
            if (best == null || p.distanceToSqr(target) < best.distanceToSqr(target)) best = p;
        }
        return best;
    }

    /** Урон снарядов CBC и аддонов (Neo Warfare, CBC-AT); удушье в блоках и пр. не считаем. */
    static boolean isCannonDamage(String msgId) {
        return msgId.startsWith("createbigcannons") || msgId.startsWith("cbcmodernwarfare") || msgId.startsWith("cbc_at")
                || msgId.startsWith("cbcatfix");
    }

    static void onDamage(net.neoforged.neoforge.event.entity.living.LivingDamageEvent.Post e) {
        if (isCannonDamage(e.getSource().getMsgId())) {
            var rs = PROBE_HITS.get(e.getEntity());
            if (rs != null) for (Runnable r : java.util.List.copyOf(rs)) r.run();
        }
        var l = HITS.get(e.getEntity());
        if (l == null) return;
        // урон снарядов CBC приходит без прямой сущности; удушье в блоках и пр. не считаем
        if (isCannonDamage(e.getSource().getMsgId())) l.accept(null);
    }

    /**
     * Автопушка по живучей цели (здоровье 2000), летящей по прямой туда-обратно: 300 тиков огня,
     * в лог - доля попаданий. Тест проходит, если попала хотя бы четверть снарядов (и не меньше 3).
     */
    /**
     * Убрать барьеры в зоне полёта (координаты площадки): игровые тесты огораживают свои площадки барьерами,
     * и старые площадки остаются в мире. Быстрая цель-манекен летит далеко за край площадки сквозь эти стены,
     * а снаряды в них упираются (в игре цель сквозь блоки не летает).
     */
    static void removeBarriers(GameTestHelper h, BlockPos fromRel, BlockPos toRel) {
        BlockPos a = h.absolutePos(fromRel), b = h.absolutePos(toRel);
        ServerLevel l = h.getLevel();
        int x0 = Math.min(a.getX(), b.getX()), x1 = Math.max(a.getX(), b.getX());
        int y0 = Math.min(a.getY(), b.getY()), y1 = Math.max(a.getY(), b.getY());
        int z0 = Math.min(a.getZ(), b.getZ()), z1 = Math.max(a.getZ(), b.getZ());
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int cx = x0 >> 4; cx <= x1 >> 4; cx++) for (int cz = z0 >> 4; cz <= z1 >> 4; cz++) {
            var chunk = l.getChunk(cx, cz);
            for (int sy = l.getSectionIndex(y0); sy <= l.getSectionIndex(y1); sy++) {
                if (sy < 0 || sy >= chunk.getSections().length) continue;
                var section = chunk.getSections()[sy];
                if (section.hasOnlyAir() || !section.maybeHas(st -> st.is(Blocks.BARRIER))) continue;
                int by = l.getSectionYFromSectionIndex(sy) << 4;
                for (int x = Math.max(x0, cx << 4); x <= Math.min(x1, (cx << 4) + 15); x++)
                    for (int z = Math.max(z0, cz << 4); z <= Math.min(z1, (cz << 4) + 15); z++)
                        for (int y = Math.max(y0, by); y <= Math.min(y1, by + 15); y++) {
                            p.set(x, y, z);
                            if (l.getBlockState(p).is(Blocks.BARRIER)) l.setBlock(p, Blocks.AIR.defaultBlockState(), 2);
                        }
            }
        }
    }

    private static void fastAutocannon(GameTestHelper h, String name, Vec3 startRel, Vec3 vel, int leg) {
        fastTarget(h, name, startRel, vel, leg, () -> {
            autocannon(h, new BlockPos(10, 1, 32), Direction.EAST, 4, 512);
            return true;
        });
    }

    /**
     * Пушка на станке (10,1,32) и вычислитель против цели-манекена, летящей туда-обратно: 300 тиков огня,
     * в лог - доля попаданий. Тест проходит, если попала хотя бы четверть снарядов (и не меньше 3).
     * build ставит пушку и возвращает false, если нужного мода нет (тест тогда не применим).
     */
    private static void fastTarget(GameTestHelper h, String name, Vec3 startRel, Vec3 vel, int leg,
                                   java.util.function.BooleanSupplier build) {
        floor(h);
        if (!build.getAsBoolean()) {
            FcsMod.LOG.info("[radar_fcs test] {}: cannon mod not installed, test skipped", name);
            h.succeed();
            return;
        }
        FireControlBlockEntity be = fcs(h, new BlockPos(6, 1, 28));
        Vec3 endRel = startRel.add(vel.scale(leg));
        removeBarriers(h, BlockPos.containing(Math.min(0, Math.min(startRel.x, endRel.x)) - 4, 1, Math.min(startRel.z, endRel.z) - 8),
                BlockPos.containing(Math.max(startRel.x, endRel.x) + 8, Math.max(startRel.y, endRel.y) + 6, Math.max(startRel.z, endRel.z) + 8));
        forceArea(h, BlockPos.containing(Math.min(startRel.x, endRel.x) - 16, 0, Math.min(startRel.z, endRel.z) - 16),
                BlockPos.containing(Math.max(startRel.x, endRel.x) + 16, 0, Math.max(startRel.z, endRel.z) + 16));
        Mob target = linearTarget(h, EntityType.HUSK, startRel, vel, leg);
        var hp = target.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH);
        if (hp != null) hp.setBaseValue(1024);
        target.setHealth(target.getMaxHealth());
        h.onEachTick(() -> { if (target.isAlive()) target.setHealth(target.getMaxHealth()); });   // снаряды больших пушек не убивают манекен
        logEvery(h, be, name, 10);
        AtomicInteger hits = countHits(h, target), rounds = countRounds(h, new BlockPos(10, 1, 32));
        // подробный разбор каждого снаряда (расчёт против факта): ./gradlew runGameTestServer -PfcsProbe
        if (Boolean.getBoolean("radarfcs.probe")) shotDirectionProbe(h, be, new BlockPos(10, 1, 32), target);
        // Цель-манекен разворачивается мгновенно (настоящая так не умеет): снаряды, которые в этот момент уже
        // летят, промахиваются при любом расчёте. Отдельно считаем снаряды, выпущенные не в такой момент.
        java.util.Map<Integer, Integer> spawned = new java.util.HashMap<>();
        java.util.Set<Integer> hitIds = new java.util.HashSet<>(), others = new java.util.HashSet<>();
        AtomicInteger tick = new AtomicInteger();
        Vec3 mc = h.absoluteVec(Vec3.atCenterOf(new BlockPos(10, 1, 32)));
        h.onEachTick(() -> {
            int t = tick.getAndIncrement();
            for (AbstractCannonProjectile p : h.getLevel().getEntitiesOfClass(AbstractCannonProjectile.class, new AABB(mc, mc).inflate(160)))
                if (!spawned.containsKey(p.getId()) && !others.contains(p.getId())) {
                    if (p.position().distanceTo(mc) < MUZZLE_ZONE) spawned.put(p.getId(), t);
                    else others.add(p.getId());
                }
        });
        onProjectileHit(target, () -> {
            AbstractCannonProjectile p = nearestProjectile(h, target);
            if (p != null) hitIds.add(p.getId());
        });
        h.runAtTickTime(300, () -> {
            // попадания - по выпущенным снарядам (урон осколков и повторный урон одного снаряда не в счёт)
            int roundHits = 0;
            for (Integer id : spawned.keySet()) if (hitIds.contains(id)) roundHits++;
            hits.set(roundHits);
            rounds.set(spawned.size());
            double rate = rounds.get() == 0 ? 0 : 100.0 * hits.get() / rounds.get();
            int clear = 0, clearHits = 0;
            for (var en : spawned.entrySet()) {
                int s0 = en.getValue();
                boolean reversal = false;
                for (int r = leg; r <= 300 + leg; r += leg) if (r >= s0 - 1 && r <= s0 + 12) reversal = true;
                if (reversal) continue;
                clear++;
                if (hitIds.contains(en.getKey())) clearHits++;
            }
            FcsMod.LOG.info("[radar_fcs test] {}: {} hits of {} rounds ({}%) on a {} m/s target; fired clear of target reversals: {} of {} ({}%)",
                    name, hits.get(), rounds.get(), String.format(java.util.Locale.ROOT, "%.0f", rate), Math.round(vel.length() * 20),
                    clearHits, clear, String.format(java.util.Locale.ROOT, "%.0f", clear == 0 ? 0 : 100.0 * clearHits / clear));
            if (hits.get() < minHits(name) || rate < 25) h.fail(name + ": " + hits.get() + " hits of " + rounds.get() + " rounds");
            h.succeed();
        });
    }

    /** Автопушка по летящей цели со скоростью 3.5 бл/тик (70 м/с), курс поперёк линии огня. */
    @GameTest(template = ARENA, batch = "fastauto", timeoutTicks = 400)
    public static void autocannonHitsFastTarget(GameTestHelper h) {
        fastAutocannon(h, "fastauto", new Vec3(46.5, 9, -37.5), new Vec3(0, 0, 3.5), 40);
    }

    /** То же на 5 бл/тик (100 м/с). */
    @GameTest(template = ARENA, batch = "fastauto100", timeoutTicks = 400)
    public static void autocannonHits100msTarget(GameTestHelper h) {
        fastAutocannon(h, "fastauto100", new Vec3(50.5, 12, -42.5), new Vec3(0, 0, 5.0), 30);
    }

    /** Пикирующая цель 70 м/с: сближается, снижается и смещается вбок одновременно. */
    @GameTest(template = ARENA, batch = "fastdive", timeoutTicks = 400)
    public static void autocannonHitsDivingTarget(GameTestHelper h) {
        fastAutocannon(h, "fastdive", new Vec3(110.5, 40, -20.5), new Vec3(-2.0, -1.0, 2.7), 30);
    }

    /**
     * Три большие пушки по одному выстрелу (8 бл/тик) по кораблю Sable 3x3x3, летящему со скоростью 3.5 бл/тик (70 м/с).
     * У CBC есть собственный случайный разброс снарядов, поэтому требуется хотя бы одно попадание из трёх.
     */
    /** Меньше выстрелов у пушек с перезарядкой: им хватает двух попаданий. */
    private static int minHits(String name) {
        return name.startsWith("user_medium") || name.startsWith("user_qf") ? 2 : 3;
    }

    // Пушки из сборки пользователя против цели 70 м/с поперёк линии огня (как fastauto)
    private static final Vec3 USER_START = new Vec3(46.5, 9, -37.5), USER_VEL = new Vec3(0, 0, 3.5);

    @BeforeBatch(batch = "user_auto") public static void bu1(ServerLevel l) { direct(l, 160); }
    @AfterBatch(batch = "user_auto") public static void au1(ServerLevel l) { cleanup(l); }
    @BeforeBatch(batch = "user_rotary") public static void bu2(ServerLevel l) { direct(l, 160); }
    @AfterBatch(batch = "user_rotary") public static void au2(ServerLevel l) { cleanup(l); }
    @BeforeBatch(batch = "user_medium") public static void bu3(ServerLevel l) { direct(l, 160); }
    @AfterBatch(batch = "user_medium") public static void au3(ServerLevel l) { cleanup(l); }
    @BeforeBatch(batch = "user_qf") public static void bu4(ServerLevel l) { direct(l, 160); }
    @AfterBatch(batch = "user_qf") public static void au4(ServerLevel l) { cleanup(l); }

    /** Стальная автопушка, 3 ствола, бронебойные патроны. */
    @GameTest(template = ARENA, batch = "user_auto", timeoutTicks = 400)
    public static void steelAutocannon3(GameTestHelper h) {
        fastTarget(h, "user_steel_autocannon", USER_START, USER_VEL, 40, () -> {
            autocannon(h, new BlockPos(10, 1, 32), Direction.EAST, "steel", 3, 512, "createbigcannons:ap_autocannon_round");
            return true;
        });
    }

    /** Бронзовая роторная пушка CBC Neo Warfare, 3 ствола. */
    @GameTest(template = ARENA, batch = "user_rotary", timeoutTicks = 400)
    public static void bronzeRotary3(GameTestHelper h) {
        fastTarget(h, "user_bronze_rotary", USER_START, USER_VEL, 40,
                () -> rotaryCannon(h, new BlockPos(10, 1, 32), Direction.EAST, "bronze", 3, 1024));
    }

    /** Незерстальная средняя пушка CBC Neo Warfare, 3 ствола; автомат заряжания - 5 тиков. */
    @GameTest(template = ARENA, batch = "user_medium", timeoutTicks = 400)
    public static void nethersteelMedium3(GameTestHelper h) {
        fastTarget(h, "user_medium_nethersteel", USER_START, USER_VEL, 40, () -> {
            if (!mediumCannon(h, new BlockPos(10, 1, 32), Direction.EAST, "nethersteel", 3)) return false;
            mediumAutoloader(h, new BlockPos(10, 1, 32), 5);
            return true;
        });
    }

    /** Стальная большая пушка: скорострельный затвор, ствол с картриджем 4/4, 4 нарезных ствола CBC-AT. */
    @GameTest(template = ARENA, batch = "user_qf", timeoutTicks = 400)
    public static void steelQfRifled(GameTestHelper h) {
        fastTarget(h, "user_qf_steel_rifled", USER_START, USER_VEL, 40, () -> {
            if (!qfRifledCannon(h, new BlockPos(10, 1, 32), Direction.EAST, "steel", 4)) return false;
            qfAutoloader(h, new BlockPos(10, 1, 32), Direction.EAST, 10);
            return true;
        });
    }

    @GameTest(template = ARENA, batch = "fastship", timeoutTicks = 400)
    public static void bigCannonHitsFastShip(GameTestHelper h) {
        if (!ModList.get().isLoaded("sable")) {
            h.succeed();
            return;
        }
        floor(h);
        removeBarriers(h, new BlockPos(0, 1, -80), new BlockPos(70, 24, 130));
        for (int z : new int[]{20, 32, 44}) bigCannon(h, new BlockPos(10, 1, z), Direction.EAST, 4, 4, true);
        FireControlBlockEntity be = fcs(h, new BlockPos(6, 1, 30));
        forceArea(h, new BlockPos(30, 0, -80), new BlockPos(60, 0, 130));
        Object sub = SableTestSupport.ship(h, new BlockPos(45, 9, -60), 3, new Vec3(0, 0, 3.5), 175, false);
        logEvery(h, be, "fastship", 5);
        AtomicInteger hits = SableTestSupport.watchShipHits(h, sub, "fastship");
        if (Boolean.getBoolean("radarfcs.probe")) SableTestSupport.probeShip(h, sub, be);
        AtomicInteger tick = new AtomicInteger();
        h.onEachTick(tick::incrementAndGet);
        // ждём, пока выстрелят все три пушки и долетят снаряды (полёт - до 10 тиков), затем считаем попадания
        AtomicInteger allFiredAt = new AtomicInteger(-1);
        h.succeedWhen(() -> {
            int fired = 0;
            for (MountState m : be.mountStates()) if (m.shotsSeen > 0) fired++;
            if (fired >= 3 && allFiredAt.get() < 0) allFiredAt.set(tick.get());
            boolean settled = allFiredAt.get() >= 0 && tick.get() - allFiredAt.get() >= 20;
            if (!settled && tick.get() < 300) throw new GameTestAssertException("fast ship: waiting for shots, fired " + fired);
            if (hits.get() == 0) throw new GameTestAssertException("fast ship: hits 0 of " + fired);
            FcsMod.LOG.info("[radar_fcs test] fastship: {} hits of {} shots on a 70 m/s ship", hits.get(), fired);
        });
    }

    /** Контроллеры наводки без вращения: наведения нет (статус «нет вращения»); моторы на их валы - попадание. */
    @GameTest(template = ARENA, batch = "nopower", timeoutTicks = 600)
    public static void noRotationNoAiming(GameTestHelper h) {
        floor(h);
        BlockPos mount = new BlockPos(10, 1, 32);
        bigCannon(h, mount, Direction.EAST, 2, 4, true, false);
        FireControlBlockEntity be = fcs(h, new BlockPos(6, 1, 28));
        Mob husk = movingTarget(h, EntityType.HUSK, new Vec3(48.5, 1, 22.5), 0.12, 18);
        float max = husk.getMaxHealth();
        logEvery(h, be, "nopower", 20);
        AtomicReference<Boolean> sawNoPower = new AtomicReference<>(false);
        h.runAtTickTime(80, () -> {
            for (MountState m : be.mountStates()) if (m.status == Status.NO_POWER) sawNoPower.set(true);
            if (husk.getHealth() < max) h.fail("fired without rotation");
            motors(h, mount, Direction.EAST, 256);
        });
        h.succeedWhen(() -> {
            if (!sawNoPower.get()) throw new GameTestAssertException("NO_POWER status not observed without rotation");
            if (husk.isAlive() && husk.getHealth() >= max) throw new GameTestAssertException("target not hit yet");
        });
    }

    /** Цели берутся с плоского радара Create Radars на линке: без него - статус «нет радара», с ним - попадание. */
    @GameTest(template = ARENA, batch = "radar", timeoutTicks = 700)
    public static void radarDesignatedTarget(GameTestHelper h) {
        if (!ModList.get().isLoaded("create_radar")) {
            h.succeed();                                                       // Create Radars не установлен
            return;
        }
        floor(h);
        bigCannon(h, new BlockPos(10, 1, 32), Direction.EAST, 2, 4, true);
        FireControlBlockEntity be = fcs(h, new BlockPos(6, 1, 28));
        Mob husk = movingTarget(h, EntityType.HUSK, new Vec3(48.5, 1, 22.5), 0.12, 18);
        float max = husk.getMaxHealth();
        logEvery(h, be, "radar", 20);
        AtomicReference<Boolean> sawNoRadar = new AtomicReference<>(false);
        h.runAtTickTime(60, () -> {
            for (MountState m : be.mountStates()) if (m.status == Status.NO_RADAR) sawNoRadar.set(true);
            if (husk.getHealth() < max) h.fail(("fired without radar"));
            // плоский радар сканирует в сторону, противоположную FACING: FACING=запад -> луч на восток, к цели
            h.setBlock(new BlockPos(8, 1, 26), block("create_radar:plane_radar").defaultBlockState()
                    .trySetValue(BlockStateProperties.HORIZONTAL_FACING, Direction.WEST));
            CREATED.add(h.absolutePos(new BlockPos(8, 1, 26)));
            link(h, new BlockPos(8, 1, 26), Direction.UP, new BlockPos(6, 1, 28));
        });
        h.succeedWhen(() -> {
            if (!sawNoRadar.get()) throw new GameTestAssertException("NO_RADAR status not observed before radar placed");
            if (husk.isAlive() && husk.getHealth() >= max) throw new GameTestAssertException("target not hit yet");
            FcsMod.LOG.info("[radar_fcs test] radar: HIT via Create Radars tracks, radar used={}", be.sensor().usedRadar());
            if (!be.sensor().usedRadar()) throw new GameTestAssertException("radar tracks were not used");
            if (be.linkedRadars().size() != 1) throw new GameTestAssertException("radars on links: " + be.linkedRadars().size());
        });
    }
    /** Корабль Create Aeronautics / Sable - цель: вычислитель стреляет и попадает по движущемуся кораблю. */
    @GameTest(template = ARENA, batch = "ship", timeoutTicks = 600)
    public static void shipIsEngaged(GameTestHelper h) {
        if (!ModList.get().isLoaded("sable")) {
            h.succeed();                                                       // без Sable тест не применим
            return;
        }
        floor(h);
        bigCannon(h, new BlockPos(10, 1, 32), Direction.EAST, 2, 4, true);
        FireControlBlockEntity be = fcs(h, new BlockPos(6, 1, 28));
        Object sub = SableTestSupport.ship(h, new BlockPos(46, 9, 22), 3, new Vec3(0, 0, 0.12), 14, false);
        logEvery(h, be, "ship", 20);
        AtomicInteger hits = SableTestSupport.watchShipHits(h, sub, "ship");
        h.succeedWhen(() -> {
            if (hits.get() == 0) throw new GameTestAssertException("ship not hit yet");
            FcsMod.LOG.info("[radar_fcs test] ship: HIT, tracks={}", be.sensor().all().size());
        });
    }

    /** Транспондер на корабле: весь корабль свой, огня нет. */
    @GameTest(template = ARENA, batch = "shipiff", timeoutTicks = 260)
    public static void shipWithTransponderIsFriendly(GameTestHelper h) {
        if (!ModList.get().isLoaded("sable")) {
            h.succeed();
            return;
        }
        floor(h);
        bigCannon(h, new BlockPos(10, 1, 32), Direction.EAST, 2, 4, true);
        FireControlBlockEntity be = fcs(h, new BlockPos(6, 1, 28));
        Object sub = SableTestSupport.ship(h, new BlockPos(46, 9, 22), 3, new Vec3(0, 0, 0.12), 14, true);
        logEvery(h, be, "shipiff", 40);
        AtomicInteger hits = SableTestSupport.watchShipHits(h, sub, "shipiff");
        h.runAtTickTime(220, () -> {
            if (hits.get() > 0) h.fail("friendly ship was shot");
            boolean seen = false;
            for (var t : be.sensor().all()) if (t.kind == dev.radarfcs.core.Track.Kind.SHIP) seen = true;
            if (!seen) h.fail("ship was not detected at all");
            if (!be.sensor().active().isEmpty()) h.fail("friendly ship is active");
            h.succeed();
        });
    }
}
