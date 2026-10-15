package com.nick.snowglobe;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import com.nick.snowglobe.net.GlobeNetwork;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

@Mod(SnowGlobeMod.MODID)
public class SnowGlobeMod
{
    public static final String MODID = "snowglobe";

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MODID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, MODID);
    public static final DeferredRegister.DataComponents COMPONENTS = DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, MODID);

    // The globes you place in the world, each showing its build space in miniature.
    public static final DeferredBlock<GlobeBlock> SNOW_GLOBE = BLOCKS.register("snow_globe", () -> new GlobeBlock(GlobeKind.SNOW_GLOBE,
            BlockBehaviour.Properties.of().mapColor(MapColor.WOOD).strength(1.0f).sound(SoundType.GLASS).noOcclusion()));
    public static final DeferredBlock<GlobeBlock> TERRARIUM = BLOCKS.register("terrarium", () -> new GlobeBlock(GlobeKind.TERRARIUM,
            BlockBehaviour.Properties.of().mapColor(MapColor.WOOD).strength(1.0f).sound(SoundType.GLASS).noOcclusion()));
    public static final DeferredBlock<GlobeBlock> STORM_GLOBE = BLOCKS.register("storm_globe", () -> new GlobeBlock(GlobeKind.STORM_GLOBE,
            BlockBehaviour.Properties.of().mapColor(MapColor.DEEPSLATE).strength(1.5f).sound(SoundType.GLASS).noOcclusion()));
    public static final DeferredBlock<GlobeBlock> SHIP_BOTTLE = BLOCKS.register("ship_bottle", () -> new GlobeBlock(GlobeKind.SHIP_BOTTLE,
            BlockBehaviour.Properties.of().mapColor(MapColor.WOOD).strength(1.0f).sound(SoundType.GLASS).noOcclusion()));

    public static final DeferredItem<GlobeItem> SNOW_GLOBE_ITEM = ITEMS.register("snow_globe", () -> new GlobeItem(SNOW_GLOBE.get(), new Item.Properties().stacksTo(1)));
    public static final DeferredItem<GlobeItem> TERRARIUM_ITEM = ITEMS.register("terrarium", () -> new GlobeItem(TERRARIUM.get(), new Item.Properties().stacksTo(1)));
    public static final DeferredItem<GlobeItem> STORM_GLOBE_ITEM = ITEMS.register("storm_globe", () -> new GlobeItem(STORM_GLOBE.get(), new Item.Properties().stacksTo(1)));
    public static final DeferredItem<GlobeItem> SHIP_BOTTLE_ITEM = ITEMS.register("ship_bottle", () -> new GlobeItem(SHIP_BOTTLE.get(), new Item.Properties().stacksTo(1)));

    // The unbreakable glass shell around each globe's build space. Right-click it with an empty hand to leave.
    public static final DeferredBlock<GlobeWallBlock> GLOBE_WALL = BLOCKS.register("globe_wall", () -> new GlobeWallBlock(BlockBehaviour.Properties.of()
            .mapColor(MapColor.ICE).strength(-1.0f, 3600000.0f).noLootTable().noOcclusion().sound(SoundType.GLASS)
            .isValidSpawn((state, level, pos, type) -> false)
            .isRedstoneConductor((state, level, pos) -> false)
            .isSuffocating((state, level, pos) -> false)
            .isViewBlocking((state, level, pos) -> false)));

    @SuppressWarnings("DataFlowIssue") // the data fixer type may be null
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<GlobeBlockEntity>> GLOBE_BLOCK_ENTITY = BLOCK_ENTITIES.register("snow_globe",
            () -> BlockEntityType.Builder.of(GlobeBlockEntity::new, SNOW_GLOBE.get(), TERRARIUM.get(), STORM_GLOBE.get(), SHIP_BOTTLE.get()).build(null));

    /** A globe's id and contents, carried by the item when you pick a globe up, so placing it again restores it. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<CustomData>> GLOBE_DATA = COMPONENTS.registerComponentType("globe",
            builder -> builder.persistent(CustomData.CODEC).networkSynchronized(CustomData.STREAM_CODEC));

    public SnowGlobeMod(IEventBus modEventBus, ModContainer container)
    {
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        BLOCK_ENTITIES.register(modEventBus);
        COMPONENTS.register(modEventBus);
        modEventBus.addListener(this::addCreative);
        modEventBus.addListener(GlobeNetwork::register);

        NeoForge.EVENT_BUS.addListener(GlobeSpace::onServerTick);
        NeoForge.EVENT_BUS.addListener(GlobeSpace::onServerStopped);
        NeoForge.EVENT_BUS.addListener(GlobeImport::registerCommands);
        NeoForge.EVENT_BUS.addListener(GlobeView::onLeftClickBlock);
    }

    private void addCreative(BuildCreativeModeTabContentsEvent event)
    {
        if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS)
        {
            event.accept(SNOW_GLOBE_ITEM);
            event.accept(TERRARIUM_ITEM);
            event.accept(STORM_GLOBE_ITEM);
            event.accept(SHIP_BOTTLE_ITEM);
        }
    }
}
