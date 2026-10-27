package dev.radarfcs.registry;

import com.simibubi.create.api.behaviour.display.DisplaySource;
import com.simibubi.create.api.registry.CreateRegistries;
import com.simibubi.create.api.stress.BlockStressValues;
import dev.radarfcs.FcsConfig;
import dev.radarfcs.FcsMod;
import dev.radarfcs.block.AntennaBlock;
import dev.radarfcs.block.FireControlBlock;
import dev.radarfcs.block.FireControlBlockEntity;
import dev.radarfcs.block.IffTransponderBlock;
import dev.radarfcs.block.IffTransponderBlockEntity;
import dev.radarfcs.block.PhasedArrayBlockEntity;
import dev.radarfcs.block.PhasedArrayControllerBlock;
import dev.radarfcs.block.PhasedArrayPanelBlock;
import dev.radarfcs.block.RadarBearingBlock;
import dev.radarfcs.block.RadarBearingBlockEntity;
import dev.radarfcs.block.RadarDomeBlock;
import dev.radarfcs.block.RadarDomeBlockEntity;
import dev.radarfcs.block.RadarMonitorBlock;
import dev.radarfcs.block.RadarMonitorBlockEntity;
import dev.radarfcs.block.RadarLinkBlock;
import dev.radarfcs.block.RadarLinkBlockEntity;
import dev.radarfcs.block.RadarReceiverBlock;
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

    // радары
    public static final DeferredBlock<RadarBearingBlock> RADAR_BEARING = BLOCKS.register("radar_bearing",
            () -> new RadarBearingBlock(metal(MapColor.COLOR_GRAY, 3.0f).noOcclusion()));
    public static final DeferredBlock<RadarReceiverBlock> RADAR_RECEIVER = BLOCKS.register("radar_receiver",
            () -> new RadarReceiverBlock(metal(MapColor.METAL, 2.0f).lightLevel(s -> 4)));
    public static final DeferredBlock<AntennaBlock> RADAR_DISH = BLOCKS.register("radar_dish",
            () -> new AntennaBlock(metal(MapColor.METAL, 2.0f)));
    public static final DeferredBlock<PhasedArrayControllerBlock> PHASED_ARRAY_CONTROLLER = BLOCKS.register("phased_array_controller",
            () -> new PhasedArrayControllerBlock(metal(MapColor.TERRACOTTA_BROWN, 3.0f).lightLevel(s -> 5)));
    public static final DeferredBlock<PhasedArrayPanelBlock> PHASED_ARRAY_PANEL = BLOCKS.register("phased_array_panel",
            () -> new PhasedArrayPanelBlock(metal(MapColor.COLOR_GRAY, 2.0f)));
    public static final DeferredBlock<RadarDomeBlock> RADAR_DOME = BLOCKS.register("radar_dome",
            () -> new RadarDomeBlock(metal(MapColor.SNOW, 2.0f).noOcclusion().lightLevel(s -> 3)));
    public static final DeferredBlock<RadarMonitorBlock> RADAR_MONITOR = BLOCKS.register("radar_monitor",
            () -> new RadarMonitorBlock(metal(MapColor.COLOR_BLACK, 2.0f).lightLevel(s -> 6)));

    public static final DeferredBlock<RadarLinkBlock> RADAR_LINK = BLOCKS.register("radar_link",
            () -> new RadarLinkBlock(metal(MapColor.COLOR_LIGHT_BLUE, 1.5f).noOcclusion().lightLevel(s -> 4)));

    public static final DeferredItem<BlockItem> FIRE_CONTROL_ITEM = ITEMS.registerSimpleBlockItem(FIRE_CONTROL);
    public static final DeferredItem<RadarLinkItem> RADAR_LINK_ITEM = ITEMS.register("radar_link",
            () -> new RadarLinkItem(RADAR_LINK.get(), new net.minecraft.world.item.Item.Properties()));
    public static final DeferredItem<BlockItem> IFF_TRANSPONDER_ITEM = ITEMS.registerSimpleBlockItem(IFF_TRANSPONDER);
    public static final DeferredItem<BlockItem> RADAR_BEARING_ITEM = ITEMS.registerSimpleBlockItem(RADAR_BEARING);
    public static final DeferredItem<BlockItem> RADAR_RECEIVER_ITEM = ITEMS.registerSimpleBlockItem(RADAR_RECEIVER);
    public static final DeferredItem<BlockItem> RADAR_DISH_ITEM = ITEMS.registerSimpleBlockItem(RADAR_DISH);
    public static final DeferredItem<BlockItem> PHASED_ARRAY_CONTROLLER_ITEM = ITEMS.registerSimpleBlockItem(PHASED_ARRAY_CONTROLLER);
    public static final DeferredItem<BlockItem> PHASED_ARRAY_PANEL_ITEM = ITEMS.registerSimpleBlockItem(PHASED_ARRAY_PANEL);
    public static final DeferredItem<BlockItem> RADAR_DOME_ITEM = ITEMS.registerSimpleBlockItem(RADAR_DOME);
    public static final DeferredItem<BlockItem> RADAR_MONITOR_ITEM = ITEMS.registerSimpleBlockItem(RADAR_MONITOR);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<FireControlBlockEntity>> FIRE_CONTROL_BE =
            BLOCK_ENTITIES.register("fire_control_computer",
                    () -> BlockEntityType.Builder.of(FireControlBlockEntity::new, FIRE_CONTROL.get()).build(null));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<IffTransponderBlockEntity>> IFF_BE =
            BLOCK_ENTITIES.register("iff_transponder",
                    () -> BlockEntityType.Builder.of(IffTransponderBlockEntity::new, IFF_TRANSPONDER.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<RadarBearingBlockEntity>> RADAR_BEARING_BE =
            BLOCK_ENTITIES.register("radar_bearing", () -> BlockEntityType.Builder.of(
                    (pos, state) -> new RadarBearingBlockEntity(FcsRegistry.RADAR_BEARING_BE.get(), pos, state),
                    RADAR_BEARING.get()).build(null));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<PhasedArrayBlockEntity>> PHASED_ARRAY_BE =
            BLOCK_ENTITIES.register("phased_array_controller", () -> BlockEntityType.Builder.of(
                    (pos, state) -> new PhasedArrayBlockEntity(FcsRegistry.PHASED_ARRAY_BE.get(), pos, state),
                    PHASED_ARRAY_CONTROLLER.get()).build(null));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<RadarDomeBlockEntity>> RADAR_DOME_BE =
            BLOCK_ENTITIES.register("radar_dome",
                    () -> BlockEntityType.Builder.of(RadarDomeBlockEntity::new, RADAR_DOME.get()).build(null));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<RadarMonitorBlockEntity>> RADAR_MONITOR_BE =
            BLOCK_ENTITIES.register("radar_monitor",
                    () -> BlockEntityType.Builder.of(RadarMonitorBlockEntity::new, RADAR_MONITOR.get()).build(null));

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
                        out.accept(IFF_TRANSPONDER_ITEM.get());
                        out.accept(RADAR_MONITOR_ITEM.get());
                        out.accept(RADAR_DOME_ITEM.get());
                        out.accept(RADAR_BEARING_ITEM.get());
                        out.accept(RADAR_RECEIVER_ITEM.get());
                        out.accept(RADAR_DISH_ITEM.get());
                        out.accept(PHASED_ARRAY_CONTROLLER_ITEM.get());
                        out.accept(PHASED_ARRAY_PANEL_ITEM.get());
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
        BlockStressValues.IMPACTS.register(RADAR_BEARING.get(), () -> 4.0);
        // реальная нагрузка решётки зависит от числа панелей (PhasedArrayBlockEntity), здесь - для подсказок
        BlockStressValues.IMPACTS.register(PHASED_ARRAY_CONTROLLER.get(), () -> FcsConfig.safe(FcsConfig.ARRAY_STRESS));
    }

    private FcsRegistry() {}
}
