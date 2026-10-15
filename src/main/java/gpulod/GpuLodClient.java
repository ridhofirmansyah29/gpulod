package gpulod;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;

public class GpuLodClient implements ClientModInitializer {
    public static final LodStore STORE = new LodStore();
    private static final LodRenderer RENDERER = new LodRenderer(STORE);

    @Override
    public void onInitializeClient() {
        ClientChunkEvents.CHUNK_LOAD.register(STORE::capture);
        ClientTickEvents.END_CLIENT_TICK.register(STORE::tick);          // ganti dunia, muat disk, autosave
        ClientLifecycleEvents.CLIENT_STOPPING.register(c -> STORE.flush(false)); // simpan saat game ditutup
        WorldRenderEvents.LAST.register(RENDERER::render);
    }
}
