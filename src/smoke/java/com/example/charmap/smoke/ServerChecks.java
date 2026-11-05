package com.example.charmap.smoke;

import com.example.charmap.CharMap;
import com.example.charmap.model.ChunkSummary;
import com.example.charmap.model.Coords;
import com.example.charmap.model.FillOrder;
import com.example.charmap.model.McaFile;
import com.example.charmap.model.Nbt;
import com.example.charmap.net.Net;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

// Runs on a real dedicated server: just getting here proves the mod loads there without any PC-only code. Then it reads a real save the same way the fill does.
@GameTestHolder(CharMap.MODID)
@PrefixGameTestTemplate(false)
public final class ServerChecks {
    private ServerChecks() {}

    @GameTest(template = "empty")
    public static void channelExists(GameTestHelper h) {
        h.assertTrue(Net.CHANNEL != null, "no channel");
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void readsARealSaveFile(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos at = h.absolutePos(BlockPos.ZERO);
        level.getServer().saveEverything(true, true, true);
        try {
            Path dir = DimensionType.getStorageFolder(level.dimension(), level.getServer().getWorldPath(LevelResource.ROOT)).resolve("region");
            int cx = at.getX() >> 4, cz = at.getZ() >> 4;
            Path file = dir.resolve("r." + Coords.regionOfChunk(cx) + "." + Coords.regionOfChunk(cz) + ".mca");
            h.assertTrue(Files.exists(file), "no region file at " + file);
            McaFile mca = McaFile.read(file);
            int index = Coords.chunkIndex(cx, cz);
            h.assertTrue(mca.has(index), "the chunk isn't in the file");
            byte[] data = mca.chunk(index);
            h.assertTrue(data != null, "couldn't unpack the chunk");
            ChunkSummary s = ChunkSummary.from(Nbt.read(data), Integer.MIN_VALUE);
            h.assertTrue(s != null, "chunk wasn't read as finished");
            h.assertTrue(s.chunkX == cx && s.chunkZ == cz, "wrong chunk position");
            int checked = 0;
            for (int c = 0; c < 256; c++) {
                int x = cx * 16 + (c & 15), z = cz * 16 + (c >> 4);
                int real = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
                h.assertTrue(s.top[c] >= 0, "no top block");
                h.assertTrue(s.topY[c] == real, "column " + c + " top at " + s.topY[c] + " but really " + real);
                String expected = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(level.getBlockState(new BlockPos(x, real, z)).getBlock()).toString();
                h.assertTrue(ChunkSummary.baseName(s.blocks[s.top[c]]).equals(expected), "column " + c + " top " + s.blocks[s.top[c]] + " but really " + expected);
                checked++;
            }
            // Over the network and back, like a server fill would send it
            List<ChunkSummary> back = ChunkSummary.unpackAll(ChunkSummary.packAll(List.of(s)));
            h.assertTrue(back.size() == 1 && back.get(0).topY[5] == s.topY[5], "summary didn't survive the network");
            CharMap.LOG.info("[ServerCheck] real save read fine: {} columns match the live world, summary packs to {} bytes, region order {}", checked, ChunkSummary.packAll(List.of(s)).length, FillOrder.parse(file.getFileName().toString()));
            h.succeed();
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }
}
