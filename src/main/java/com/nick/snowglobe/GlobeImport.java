package com.nick.snowglobe;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Item;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.stream.Stream;

/**
 * Fills a globe with a schematic: Minecraft structure files (.nbt), which is also what Create saves.
 * The globe grows to the smallest size the schematic fits in. Use {@code /globe import <file>} with a file
 * from the game's {@code schematics} folder (Create's folder), or right-click a globe holding a Create schematic.
 * {@code /globe size <16|24|32>} resizes a globe by hand, keeping what's inside.
 */
public final class GlobeImport
{
    private static final ResourceLocation CREATE_SCHEMATIC = ResourceLocation.fromNamespaceAndPath("create", "schematic");
    private static final ResourceLocation CREATE_FILE = ResourceLocation.fromNamespaceAndPath("create", "schematic_file");
    private static final ResourceLocation CREATE_OWNER = ResourceLocation.fromNamespaceAndPath("create", "schematic_owner");

    private GlobeImport() {}

    private static Path schematicsDir(MinecraftServer server)
    {
        return server.getServerDirectory().resolve("schematics").toAbsolutePath().normalize();
    }

    static void registerCommands(RegisterCommandsEvent event)
    {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("globe")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("import")
                        .then(Commands.argument("file", StringArgumentType.greedyString())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                        listSchematics(context.getSource().getServer()), builder))
                                .executes(GlobeImport::runImport)))
                .then(Commands.literal("size")
                        .then(Commands.argument("size", IntegerArgumentType.integer(GlobeSpace.DEFAULT_SIZE, GlobeSpace.MAX_SIZE))
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                        GlobeSpace.SIZES.stream().map(String::valueOf), builder))
                                .executes(context -> runSize(context, false))
                                .then(Commands.literal("force").executes(context -> runSize(context, true))))));
    }

    private static List<String> listSchematics(MinecraftServer server)
    {
        Path dir = schematicsDir(server);
        if (!Files.isDirectory(dir))
            return List.of();
        try (Stream<Path> files = Files.walk(dir, 4))
        {
            return files.filter(p -> p.toString().endsWith(".nbt"))
                    .map(p -> dir.relativize(p).toString().replace('\\', '/'))
                    .sorted().toList();
        }
        catch (IOException e)
        {
            return List.of();
        }
    }

    private static int runImport(CommandContext<CommandSourceStack> context)
    {
        CommandSourceStack source = context.getSource();
        Integer id = targetGlobe(source);
        if (id == null)
            return 0;
        Path dir = schematicsDir(source.getServer());
        Path file = dir.resolve(StringArgumentType.getString(context, "file")).normalize();
        if (!file.startsWith(dir))
        {
            source.sendFailure(Component.translatable("snowglobe.import.not_found", file.getFileName().toString()));
            return 0;
        }
        source.sendSystemMessage(importInto(source.getServer(), id, file, source.getPlayer()));
        return 1;
    }

    private static int runSize(CommandContext<CommandSourceStack> context, boolean force)
    {
        CommandSourceStack source = context.getSource();
        Integer id = targetGlobe(source);
        if (id == null)
            return 0;
        int wanted = IntegerArgumentType.getInteger(context, "size");
        int size = GlobeSpace.SIZES.stream().filter(s -> s >= wanted).findFirst().orElse(GlobeSpace.MAX_SIZE);
        // Shrinking never quietly destroys blocks: only in creative, and only when asked to.
        int lost = GlobeSpace.blocksLostByResize(source.getServer(), id, size);
        if (lost > 0 && !(force && creative(source.getPlayer())))
        {
            source.sendFailure(Component.translatable("snowglobe.size.would_lose", size + "×" + size + "×" + size, lost));
            return 0;
        }
        GlobeSpace.resize(source.getServer(), id, size);
        source.sendSystemMessage(Component.translatable("snowglobe.size.done", size + "×" + size + "×" + size));
        return 1;
    }

    /** The globe the player is inside, or else the globe block they're looking at. Tells them if there's neither. */
    @Nullable
    private static Integer targetGlobe(CommandSourceStack source)
    {
        ServerPlayer player = source.getPlayer();
        if (player == null)
            source.sendFailure(Component.translatable("snowglobe.import.no_player"));
        else if (player.level().dimension() == GlobeSpace.LEVEL && GlobeSpace.idAt(player.server, player.blockPosition()) >= 0)
            return GlobeSpace.idAt(player.server, player.blockPosition());
        else if (player.pick(6, 0, false) instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK
                && player.level().getBlockEntity(hit.getBlockPos()) instanceof GlobeBlockEntity globe)
            return globe.ensureGlobe(player.server);
        else
            source.sendFailure(Component.translatable("snowglobe.import.no_globe"));
        return null;
    }

    public static boolean isCreateSchematic(ItemStack stack)
    {
        return CREATE_SCHEMATIC.equals(BuiltInRegistries.ITEM.getKey(stack.getItem())) && component(stack, CREATE_FILE) != null;
    }

    /** Reads one of another mod's item components by name, without depending on that mod. */
    @Nullable
    private static String component(ItemStack stack, ResourceLocation name)
    {
        DataComponentType<?> type = BuiltInRegistries.DATA_COMPONENT_TYPE.get(name);
        Object value = type == null ? null : stack.get(type);
        return value == null ? null : value.toString();
    }

    /** A player asked to import the schematic in their hand into the globe at {@code pos}: check it's real and in reach. */
    public static void importHeldAt(ServerPlayer player, BlockPos pos)
    {
        if (!player.level().isLoaded(pos) || player.distanceToSqr(pos.getCenter()) > 8 * 8
                || !(player.level().getBlockEntity(pos) instanceof GlobeBlockEntity globe))
            return;
        for (InteractionHand hand : InteractionHand.values())
        {
            ItemStack stack = player.getItemInHand(hand);
            if (isCreateSchematic(stack))
            {
                player.swing(hand, true);
                importHeldSchematic(player, stack, globe);
                return;
            }
        }
    }

    /** Right-clicking a globe with a Create schematic in hand: import that schematic. */
    static void importHeldSchematic(ServerPlayer player, ItemStack stack, GlobeBlockEntity globe)
    {
        // Create keeps schematics uploaded at a schematic table per player; local ones sit in the folder itself.
        Path dir = schematicsDir(player.server);
        String name = component(stack, CREATE_FILE);
        String owner = component(stack, CREATE_OWNER);
        if (name == null)
            return;
        Path file = dir.resolve("uploaded").resolve(owner == null ? "" : owner).resolve(name).normalize();
        if (!Files.exists(file))
            file = dir.resolve(name).normalize();
        if (!file.startsWith(dir))
            return;
        player.sendSystemMessage(importInto(player.server, globe.ensureGlobe(player.server), file, player));
    }

    /** Creative players (and the server itself) build for free; everyone else pays for every block. */
    private static boolean creative(@Nullable ServerPlayer player)
    {
        return player == null || player.getAbilities().instabuild;
    }

    /**
     * Fills a globe with the schematic in {@code file}, centred, growing the globe if it needs to.
     * In creative it replaces whatever is there. In survival the globe must be empty (nothing is thrown away),
     * and {@code player} pays for every block from their inventory (nothing comes from nowhere).
     * Returns a message for the player.
     */
    public static Component importInto(MinecraftServer server, int id, Path file, @Nullable ServerPlayer player)
    {
        ServerLevel level = server.getLevel(GlobeSpace.LEVEL);
        String fileName = file.getFileName().toString();
        if (level == null)
            return Component.translatable("snowglobe.import.failed", fileName, "no globe dimension");
        if (!Files.isRegularFile(file))
            return Component.translatable("snowglobe.import.not_found", fileName);

        StructureTemplate template;
        try
        {
            CompoundTag tag = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
            template = server.getStructureManager().readStructure(tag);
        }
        catch (IOException | RuntimeException e)
        {
            return Component.translatable("snowglobe.import.failed", fileName, String.valueOf(e.getMessage()));
        }

        GlobeSavedData data = GlobeSavedData.get(server);
        GlobeKind kind = data.kindOf(id);
        Vec3i dims = template.getSize();
        String dimsText = dims.getX() + "×" + dims.getY() + "×" + dims.getZ();
        Integer size = GlobeSpace.SIZES.stream().filter(s -> kind.fits(dims.getX(), dims.getY(), dims.getZ(), s)).findFirst().orElse(null);
        if (size == null)
            return Component.translatable("snowglobe.import.too_big", fileName, dimsText, GlobeSpace.MAX_SIZE + "×" + GlobeSpace.MAX_SIZE + "×" + GlobeSpace.MAX_SIZE);

        if (creative(player))
            GlobeSpace.clearSpace(level, GlobeSpace.interiorMin(server, id), kind, data.sizeOf(id), true);
        else
        {
            if (!GlobeSpace.isEmpty(server, id))
                return Component.translatable("snowglobe.import.not_empty");
            Map<Item, Integer> cost = cost(template, level);
            Component missing = missing(player, cost);
            if (missing != null)
                return Component.translatable("snowglobe.import.missing", fileName, missing);
            pay(player, cost);
        }
        GlobeSpace.resize(server, id, size);
        // Only now: growing may have moved the globe to a roomier slot.
        BlockPos min = GlobeSpace.interiorMin(server, id);

        // Centre it; a terrarium stands it on the floor, round globes and bottles float it in the middle.
        int y = kind.space == GlobeKind.Space.CUBE ? 0 : (size - dims.getY()) / 2;
        BlockPos origin = min.offset((size - dims.getX()) / 2, y, (size - dims.getZ()) / 2);
        StructurePlaceSettings settings = new StructurePlaceSettings().addProcessor(new InsideOnly(min, kind, size));
        template.placeInWorld(level, origin, origin, settings, level.random, Block.UPDATE_CLIENTS);

        GlobeSpace.refreshSnapshot(server, id);
        return Component.translatable("snowglobe.import.done", fileName, dimsText, size + "×" + size + "×" + size);
    }

    // ---- What a schematic costs in survival ----

    /** The items needed to build a schematic: one per block, the way you'd place them by hand. */
    private static Map<Item, Integer> cost(StructureTemplate template, ServerLevel level)
    {
        CompoundTag saved = template.save(new CompoundTag());
        ListTag paletteTag = saved.contains("palette") ? saved.getList("palette", Tag.TAG_COMPOUND)
                : saved.getList("palettes", Tag.TAG_LIST).getList(0);
        List<BlockState> palette = new ArrayList<>();
        for (int i = 0; i < paletteTag.size(); i++)
            palette.add(NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), paletteTag.getCompound(i)));
        Map<Item, Integer> cost = new LinkedHashMap<>();
        ListTag blocks = saved.getList("blocks", Tag.TAG_COMPOUND);
        for (int i = 0; i < blocks.size(); i++)
        {
            int index = blocks.getCompound(i).getInt("state");
            if (index >= 0 && index < palette.size())
                addCost(palette.get(index), level, cost);
        }
        return cost;
    }

    private static void addCost(BlockState state, ServerLevel level, Map<Item, Integer> cost)
    {
        Block block = state.getBlock();
        if (state.isAir() || block == Blocks.STRUCTURE_VOID || block == Blocks.WATER)
            return; // water is free, as in vanilla: two sources make endless more
        if (block == Blocks.LAVA)
        {
            if (state.getFluidState().isSource())
                cost.merge(Items.LAVA_BUCKET, 1, Integer::sum);
            return;
        }
        // Two-block things (doors, tall flowers, beds) cost one item, counted on one of their halves.
        if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF) && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER)
            return;
        if (state.hasProperty(BlockStateProperties.BED_PART) && state.getValue(BlockStateProperties.BED_PART) == BedPart.HEAD)
            return;
        // The item you'd pick for this block (a wall torch is a torch).
        Item item;
        try
        {
            item = block.getCloneItemStack(level, BlockPos.ZERO, state).getItem();
        }
        catch (RuntimeException e)
        {
            item = block.asItem();
        }
        if (item == Items.AIR)
            item = block.asItem();
        if (item == Items.AIR)
            return; // nothing to pay with (fire, portals, ...)
        int count = state.hasProperty(BlockStateProperties.SLAB_TYPE) && state.getValue(BlockStateProperties.SLAB_TYPE) == SlabType.DOUBLE ? 2 : 1;
        cost.merge(item, count, Integer::sum);
    }

    /** What the player is short of, as a short list, or null if they have it all. */
    @Nullable
    private static Component missing(ServerPlayer player, Map<Item, Integer> cost)
    {
        List<String> parts = new ArrayList<>();
        int more = 0;
        for (Map.Entry<Item, Integer> entry : cost.entrySet())
        {
            int short_ = entry.getValue() - player.getInventory().countItem(entry.getKey());
            if (short_ <= 0)
                continue;
            if (parts.size() < 6)
                parts.add(short_ + "× " + entry.getKey().getDescription().getString());
            else
                more++;
        }
        if (parts.isEmpty())
            return null;
        String text = String.join(", ", parts) + (more > 0 ? " and " + more + " more" : "");
        return Component.literal(text);
    }

    private static void pay(ServerPlayer player, Map<Item, Integer> cost)
    {
        cost.forEach((item, count) ->
                player.getInventory().clearOrCountMatchingItems(stack -> stack.is(item), count, player.inventoryMenu.getCraftSlots()));
        // Lava buckets are emptied into the globe; you keep the buckets.
        Integer buckets = cost.get(Items.LAVA_BUCKET);
        if (buckets != null)
            player.getInventory().placeItemBackInInventory(new ItemStack(Items.BUCKET, buckets));
    }

    /** Leaves out any block that would land outside the build space (e.g. in a round globe's corners, on its walls). */
    private static final class InsideOnly extends StructureProcessor
    {
        private final BlockPos min;
        private final GlobeKind kind;
        private final int size;

        InsideOnly(BlockPos min, GlobeKind kind, int size)
        {
            this.min = min;
            this.kind = kind;
            this.size = size;
        }

        @Nullable
        @Override
        public StructureTemplate.StructureBlockInfo processBlock(LevelReader level, BlockPos offset, BlockPos pos,
                                                                 StructureTemplate.StructureBlockInfo original,
                                                                 StructureTemplate.StructureBlockInfo placed, StructurePlaceSettings settings)
        {
            BlockPos p = placed.pos().subtract(min);
            return kind.inside(p.getX(), p.getY(), p.getZ(), size) ? placed : null;
        }

        @Override
        protected StructureProcessorType<?> getType()
        {
            return StructureProcessorType.NOP;
        }
    }
}
