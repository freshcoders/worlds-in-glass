package com.nick.snowglobe;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

@Mod(SnowGlobeMod.MODID)
public class SnowGlobeMod
{
    public static final String MODID = "snowglobe";

    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, MODID);
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, MODID);

    // The globe you place in the world: a wooden base with a glass case showing the miniature.
    public static final RegistryObject<Block> SNOW_GLOBE = BLOCKS.register("snow_globe", () -> new GlobeBlock(BlockBehaviour.Properties.of()
            .mapColor(MapColor.WOOD).strength(1.0f).sound(SoundType.GLASS).noOcclusion()));
    public static final RegistryObject<Item> SNOW_GLOBE_ITEM = ITEMS.register("snow_globe", () -> new BlockItem(SNOW_GLOBE.get(), new Item.Properties()));

    // The unbreakable glass shell around each globe's build space. Right-click it with an empty hand to leave.
    public static final RegistryObject<Block> GLOBE_WALL = BLOCKS.register("globe_wall", () -> new GlobeWallBlock(BlockBehaviour.Properties.of()
            .mapColor(MapColor.ICE).strength(-1.0f, 3600000.0f).noLootTable().noOcclusion().sound(SoundType.GLASS)
            .isValidSpawn((state, level, pos, type) -> false)
            .isRedstoneConductor((state, level, pos) -> false)
            .isSuffocating((state, level, pos) -> false)
            .isViewBlocking((state, level, pos) -> false)));

    @SuppressWarnings("DataFlowIssue") // the data fixer type may be null
    public static final RegistryObject<BlockEntityType<GlobeBlockEntity>> GLOBE_BLOCK_ENTITY = BLOCK_ENTITIES.register("snow_globe",
            () -> BlockEntityType.Builder.of(GlobeBlockEntity::new, SNOW_GLOBE.get()).build(null));

    public SnowGlobeMod()
    {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        BLOCK_ENTITIES.register(modEventBus);
        modEventBus.addListener(this::addCreative);

        MinecraftForge.EVENT_BUS.addListener(GlobeSpace::onServerTick);
    }

    private void addCreative(BuildCreativeModeTabContentsEvent event)
    {
        if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS)
            event.accept(SNOW_GLOBE_ITEM);
    }
}
