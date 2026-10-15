package gpulod;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.BlockState;
import net.minecraft.block.MapColor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.WorldChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.*;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Data LOD per dunia/dimensi, disimpan di .minecraft/gpulod/<dunia>/<dimensi>/r.X.Z.lod
 * (1 file = 32x32 chunk, gzip). Saat join data dimuat di thread latar lalu di-upload ke GPU.
 */
public final class LodStore {
    private static final Logger LOG = LoggerFactory.getLogger("gpulod");
    private static final int MAGIC = 0x4C4F4402;
    private static final int AUTOSAVE_TICKS = 1200; // 60 detik

    /** Antrean upload ke GPU (dibaca LodRenderer). */
    public final Long2ObjectLinkedOpenHashMap<ChunkLod> pending = new Long2ObjectLinkedOpenHashMap<>();
    public boolean reset;

    private record Loaded(int gen, ChunkLod lod) {}

    private final Long2ObjectOpenHashMap<Long2ObjectOpenHashMap<ChunkLod>> regions = new Long2ObjectOpenHashMap<>();
    private final LongOpenHashSet dirty = new LongOpenHashSet();
    private final ConcurrentLinkedQueue<Loaded> loaded = new ConcurrentLinkedQueue<>();
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "gpulod-io");
        t.setDaemon(true);
        return t;
    });
    private volatile int generation;
    private String currentKey;
    private Path dir;
    private int saveTimer;

    // ------------------------------------------------------------ dunia aktif

    private static String sanitize(String s) {
        return s.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private static String keyFor(MinecraftClient mc, ClientWorld w) {
        if (w == null) return null;
        String base;
        if (mc.getServer() != null) {
            base = "sp_" + mc.getServer().getSaveProperties().getLevelName();
        } else {
            ServerInfo si = mc.getCurrentServerEntry();
            base = "mp_" + (si != null ? si.address : "unknown");
        }
        return sanitize(base) + "/" + sanitize(w.getRegistryKey().getValue().toString());
    }

    /** Ganti dunia/dimensi: simpan yang lama, kosongkan, lalu muat yang baru. */
    private void sync(MinecraftClient mc, ClientWorld w) {
        String key = keyFor(mc, w);
        if (Objects.equals(key, currentKey)) return;

        flush(true);
        regions.clear();
        pending.clear();
        dirty.clear();
        loaded.clear();
        reset = true;
        generation++;
        currentKey = key;
        saveTimer = 0;

        if (key == null) { dir = null; return; }
        dir = FabricLoader.getInstance().getGameDir().resolve("gpulod").resolve(key);
        final Path d = dir;
        final int gen = generation;
        io.execute(() -> loadAll(d, gen));
    }

    // ------------------------------------------------------------ tangkap chunk

    public void capture(ClientWorld world, WorldChunk chunk) {
        sync(MinecraftClient.getInstance(), world);
        if (dir == null) return;

        ChunkPos p = chunk.getPos();
        ChunkLod lod = new ChunkLod(p.x, p.z);
        BlockPos.Mutable m = new BlockPos.Mutable();
        for (int i = 0; i < ChunkLod.COUNT; i++) {
            int lx = (i % ChunkLod.CELLS) * ChunkLod.CELL + ChunkLod.CELL / 2;
            int lz = (i / ChunkLod.CELLS) * ChunkLod.CELL + ChunkLod.CELL / 2;
            int y = chunk.sampleHeightmap(Heightmap.Type.WORLD_SURFACE, lx, lz) - 1;
            lod.height[i] = ChunkLod.EMPTY;
            if (y <= world.getBottomY()) continue;
            m.set(p.getStartX() + lx, y, p.getStartZ() + lz);
            BlockState s = chunk.getBlockState(m);
            MapColor mc = s.getMapColor(world, m);
            if (mc == null || mc.color == 0) continue;
            lod.height[i] = (short) (y + 1);
            lod.rgb[i] = mc.color;
        }
        put(lod, true);
    }

    /** Masukkan ke memori + antrean GPU; lewati bila sama persis dengan yang sudah ada. */
    private void put(ChunkLod lod, boolean markDirty) {
        long rk = ChunkPos.toLong(lod.x >> 5, lod.z >> 5);
        long ck = ChunkPos.toLong(lod.x, lod.z);
        Long2ObjectOpenHashMap<ChunkLod> region = regions.get(rk);
        if (region == null) {
            region = new Long2ObjectOpenHashMap<>();
            regions.put(rk, region);
        }
        ChunkLod old = region.get(ck);
        if (old != null && Arrays.equals(old.height, lod.height) && Arrays.equals(old.rgb, lod.rgb)) return;
        region.put(ck, lod);
        pending.put(ck, lod);
        if (markDirty) dirty.add(rk);
    }

    // ------------------------------------------------------------ tick

    public void tick(MinecraftClient mc) {
        sync(mc, mc.world);

        // serap data hasil muat dari disk (jangan timpa yang baru ditangkap live)
        int budget = 4000;
        Loaded l;
        while (budget-- > 0 && (l = loaded.poll()) != null) {
            if (l.gen() != generation) continue;
            ChunkLod lod = l.lod();
            long rk = ChunkPos.toLong(lod.x >> 5, lod.z >> 5);
            Long2ObjectOpenHashMap<ChunkLod> region = regions.get(rk);
            if (region != null && region.containsKey(ChunkPos.toLong(lod.x, lod.z))) continue;
            put(lod, false);
        }

        if (++saveTimer >= AUTOSAVE_TICKS) {
            saveTimer = 0;
            flush(true);
        }
    }

    // ------------------------------------------------------------ simpan

    /** Tulis region yang berubah. async=false dipakai saat game ditutup. */
    public void flush(boolean async) {
        if (dir == null || dirty.isEmpty()) return;
        final Path d = dir;
        for (long rk : dirty) {
            Long2ObjectOpenHashMap<ChunkLod> region = regions.get(rk);
            if (region == null || region.isEmpty()) continue;
            final byte[] bytes = serialize(region);
            final Path file = d.resolve("r." + ChunkPos.getPackedX(rk) + "." + ChunkPos.getPackedZ(rk) + ".lod");
            if (async) io.execute(() -> write(file, bytes));
            else write(file, bytes);
        }
        dirty.clear();
    }

    private static byte[] serialize(Long2ObjectOpenHashMap<ChunkLod> region) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(region.size() * 100 + 16);
            try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(bos))) {
                out.writeInt(MAGIC);
                out.writeInt(region.size());
                for (ChunkLod l : region.values()) {
                    out.writeInt(l.x);
                    out.writeInt(l.z);
                    for (int i = 0; i < ChunkLod.COUNT; i++) out.writeShort(l.height[i]);
                    for (int i = 0; i < ChunkLod.COUNT; i++) out.writeInt(l.rgb[i]);
                }
            }
            return bos.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void write(Path file, byte[] bytes) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(tmp, bytes);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            LOG.warn("Gagal menyimpan {}", file, e);
        }
    }

    // ------------------------------------------------------------ muat

    private void loadAll(Path d, int gen) {
        if (!Files.isDirectory(d)) return;
        try (Stream<Path> files = Files.list(d)) {
            for (Path f : (Iterable<Path>) files.filter(x -> x.toString().endsWith(".lod"))::iterator) {
                if (generation != gen) return;
                readRegion(f, gen);
            }
        } catch (IOException e) {
            LOG.warn("Gagal membaca {}", d, e);
        }
    }

    private void readRegion(Path f, int gen) {
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(new GZIPInputStream(new BufferedInputStream(Files.newInputStream(f)))))) {
            if (in.readInt() != MAGIC) return;
            int n = in.readInt();
            for (int c = 0; c < n; c++) {
                ChunkLod l = new ChunkLod(in.readInt(), in.readInt());
                for (int i = 0; i < ChunkLod.COUNT; i++) l.height[i] = in.readShort();
                for (int i = 0; i < ChunkLod.COUNT; i++) l.rgb[i] = in.readInt();
                loaded.add(new Loaded(gen, l));
            }
        } catch (IOException e) {
            LOG.warn("File LOD rusak, dilewati: {}", f, e);
        }
    }
}
