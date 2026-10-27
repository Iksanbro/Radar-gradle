package dev.radarfcs.registry;

import com.simibubi.create.api.behaviour.display.DisplaySource;
import com.simibubi.create.api.registry.CreateRegistries;
import com.simibubi.create.api.stress.BlockStressValues;
import dev.radarfcs.FcsConfig;
import dev.radarfcs.FcsMod;
import dev.radarfcs.block.AimControllerBlock;
import dev.radarfcs.block.AimControllerBlockEntity;
import dev.radarfcs.block.FireControlBlock;
import dev.radarfcs.block.FireControlBlockEntity;
import dev.radarfcs.block.IffTransponderBlock;
import dev.radarfcs.block.IffTransponderBlockEntity;
import dev.radarfcs.block.RadarMonitorBlock;
import dev.radarfcs.block.RadarMonitorBlockEntity;
import dev.radarfcs.block.RadarLinkBlock;
import dev.radarfcs.block.RadarLinkBlockEntity;
import dev.radarfcs.item.RadarLinkItem;
import dev.radarfcs.compat.FcsDisplaySource;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class FcsRegistry {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(FcsMod.MODID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(FcsMod.MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, FcsMod.MODID);
    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, FcsMod.MODID);
    public static final DeferredRegister<DisplaySource> DISPLAY_SOURCES =
            DeferredRegister.create(CreateRegistries.DISPLAY_SOURCE, FcsMod.MODID);

    public static final DeferredBlock<FireControlBlock> FIRE_CONTROL = BLOCKS.register("fire_control_computer",
            () -> new FireControlBlock(BlockBehaviour.Properties.of().mapColor(MapColor.TERRACOTTA_BROWN)
                    .strength(3.5f).requiresCorrectToolForDrops().sound(SoundType.NETHERITE_BLOCK)
                    .lightLevel(s -> s.getValue(FireControlBlock.ENGAGED) ? 7 : 3)));
    public static final DeferredBlock<IffTransponderBlock> IFF_TRANSPONDER = BLOCKS.register("iff_transponder",
            () -> new IffTransponderBlock(BlockBehaviour.Properties.of().mapColor(MapColor.METAL)
                    .strength(2.5f).requiresCorrectToolForDrops().sound(SoundType.COPPER).lightLevel(s -> 5)));

    public static final DeferredBlock<RadarMonitorBlock> RADAR_MONITOR = BLOCKS.register("radar_monitor",
            () -> new RadarMonitorBlock(metal(MapColor.COLOR_BLACK, 2.0f).lightLevel(s -> 6)));

    // контроллеры наводки: горизонтальной (поворот станка) и вертикальной (наклон ствола)
    public static final DeferredBlock<AimControllerBlock> YAW_CONTROLLER = BLOCKS.register("yaw_controller",
            () -> new AimControllerBlock(metal(MapColor.STONE, 2.5f).noOcclusion(), false));
    public static final DeferredBlock<AimControllerBlock> PITCH_CONTROLLER = BLOCKS.register("pitch_controller",
            () -> new AimControllerBlock(metal(MapColor.STONE, 2.5f).noOcclusion(), true));

    public static final DeferredBlock<RadarLinkBlock> RADAR_LINK = BLOCKS.register("radar_link",
            () -> new RadarLinkBlock(metal(MapColor.COLOR_LIGHT_BLUE, 1.5f).noOcclusion().lightLevel(s -> 4)));

    public static final DeferredItem<BlockItem> FIRE_CONTROL_ITEM = ITEMS.registerSimpleBlockItem(FIRE_CONTROL);
    public static final DeferredItem<BlockItem> YAW_CONTROLLER_ITEM = ITEMS.registerSimpleBlockItem(YAW_CONTROLLER);
    public static final DeferredItem<BlockItem> PITCH_CONTROLLER_ITEM = ITEMS.registerSimpleBlockItem(PITCH_CONTROLLER);
    public static final DeferredItem<RadarLinkItem> RADAR_LINK_ITEM = ITEMS.register("radar_link",
            () -> new RadarLinkItem(RADAR_LINK.get(), new net.minecraft.world.item.Item.Properties()));
    public static final DeferredItem<BlockItem> IFF_TRANSPONDER_ITEM = ITEMS.registerSimpleBlockItem(IFF_TRANSPONDER);
    public static final DeferredItem<BlockItem> RADAR_MONITOR_ITEM = ITEMS.registerSimpleBlockItem(RADAR_MONITOR);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<FireControlBlockEntity>> FIRE_CONTROL_BE =
            BLOCK_ENTITIES.register("fire_control_computer",
                    () -> BlockEntityType.Builder.of(FireControlBlockEntity::new, FIRE_CONTROL.get()).build(null));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<IffTransponderBlockEntity>> IFF_BE =
            BLOCK_ENTITIES.register("iff_transponder",
                    () -> BlockEntityType.Builder.of(IffTransponderBlockEntity::new, IFF_TRANSPONDER.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<RadarMonitorBlockEntity>> RADAR_MONITOR_BE =
            BLOCK_ENTITIES.register("radar_monitor",
                    () -> BlockEntityType.Builder.of(RadarMonitorBlockEntity::new, RADAR_MONITOR.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<AimControllerBlockEntity>> AIM_CONTROLLER_BE =
            BLOCK_ENTITIES.register("aim_controller", () -> BlockEntityType.Builder.of(
                    (pos, state) -> new AimControllerBlockEntity(FcsRegistry.AIM_CONTROLLER_BE.get(), pos, state),
                    YAW_CONTROLLER.get(), PITCH_CONTROLLER.get()).build(null));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<RadarLinkBlockEntity>> RADAR_LINK_BE =
            BLOCK_ENTITIES.register("radar_link",
                    () -> BlockEntityType.Builder.of(RadarLinkBlockEntity::new, RADAR_LINK.get()).build(null));

    public static final DeferredHolder<DisplaySource, FcsDisplaySource> FCS_DISPLAY_SOURCE =
            DISPLAY_SOURCES.register("fire_control", FcsDisplaySource::new);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> TAB = TABS.register("main",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.radar_fcs"))
                    .icon(() -> new ItemStack(FIRE_CONTROL_ITEM.get()))
                    .displayItems((params, out) -> {
                        out.accept(FIRE_CONTROL_ITEM.get());
                        out.accept(RADAR_LINK_ITEM.get());
                        out.accept(YAW_CONTROLLER_ITEM.get());
                        out.accept(PITCH_CONTROLLER_ITEM.get());
                        out.accept(IFF_TRANSPONDER_ITEM.get());
                        out.accept(RADAR_MONITOR_ITEM.get());
                    })
                    .build());

    public static void register(IEventBus bus) {
        BLOCKS.register(bus);
        ITEMS.register(bus);
        BLOCK_ENTITIES.register(bus);
        TABS.register(bus);
        DISPLAY_SOURCES.register(bus);
    }

    private static BlockBehaviour.Properties metal(MapColor color, float strength) {
        return BlockBehaviour.Properties.of().mapColor(color).strength(strength).requiresCorrectToolForDrops().sound(SoundType.METAL);
    }

    /** Привязки к API Create после регистрации: источник Display Link, нагрузка на кинетику. */
    public static void bindCreate() {
        DisplaySource.BY_BLOCK.add(FIRE_CONTROL.get(), FCS_DISPLAY_SOURCE.get());
        BlockStressValues.IMPACTS.register(YAW_CONTROLLER.get(), () -> FcsConfig.safe(FcsConfig.CONTROLLER_STRESS));
        BlockStressValues.IMPACTS.register(PITCH_CONTROLLER.get(), () -> FcsConfig.safe(FcsConfig.CONTROLLER_STRESS));
    }

    private FcsRegistry() {}
}
