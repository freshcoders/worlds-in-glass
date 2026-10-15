package com.nick.snowglobe;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * Fills a globe with a schematic: Minecraft structure files (.nbt), which is also what Create saves.
 * Use {@code /globe import <file>} with a file from the game's {@code schematics} folder (Create's folder),
 * or right-click a globe holding a Create schematic.
 */
public final class GlobeImport
{
    private static final ResourceLocation CREATE_SCHEMATIC = new ResourceLocation("create", "schematic");

    private GlobeImport() {}

    private static Path schematicsDir(MinecraftServer server)
    {
        return server.getServerDirectory().toPath().resolve("schematics").normalize();
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
                                .executes(GlobeImport::runImport))));
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
        ServerPlayer player = source.getPlayer();
        if (player == null)
        {
            source.sendFailure(Component.translatable("snowglobe.import.no_player"));
            return 0;
        }
        Integer id = targetGlobe(player);
        if (id == null)
        {
            source.sendFailure(Component.translatable("snowglobe.import.no_globe"));
            return 0;
        }
        Path dir = schematicsDir(source.getServer());
        Path file = dir.resolve(StringArgumentType.getString(context, "file")).normalize();
        if (!file.startsWith(dir))
        {
            source.sendFailure(Component.translatable("snowglobe.import.not_found", file.getFileName().toString()));
            return 0;
        }
        Component result = importInto(source.getServer(), id, file);
        source.sendSystemMessage(result);
        return 1;
    }

    /** The globe the player is inside, or else the globe block they're looking at. */
    @Nullable
    private static Integer targetGlobe(ServerPlayer player)
    {
        if (player.level().dimension() == GlobeSpace.LEVEL)
            return GlobeSpace.idAt(player.blockPosition());
        HitResult hit = player.pick(6, 0, false);
        if (hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK
                && player.level().getBlockEntity(blockHit.getBlockPos()) instanceof GlobeBlockEntity globe)
            return globe.ensureGlobe(player.server);
        return null;
    }

    /** Right-clicking a globe with a Create schematic in hand: import that schematic. True if it was one. */
    static boolean tryImportHeldSchematic(ServerPlayer player, InteractionHand hand, GlobeBlockEntity globe)
    {
        ItemStack held = player.getItemInHand(hand);
        CompoundTag tag = held.getTag();
        if (!CREATE_SCHEMATIC.equals(ForgeRegistries.ITEMS.getKey(held.getItem())) || tag == null || !tag.contains("File"))
            return false;
        // Create keeps uploaded schematics per player on the server; local ones sit in the folder itself.
        Path dir = schematicsDir(player.server);
        String name = tag.getString("File");
        Path file = dir.resolve("uploaded").resolve(tag.getString("Owner")).resolve(name).normalize();
        if (!Files.exists(file))
            file = dir.resolve(name).normalize();
        if (!file.startsWith(dir))
            return true;
        player.sendSystemMessage(importInto(player.server, globe.ensureGlobe(player.server), file));
        return true;
    }

    /** Replaces a globe's contents with the schematic in {@code file}, centred. Returns a message for the player. */
    public static Component importInto(MinecraftServer server, int id, Path file)
    {
        ServerLevel level = server.getLevel(GlobeSpace.LEVEL);
        if (level == null)
            return Component.translatable("snowglobe.import.failed", file.getFileName().toString(), "no globe dimension");
        if (!Files.isRegularFile(file))
            return Component.translatable("snowglobe.import.not_found", file.getFileName().toString());

        StructureTemplate template;
        try
        {
            CompoundTag tag = NbtIo.readCompressed(file.toFile());
            template = server.getStructureManager().readStructure(tag);
        }
        catch (IOException | RuntimeException e)
        {
            return Component.translatable("snowglobe.import.failed", file.getFileName().toString(), e.getMessage());
        }

        Vec3i size = template.getSize();
        int max = GlobeSpace.SIZE;
        if (size.getX() > max || size.getY() > max || size.getZ() > max)
            return Component.translatable("snowglobe.import.too_big", file.getFileName().toString(),
                    size.getX() + "×" + size.getY() + "×" + size.getZ(), max + "×" + max + "×" + max);

        GlobeKind kind = GlobeSavedData.get(server).kindOf(id);
        BlockPos min = GlobeSpace.interiorMin(id);
        clear(level, min, kind);

        // Centre it; a terrarium stands it on the floor, round globes and bottles float it in the middle.
        int y = kind.space == GlobeKind.Space.CUBE ? 0 : (max - size.getY()) / 2;
        BlockPos origin = min.offset((max - size.getX()) / 2, y, (max - size.getZ()) / 2);
        StructurePlaceSettings settings = new StructurePlaceSettings().addProcessor(new InsideOnly(min, kind));
        template.placeInWorld(level, origin, origin, settings, level.random, Block.UPDATE_CLIENTS);

        GlobeSavedData data = GlobeSavedData.get(server);
        data.storeSnapshot(id, GlobeSnapshot.capture(level, min, kind));
        return Component.translatable("snowglobe.import.done", file.getFileName().toString(),
                size.getX() + "×" + size.getY() + "×" + size.getZ());
    }

    /** Empties a globe's build space: blocks and everything living there except players. */
    private static void clear(ServerLevel level, BlockPos min, GlobeKind kind)
    {
        int size = GlobeSpace.SIZE;
        for (Entity entity : level.getEntitiesOfClass(Entity.class, new AABB(min, min.offset(size, size, size)), e -> !(e instanceof Player)))
            entity.discard();
        for (int x = 0; x < size; x++)
            for (int y = 0; y < size; y++)
                for (int z = 0; z < size; z++)
                    if (kind.inside(x, y, z))
                        level.setBlock(min.offset(x, y, z), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_SUPPRESS_DROPS);
    }

    /** Leaves out any block that would land outside the build space (e.g. in a round globe's corners, on its walls). */
    private static final class InsideOnly extends StructureProcessor
    {
        private final BlockPos min;
        private final GlobeKind kind;

        InsideOnly(BlockPos min, GlobeKind kind)
        {
            this.min = min;
            this.kind = kind;
        }

        @Nullable
        @Override
        public StructureTemplate.StructureBlockInfo processBlock(LevelReader level, BlockPos offset, BlockPos pos,
                                                                 StructureTemplate.StructureBlockInfo original,
                                                                 StructureTemplate.StructureBlockInfo placed, StructurePlaceSettings settings)
        {
            BlockPos p = placed.pos().subtract(min);
            return kind.inside(p.getX(), p.getY(), p.getZ()) ? placed : null;
        }

        @Override
        protected StructureProcessorType<?> getType()
        {
            return StructureProcessorType.NOP;
        }
    }
}
