package dev.radarfcs.energy;

import dev.radarfcs.FcsConfig;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.neoforge.energy.EnergyStorage;

/**
 * Буфер электричества (FE) блока мода: принимает энергию от любых модов с Forge Energy (Create Crafts & Additions -
 * генератор, аккумулятор, провода), наружу не отдаёт. Блок каждый тик забирает своё потребление; запас -
 * BUFFER_TICKS тиков потребления.
 */
public class EnergyBuffer extends EnergyStorage {
    public static final int BUFFER_TICKS = 40;
    private final Runnable onChange;

    public EnergyBuffer(int usage, Runnable onChange) {
        super(capacityFor(usage), capacityFor(usage), 0);
        this.onChange = onChange;
    }

    private static int capacityFor(int usage) {
        return Math.max(1000, usage * BUFFER_TICKS);
    }

    /** Подогнать ёмкость под потребление (конфиг сервера мог измениться). */
    public void fit(int usage) {
        int cap = capacityFor(usage);
        if (cap != capacity) {
            capacity = cap;
            maxReceive = cap;
            energy = Math.min(energy, cap);
        }
    }

    /** Забрать потребление тика; false - не хватило (тогда ничего не забирается). */
    public boolean consume(int usage) {
        if (usage <= 0) return true;
        if (energy < usage) return false;
        energy -= usage;
        onChange.run();
        return true;
    }

    /** Забрать сколько есть, но не больше amount; возвращает забранное. */
    public int drain(int amount) {
        int d = Math.min(energy, Math.max(0, amount));
        if (d > 0) {
            energy -= d;
            onChange.run();
        }
        return d;
    }

    @Override
    public int receiveEnergy(int toReceive, boolean simulate) {
        int r = super.receiveEnergy(toReceive, simulate);
        if (r > 0 && !simulate) onChange.run();
        return r;
    }

    public void setStored(int e) {
        energy = Math.max(0, Math.min(capacity, e));
    }

    public void save(CompoundTag tag) {
        tag.putInt("Energy", energy);
    }

    public void load(CompoundTag tag) {
        energy = Math.max(0, tag.getInt("Energy"));
    }

    /** Нужно ли электричество (конфиг сервера). */
    public static boolean required() {
        return FcsConfig.safe(FcsConfig.REQUIRE_POWER);
    }
}
