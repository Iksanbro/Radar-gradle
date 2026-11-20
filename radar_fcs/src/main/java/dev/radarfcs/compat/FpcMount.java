package dev.radarfcs.compat;

import com.cbcfirepowercomponents.content.compact_cannon_mount.CompactAutocannonMountBlock;
import com.cbcfirepowercomponents.content.compact_cannon_mount.CompactCannonMountBlock;
import com.cbcfirepowercomponents.content.compact_cannon_mount.CompactCannonMountBlockEntity;
import com.cbcfirepowercomponents.content.compact_cannon_mount.CompactCannonMountInterfaceBlockEntity;
import com.simibubi.create.content.contraptions.AssemblyException;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import org.jetbrains.annotations.Nullable;
import rbasamoyai.createbigcannons.cannon_control.contraption.PitchOrientedContraptionEntity;

/**
 * Компактные станки CBC: Firepower Components (`cbc_firepower_components`): обычный, вертикальный и для автопушек.
 * Это своя реализация станка (не наследник станка CBC) с тем же смыслом углов и сигналов: cannonYaw - yaw Minecraft,
 * cannonPitch > 0 - вверх, `onRedstoneUpdate` с теми же параметрами. Углы ставятся через `setLimitedYaw/Pitch` -
 * их ограничивает установленный в станок ограничитель. Класс грузится только при {@link Mods#FIREPOWER}.
 */
final class FpcMount {
    private FpcMount() {}

    @Nullable
    static CbcMount.Ops ops(BlockEntity be) {
        return be instanceof CompactCannonMountBlockEntity m ? new Ops(m) : null;
    }

    private record Ops(CompactCannonMountBlockEntity be) implements CbcMount.Ops {
        public PitchOrientedContraptionEntity contraption() { return be.getContraption(); }
        public boolean running() { return be.isRunning(); }
        public double yaw() { return be.getYawOffset(0.0F); }
        public double pitch() { return be.getDisplayPitch(); }

        public boolean setAngles(float yaw, float pitch) {
            // станок сам переносит углы на контрапшен (applyRotation), но и шлёт данные клиентам - только при изменении
            if (Math.abs(be.getCannonYaw() - yaw) > 1e-4F) be.setLimitedYaw(yaw);
            if (Math.abs(be.getCannonPitch() - pitch) > 1e-4F) be.setLimitedPitch(pitch);
            return true;
        }

        public void setDriveLock(double limit) {
            for (KineticBlockEntity k : be.getAllKineticBlockEntities()) {
                if (k instanceof CompactCannonMountInterfaceBlockEntity i) i.setSequencedAngleLimit(limit);
            }
        }

        public void redstone(boolean asm, boolean prevAsm, boolean fire, boolean prevFire, int power) {
            be.onRedstoneUpdate(asm, prevAsm, fire, prevFire, power);
        }

        public void disassemble() { be.disassemble(); }
        public AssemblyException lastException() { return be.getLastAssemblyException(); }
        public BooleanProperty firePowered() { return CompactCannonMountBlock.FIRE_POWERED; }
        public BooleanProperty assemblyPowered() { return CompactCannonMountBlock.ASSEMBLY_POWERED; }

        public String prefix() {
            return be.getBlockState().getBlock() instanceof CompactAutocannonMountBlock ? "Compact AC " : "Compact ";
        }
    }
}
