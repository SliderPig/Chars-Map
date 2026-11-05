package com.example.charmap.net;

import com.example.charmap.CharMap;
import com.example.charmap.model.ChunkSummary;
import java.io.IOException;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

// Only used to fill the map from a server that has this mod too. Either side can be missing it: then nothing here is ever sent.
public final class Net {
    private Net() {}

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(new ResourceLocation(CharMap.MODID, "main"), () -> "1", NetworkRegistry.acceptMissingOr("1"), NetworkRegistry.acceptMissingOr("1"));

    public static final int REPLY_REFUSED = 0, REPLY_STARTED = 1, REPLY_DONE = 2, REPLY_BUSY = 3;

    public record FillRequest(boolean stop, long since) {}

    public record FillReply(int code, int total, long when) {}

    public record FillChunks(byte[] packed, int done, int total, String dimension) {}

    public record VoxelAsk(long[] chunks) {}

    public record VoxelSend(byte[] blob, String dimension) {}

    public static void register() {
        int id = 0;
        CHANNEL.registerMessage(id++, FillRequest.class,
                (m, b) -> {
                    b.writeBoolean(m.stop());
                    b.writeLong(m.since());
                },
                b -> new FillRequest(b.readBoolean(), b.readLong()),
                Net::onRequest);
        CHANNEL.registerMessage(id++, FillReply.class,
                (m, b) -> {
                    b.writeByte(m.code());
                    b.writeInt(m.total());
                    b.writeLong(m.when());
                },
                b -> new FillReply(b.readByte(), b.readInt(), b.readLong()),
                Net::onReply);
        CHANNEL.registerMessage(id++, FillChunks.class,
                (m, b) -> {
                    b.writeByteArray(m.packed());
                    b.writeInt(m.done());
                    b.writeInt(m.total());
                    b.writeUtf(m.dimension());
                },
                b -> new FillChunks(readBytes(b), b.readInt(), b.readInt(), b.readUtf(256)),
                Net::onChunks);
        CHANNEL.registerMessage(id++, VoxelAsk.class,
                (m, b) -> b.writeLongArray(m.chunks()),
                b -> new VoxelAsk(b.readLongArray(new long[0], 256)),
                Net::onVoxelAsk);
        CHANNEL.registerMessage(id++, VoxelSend.class,
                (m, b) -> {
                    b.writeByteArray(m.blob());
                    b.writeUtf(m.dimension());
                },
                b -> new VoxelSend(readBytes(b), b.readUtf(256)),
                Net::onVoxelSend);
    }

    private static void onVoxelAsk(VoxelAsk m, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context c = ctx.get();
        ServerPlayer player = c.getSender();
        if (player != null) c.enqueueWork(() -> ServerFill.voxelAsk(player, m.chunks()));
        c.setPacketHandled(true);
    }

    private static void onVoxelSend(VoxelSend m, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context c = ctx.get();
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientSide.voxels(m));
        c.setPacketHandled(true);
    }

    private static byte[] readBytes(FriendlyByteBuf b) {
        return b.readByteArray(1 << 20);
    }

    private static void onRequest(FillRequest m, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context c = ctx.get();
        ServerPlayer player = c.getSender();
        if (player != null) c.enqueueWork(() -> ServerFill.request(player, m.stop(), m.since()));
        c.setPacketHandled(true);
    }

    private static void onReply(FillReply m, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context c = ctx.get();
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> c.enqueueWork(() -> ClientSide.reply(m)));
        c.setPacketHandled(true);
    }

    private static void onChunks(FillChunks m, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context c = ctx.get();
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientSide.chunks(m));
        c.setPacketHandled(true);
    }

    // Kept apart so a dedicated server never loads anything client-only.
    private static final class ClientSide {
        static void reply(FillReply m) {
            com.example.charmap.client.Filler.onServerReply(m.code(), m.total(), m.when());
        }

        static void voxels(VoxelSend m) {
            try {
                var session = com.example.charmap.client.MapClient.session;
                if (session == null || !session.dimension.equals(m.dimension())) return;
                java.io.DataInputStream in = new java.io.DataInputStream(new java.io.ByteArrayInputStream(m.blob()));
                int n = in.readUnsignedShort();
                for (int i = 0; i < n; i++) {
                    byte[] one = new byte[in.readInt()];
                    in.readFully(one);
                    session.voxels.receive(com.example.charmap.model.VoxelChunk.decode(one));
                }
            } catch (IOException | RuntimeException e) {
                CharMap.once("voxel-packet", "A block packet from the server couldn't be read", e);
            }
        }

        static void chunks(FillChunks m) {
            try {
                List<ChunkSummary> list = ChunkSummary.unpackAll(m.packed());
                com.example.charmap.client.Filler.onServerChunks(list, m.done(), m.total(), m.dimension());
            } catch (IOException | RuntimeException e) {
                CharMap.once("fill-packet", "A map packet from the server couldn't be read", e);
            }
        }
    }
}
