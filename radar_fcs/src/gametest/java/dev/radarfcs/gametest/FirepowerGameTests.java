package dev.radarfcs.gametest;

import static dev.radarfcs.gametest.FcsGameTests.autocannonAmmo;
import static dev.radarfcs.gametest.FcsGameTests.block;
import static dev.radarfcs.gametest.FcsGameTests.connect;
import static dev.radarfcs.gametest.FcsGameTests.fcs;
import static dev.radarfcs.gametest.FcsGameTests.floor;
import static dev.radarfcs.gametest.FcsGameTests.logEvery;
import static dev.radarfcs.gametest.FcsGameTests.movingTarget;
import static dev.radarfcs.gametest.FcsGameTests.powerMount;

import com.cbcfirepowercomponents.content.compact_cannon_mount.CompactCannonMountBlock;
import dev.radarfcs.FcsMod;
import dev.radarfcs.block.FireControlBlockEntity;
import dev.radarfcs.compat.CbcMount;
import dev.radarfcs.compat.Mods;
import dev.radarfcs.fcs.MountState;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import rbasamoyai.createbigcannons.cannons.autocannon.breech.AbstractAutocannonBreechBlockEntity;

/**
 * Компактные станки CBC: Firepower Components: станок для автопушек и обычный компактный станок как станки CBC -
 * линк, контроллеры наводки, сборка, наведение и огонь. Без мода тесты ничего не проверяют.
 */
@GameTestHolder(FcsMod.MODID)
@PrefixGameTestTemplate(false)
public class FirepowerGameTests {
    private static final String ARENA = "arena";

    @BeforeBatch(batch = "fpc") public static void b1(ServerLevel l) { FcsGameTests.direct(l); }
    @AfterBatch(batch = "fpc") public static void a1(ServerLevel l) { FcsGameTests.cleanup(l); }

    /**
     * Чугунная автопушка на компактном станке, ствол - по HORIZONTAL_FACING. Сторона пушки - `getCannonSide`:
     * у обычного компактного станка сбоку (против часовой от HORIZONTAL_FACING), у станка для автопушек - против
     * VERTICAL_DIRECTION (ставим DOWN - пушка сверху). Контроллеры наводки - снизу и с другого бока.
     */
    static void compactAutocannon(GameTestHelper h, String mountId, BlockPos mount, Direction dir, int barrels, int rounds) {
        BlockState st = block(mountId).defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, dir)
                .setValue(CompactCannonMountBlock.VERTICAL_DIRECTION, Direction.DOWN);
        h.setBlock(mount, st);
        powerMount(h, mount, dir, 256);
        BlockPos pivot = mount.relative(((CompactCannonMountBlock) st.getBlock()).getCannonSide(st));
        String m = "cast_iron";
        h.setBlock(pivot.relative(dir, -1), block("createbigcannons:" + m + "_autocannon_breech").defaultBlockState()
                .setValue(BlockStateProperties.FACING, dir));
        h.setBlock(pivot, block("createbigcannons:" + m + "_autocannon_recoil_spring").defaultBlockState()
                .setValue(BlockStateProperties.FACING, dir));
        for (int i = 1; i <= barrels; i++) {
            h.setBlock(pivot.relative(dir, i), block("createbigcannons:" + m + "_autocannon_barrel").defaultBlockState()
                    .setValue(BlockStateProperties.FACING, dir));
        }
        connect(h, pivot.relative(dir, -1), dir, barrels + 2);
        if (h.getLevel().getBlockEntity(h.absolutePos(pivot.relative(dir, -1))) instanceof AbstractAutocannonBreechBlockEntity breech) {
            for (int i = 0; i < rounds; i++) breech.getInputBuffer().add(autocannonAmmo("createbigcannons:machine_gun_round"));
            breech.setChanged();
        } else {
            throw new GameTestAssertException("no autocannon breech at " + pivot.relative(dir, -1));
        }
    }

    @GameTest(template = ARENA, batch = "fpc", timeoutTicks = 600)
    public static void compactAutocannonMountHits(GameTestHelper h) {
        compactHits(h, "cbc_firepower_components:compact_autocannon_mount", "fpc_ac");
    }

    @GameTest(template = ARENA, batch = "fpc", timeoutTicks = 600)
    public static void compactCannonMountHits(GameTestHelper h) {
        compactHits(h, "cbc_firepower_components:compact_cannon_mount", "fpc");
    }

    /** Вычислитель собирает пушку на компактном станке, поворачивает её контроллерами и попадает в идущего хаска. */
    private static void compactHits(GameTestHelper h, String mountId, String name) {
        if (!Mods.FIREPOWER) {
            h.succeed();
            return;
        }
        floor(h);
        BlockPos mountRel = new BlockPos(10, 1, 32);
        compactAutocannon(h, mountId, mountRel, Direction.EAST, 4, 64);
        CbcMount mount = CbcMount.of(h.getLevel().getBlockEntity(h.absolutePos(mountRel)));
        if (mount == null) throw new GameTestAssertException("compact mount is not recognized as a mount");
        FireControlBlockEntity be = fcs(h, new BlockPos(6, 1, 28));
        Mob husk = movingTarget(h, EntityType.HUSK, new Vec3(40.5, 1, 24.5), 0.15, 16);
        float max = husk.getMaxHealth();
        logEvery(h, be, name, 20);
        AtomicReference<Double> yaw0 = new AtomicReference<>();
        h.onEachTick(() -> {
            if (yaw0.get() == null && mount.isAssembled()) yaw0.set(mount.yaw());
        });
        h.succeedWhen(() -> {
            if (!mount.isAssembled()) throw new GameTestAssertException("compact mount not assembled: " + mount.assemblyError());
            MountState m = be.mountStates().isEmpty() ? null : be.mountStates().get(0);
            if (m == null) throw new GameTestAssertException("no mount state");
            if (husk.isAlive() && husk.getHealth() >= max) throw new GameTestAssertException("target not hit yet");
            if (!mount.typeName().startsWith("Compact")) throw new GameTestAssertException("type name " + mount.typeName());
            FcsMod.LOG.info("[radar_fcs test] {}: HIT, health {} -> {}, type {}, yaw {} -> {}, pitch {}", name, max,
                    husk.getHealth(), mount.typeName(), yaw0.get(), mount.yaw(), mount.pitch());
        });
    }
}
