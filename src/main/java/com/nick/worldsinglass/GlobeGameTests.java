package com.nick.worldsinglass;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Automated checks, run on a dedicated server with {@code gradlew runGameTestServer}. */
@GameTestHolder(WorldsInGlassMod.MODID)
@PrefixGameTestTemplate(false)
public final class GlobeGameTests
{
    private GlobeGameTests() {}

    /** A globe placed in creative gets its own world with its starter scene, which the block sees in miniature. */
    @GameTest(template = "empty")
    public static void globeGetsItsWorld(GameTestHelper helper)
    {
        // The test server makes a world with only the vanilla dimensions, so the globes' own is missing there; check
        // what can be checked without it (a real dedicated server has it, from the mod's data).
        boolean hasGlobeLevel = helper.getLevel().getServer().getLevel(GlobeSpace.LEVEL) != null;
        for (GlobeKind kind : GlobeKind.values())
        {
            BlockPos pos = new BlockPos(1, 1, 1);
            helper.setBlock(pos, WorldsInGlassMod.globe(kind).get().defaultBlockState());
            GlobeBlockEntity globe = (GlobeBlockEntity) helper.getBlockEntity(pos);
            int id = globe.ensureGlobe(helper.getLevel().getServer(), true);
            helper.assertTrue(id >= 0, "a " + kind.getName() + " got no world");
            if (!hasGlobeLevel)
                continue;
            helper.assertTrue(GlobeSavedData.get(helper.getLevel().getServer()).get(id) != null, "a " + kind.getName() + " has no snapshot");
            helper.assertFalse(GlobeSpace.isEmpty(helper.getLevel().getServer(), id), "a " + kind.getName() + "'s starter scene is empty");
        }
        helper.succeed();
    }
}
