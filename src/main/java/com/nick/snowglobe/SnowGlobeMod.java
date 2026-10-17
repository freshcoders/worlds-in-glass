package com.nick.snowglobe;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import com.nick.snowglobe.net.GlobeNetwork;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.EnumMap;
import java.util.Map;

@Mod(SnowGlobeMod.MODID)
public class SnowGlobeMod
{
    public static final String MODID = "snowglobe";

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MODID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, MODID);
    public static final DeferredRegister.DataComponents COMPONENTS = DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, MODID);
    public static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);
    public static final DeferredRegister<net.minecraft.world.item.crafting.RecipeSerializer<?>> RECIPES =
            DeferredRegister.create(Registries.RECIPE_SERIALIZER, MODID);

    /** Twin globes: two globes opening onto one world. */
    public static final DeferredHolder<net.minecraft.world.item.crafting.RecipeSerializer<?>, net.minecraft.world.item.crafting.RecipeSerializer<TwinGlobeRecipe>> TWIN_GLOBE =
            RECIPES.register("twin_globe", () -> new net.minecraft.world.item.crafting.SimpleCraftingRecipeSerializer<>(TwinGlobeRecipe::new));

    /** The globes you place in the world, each showing its build space in miniature: one block and item per kind. */
    public static final Map<GlobeKind, DeferredBlock<GlobeBlock>> GLOBES = new EnumMap<>(GlobeKind.class);
    public static final Map<GlobeKind, DeferredItem<GlobeItem>> GLOBE_ITEMS = new EnumMap<>(GlobeKind.class);

    static
    {
        for (GlobeKind kind : GlobeKind.values())
        {
            DeferredBlock<GlobeBlock> block = BLOCKS.register(kind.getName(), () -> new GlobeBlock(kind, properties(kind)));
            GLOBES.put(kind, block);
            GLOBE_ITEMS.put(kind, ITEMS.register(kind.getName(), () -> new GlobeItem(block.get(), new Item.Properties().stacksTo(1))));
        }
    }

    private static BlockBehaviour.Properties properties(GlobeKind kind)
    {
        BlockBehaviour.Properties properties = BlockBehaviour.Properties.of().strength(1.0f).sound(SoundType.GLASS).noOcclusion();
        return switch (kind)
        {
            case STORM_GLOBE -> properties.mapColor(MapColor.DEEPSLATE).strength(1.5f);
            case DESERT_GLOBE -> properties.mapColor(MapColor.SAND);
            case AQUARIUM -> properties.mapColor(MapColor.WARPED_NYLIUM);
            case STAR_GLOBE -> properties.mapColor(MapColor.COLOR_BLACK).lightLevel(state -> 4);
            // The embers inside light up the room around the jar.
            case NETHER_JAR -> properties.mapColor(MapColor.NETHER).strength(1.5f).lightLevel(state -> 10);
            default -> properties.mapColor(MapColor.WOOD);
        };
    }

    public static DeferredBlock<GlobeBlock> globe(GlobeKind kind)
    {
        return GLOBES.get(kind);
    }

    // The unbreakable glass shell around each globe's build space. Right-click it with an empty hand to leave.
    public static final DeferredBlock<GlobeWallBlock> GLOBE_WALL = BLOCKS.register("globe_wall", () -> new GlobeWallBlock(BlockBehaviour.Properties.of()
            .mapColor(MapColor.ICE).strength(-1.0f, 3600000.0f).noLootTable().noOcclusion().sound(SoundType.GLASS)
            .isValidSpawn((state, level, pos, type) -> false)
            .isRedstoneConductor((state, level, pos) -> false)
            .isSuffocating((state, level, pos) -> false)
            .isViewBlocking((state, level, pos) -> false)));

    @SuppressWarnings("DataFlowIssue") // the data fixer type may be null
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<GlobeBlockEntity>> GLOBE_BLOCK_ENTITY = BLOCK_ENTITIES.register("snow_globe",
            () -> BlockEntityType.Builder.of(GlobeBlockEntity::new, GLOBES.values().stream().map(DeferredBlock::get).toArray(Block[]::new)).build(null));

    /** A display turntable: a globe standing on it slowly spins round. */
    public static final DeferredBlock<Block> TURNTABLE = BLOCKS.register("globe_turntable", () -> new TurntableBlock(BlockBehaviour.Properties.of()
            .mapColor(MapColor.WOOD).strength(1.5f).sound(SoundType.WOOD).noOcclusion()));
    public static final DeferredItem<net.minecraft.world.item.BlockItem> TURNTABLE_ITEM = ITEMS.registerSimpleBlockItem(TURNTABLE);

    /** The glassblower's pipe, and the glass bulb it blows: every globe starts as one. */
    public static final DeferredItem<GlassblowerPipeItem> PIPE = ITEMS.register("glassblower_pipe",
            () -> new GlassblowerPipeItem(new Item.Properties().durability(64)));
    public static final DeferredItem<Item> GLASS_BULB = ITEMS.register("glass_bulb", () -> new Item(new Item.Properties()));

    /** The Globe Keeper's Handbook: lore, recipes and how globes work. */
    public static final DeferredItem<HandbookItem> HANDBOOK = ITEMS.register("handbook", () -> new HandbookItem(new Item.Properties().stacksTo(1)));

    /** A collector's shelf for two globes, showing their miniatures. */
    public static final DeferredBlock<GlobeShelfBlock> SHELF = BLOCKS.register("globe_shelf", () -> new GlobeShelfBlock(BlockBehaviour.Properties.of()
            .mapColor(MapColor.WOOD).strength(1.5f).sound(SoundType.WOOD).noOcclusion()));
    public static final DeferredItem<net.minecraft.world.item.BlockItem> SHELF_ITEM = ITEMS.registerSimpleBlockItem(SHELF);
    @SuppressWarnings("DataFlowIssue")
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<GlobeShelfBlockEntity>> SHELF_BLOCK_ENTITY = BLOCK_ENTITIES.register("globe_shelf",
            () -> BlockEntityType.Builder.of(GlobeShelfBlockEntity::new, SHELF.get()).build(null));

    /** A globe's id and contents, carried by the item when you pick a globe up, so placing it again restores it. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<CustomData>> GLOBE_DATA = COMPONENTS.registerComponentType("globe",
            builder -> builder.persistent(CustomData.CODEC).networkSynchronized(CustomData.STREAM_CODEC));

    /** Everything from this mod in one creative tab. */
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> TAB = TABS.register("globes", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.snowglobe"))
            .icon(() -> new ItemStack(GLOBE_ITEMS.get(GlobeKind.SNOW_GLOBE).get()))
            .displayItems((parameters, output) -> {
                for (GlobeKind kind : GlobeKind.values())
                    output.accept(GLOBE_ITEMS.get(kind).get());
                output.accept(PIPE.get());
                output.accept(GLASS_BULB.get());
                output.accept(SHELF_ITEM.get());
                output.accept(TURNTABLE_ITEM.get());
                output.accept(HANDBOOK.get());
            })
            .build());

    public SnowGlobeMod(IEventBus modEventBus, ModContainer container)
    {
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        BLOCK_ENTITIES.register(modEventBus);
        COMPONENTS.register(modEventBus);
        TABS.register(modEventBus);
        RECIPES.register(modEventBus);
        modEventBus.addListener(GlobeNetwork::register);
        container.registerConfig(ModConfig.Type.CLIENT, SnowGlobeConfig.SPEC);

        NeoForge.EVENT_BUS.addListener(GlobeSpace::onServerTick);
        NeoForge.EVENT_BUS.addListener(GlobeSpace::onServerStopped);
        NeoForge.EVENT_BUS.addListener(GlobeImport::registerCommands);
        NeoForge.EVENT_BUS.addListener(GlobeView::onLeftClickBlock);
        NeoForge.EVENT_BUS.addListener(GlobeTrades::onWandererTrades);
        NeoForge.EVENT_BUS.addListener(GlobeWakeUp::onDeath);
        NeoForge.EVENT_BUS.addListener(GlobeWakeUp::onClone);
        NeoForge.EVENT_BUS.addListener(GlobeWakeUp::onRespawn);
    }
}
