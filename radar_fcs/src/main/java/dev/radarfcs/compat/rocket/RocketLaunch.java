package dev.radarfcs.compat.rocket;

import com.example.cbcenhancedshells.rocket.RocketBaseBlock;
import dev.radarfcs.FcsMod;
import java.lang.reflect.Method;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * Пуск ракеты со стартовой площадки CBC Enchanced Shells по команде носа: площадка запускает стек сама (её пуск -
 * по сигналу редстоуна), здесь - тот же её пуск (метод площадки, без сигнала). Только при установленном моде.
 */
public final class RocketLaunch {
    /** Стек площадки: заряды (до 3), снаряд, нос - площадка не дальше этого. */
    private static final int MAX_STACK = 6;
    @Nullable private static Method tryLaunch;
    private static boolean broken;

    private RocketLaunch() {}

    /** Стартовая площадка этого носа (стек от площадки в сторону носа), или null - нос не на ракете. */
    @Nullable
    public static BlockPos findBase(Level level, BlockPos nose, Direction facing) {
        BlockPos c = nose.relative(facing.getOpposite());
        for (int i = 0; i < MAX_STACK; i++) {
            BlockState s = level.getBlockState(c);
            if (s.getBlock() instanceof RocketBaseBlock) {
                return s.hasProperty(DirectionalBlock.FACING) && s.getValue(DirectionalBlock.FACING) == facing ? c : null;
            }
            if (s.isAir()) return null;
            c = c.relative(facing.getOpposite());
        }
        return null;
    }

    /** Пустить ракету, на которой стоит нос; true - ракета ушла (нос снят площадкой). */
    public static boolean launch(ServerLevel level, BlockPos nose, Direction facing) {
        BlockPos base = findBase(level, nose, facing);
        if (base == null || broken) return false;
        try {
            if (tryLaunch == null) {
                tryLaunch = RocketBaseBlock.class.getDeclaredMethod("tryLaunch", ServerLevel.class, BlockPos.class, Direction.class);
                tryLaunch.setAccessible(true);
            }
            tryLaunch.invoke(level.getBlockState(base).getBlock(), level, base, facing);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            broken = true;
            FcsMod.LOG.warn("[radar_fcs] CBC Enchanced Shells launchpad not supported ({}): automatic launch disabled", e.toString());
            return false;
        }
        return level.getBlockState(nose).isAir();
    }
}
