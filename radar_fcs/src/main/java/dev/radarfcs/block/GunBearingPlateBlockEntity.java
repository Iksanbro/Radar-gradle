package dev.radarfcs.block;

import com.simibubi.create.content.kinetics.base.IRotate;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * Пластина подшипника орудия: помнит свой подшипник (его позиция в мире или в плоте другой поворотной части).
 * Вал насквозь: подшипник - пластина - постройка (связь с подшипником - своя, он не сосед).
 */
public class GunBearingPlateBlockEntity extends KineticBlockEntity {
    @Nullable private BlockPos parent;
    /** Пластину снимает сам подшипник (разборка) или её переносят - подшипник не ломать. */
    boolean moving, detaching;

    public GunBearingPlateBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    public void setParent(BlockPos pos) {
        parent = pos.immutable();
        setChanged();
        sendData();
    }

    @Nullable
    public BlockPos parent() {
        return parent;
    }

    @Nullable
    private GunBearingBlockEntity bearing() {
        return parent != null && level != null && level.isLoaded(parent)
                && level.getBlockEntity(parent) instanceof GunBearingBlockEntity b ? b : null;
    }

    /** ПКМ по пластине - подшипник разбирается. */
    void requestParentToggle() {
        GunBearingBlockEntity b = bearing();
        if (b != null) b.assembleNextTick = true;
    }

    /** Пластина переехала: подшипник узнаёт новое место. */
    void afterMove() {
        moving = false;
        GunBearingBlockEntity b = bearing();
        if (b != null) b.plateMoved(worldPosition);
    }

    /** Сломали пластину - ломается и подшипник (выпадает предметом), как у Aeronautics. */
    @Override
    public void remove() {
        if (level != null && !level.isClientSide && !moving && !detaching && parent != null && level.isLoaded(parent)
                && level.getBlockState(parent).getBlock() instanceof GunBearingBlock) {
            level.destroyBlock(parent, true);
        }
        super.remove();
    }

    @Override
    public float calculateStressApplied() {
        return 0;
    }

    // вал насквозь: связь с подшипником (он в мире или в плоте основания, не сосед в плоте пластины)
    @Override
    public List<BlockPos> addPropagationLocations(IRotate block, BlockState state, List<BlockPos> neighbours) {
        if (parent != null) neighbours.add(parent);
        return super.addPropagationLocations(block, state, neighbours);
    }

    @Override
    public float propagateRotationTo(KineticBlockEntity target, BlockState stateFrom, BlockState stateTo, BlockPos diff,
                                     boolean connectedViaAxes, boolean connectedViaCogs) {
        if (parent != null && target.getBlockPos().equals(parent)) return 1;
        return super.propagateRotationTo(target, stateFrom, stateTo, diff, connectedViaAxes, connectedViaCogs);
    }

    @Override
    public boolean isCustomConnection(KineticBlockEntity other, BlockState state, BlockState otherState) {
        return parent != null && other.getBlockPos().equals(parent) || super.isCustomConnection(other, state, otherState);
    }

    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        if (parent != null) tag.put("Parent", NbtUtils.writeBlockPos(parent));
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        parent = NbtUtils.readBlockPos(tag, "Parent").orElse(null);
    }
}
