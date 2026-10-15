package gpulod;

import com.mojang.blaze3d.systems.RenderSystem;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.util.Window;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL33;
import org.lwjgl.system.MemoryStack;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.Iterator;

/**
 * Renderer LOD berbasis GPU instancing.
 *
 * CPU hanya meng-upload 16 sel x 16 byte per chunk (sekali). Semua kerja berat
 * dilakukan GPU: ekspansi tiap sel menjadi atap + 4 dinding (30 vertex),
 * culling area vanilla & jarak, shading, dan fog. Tidak ada rebuild mesh di CPU.
 */
public final class LodRenderer {
    private static final int CELL_BYTES = 16;                       // ivec4 per sel
    private static final int SLOT_BYTES = ChunkLod.COUNT * CELL_BYTES;
    private static final int VERTS_PER_CELL = 30;                   // 6 atap + 4*6 dinding
    private static final int UPLOAD_BUDGET = 128;                   // chunk per frame
    private static final float RANGE = 6144f;                       // jarak LOD maks (blok)
    private static final float SKIRT = 12f;                         // tinggi dinding penutup retakan

    private static final String VS = """
        #version 330 core
        layout(location=0) in ivec4 inst;      // x, y, z, (rgb | valid<<24)
        uniform mat4 uMvp;
        uniform ivec3 uCamI;
        uniform vec3 uCamF;
        uniform vec2 uCenter;
        uniform float uInner;
        uniform float uRange;
        uniform float uSkirt;
        out vec3 vCol;
        out float vDist;

        const vec2 Q[6] = vec2[6](vec2(0,0), vec2(0,1), vec2(1,0), vec2(1,0), vec2(0,1), vec2(1,1));

        void main() {
            vCol = vec3(0.0);
            vDist = 0.0;
            vec2 c = vec2(inst.x, inst.z) + 2.0;
            vec2 d = abs(c - uCenter);
            bool valid = ((inst.w >> 24) & 1) != 0;
            bool insideVanilla = max(d.x, d.y) < uInner;
            vec2 rc = c - (vec2(uCamI.xz) + uCamF.xz);
            if (!valid || insideVanilla || dot(rc, rc) > uRange * uRange) {
                gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
                return;
            }

            int id = gl_VertexID;
            vec3 off;
            float shade = 1.0;
            if (id < 6) {
                off = vec3(Q[id].x * 4.0, 0.0, Q[id].y * 4.0);
            } else {
                int s = (id - 6) / 6;
                int k = (id - 6) % 6;
                float u = Q[k].x * 4.0;
                float y = (Q[k].y - 1.0) * uSkirt;
                if (s == 0)      off = vec3(u, y, 0.0);
                else if (s == 1) off = vec3(u, y, 4.0);
                else if (s == 2) off = vec3(0.0, y, u);
                else             off = vec3(4.0, y, u);
                shade = (s < 2) ? 0.78 : 0.68;
            }

            vec3 rel = vec3(ivec3(inst.x, inst.y, inst.z) - uCamI) - uCamF + off;
            gl_Position = uMvp * vec4(rel, 1.0);

            int rgb = inst.w & 0xFFFFFF;
            vCol = vec3(float((rgb >> 16) & 255), float((rgb >> 8) & 255), float(rgb & 255)) / 255.0 * shade;
            vDist = length(rel);
        }
        """;

    private static final String FS = """
        #version 330 core
        in vec3 vCol;
        in float vDist;
        uniform vec3 uFogColor;
        uniform float uRange;
        out vec4 fragColor;
        void main() {
            float f = smoothstep(uRange * 0.55, uRange, vDist);
            fragColor = vec4(mix(vCol, uFogColor, f), 1.0);
        }
        """;

    private final LodStore store;
    private final Long2IntOpenHashMap slotOf = new Long2IntOpenHashMap();
    private boolean ready;
    private int prog, vao, vbo, capacitySlots, usedSlots;
    private int uMvp, uCamI, uCamF, uCenter, uInner, uRange, uSkirt, uFogColor;

    public LodRenderer(LodStore store) {
        this.store = store;
        slotOf.defaultReturnValue(-1);
    }

    // ---------------------------------------------------------------- init

    private void init() {
        prog = GL20.glCreateProgram();
        int vs = compile(GL20.GL_VERTEX_SHADER, VS);
        int fs = compile(GL20.GL_FRAGMENT_SHADER, FS);
        GL20.glAttachShader(prog, vs);
        GL20.glAttachShader(prog, fs);
        GL20.glLinkProgram(prog);
        if (GL20.glGetProgrami(prog, GL20.GL_LINK_STATUS) == 0)
            throw new IllegalStateException(GL20.glGetProgramInfoLog(prog));
        GL20.glDeleteShader(vs);
        GL20.glDeleteShader(fs);

        uMvp = GL20.glGetUniformLocation(prog, "uMvp");
        uCamI = GL20.glGetUniformLocation(prog, "uCamI");
        uCamF = GL20.glGetUniformLocation(prog, "uCamF");
        uCenter = GL20.glGetUniformLocation(prog, "uCenter");
        uInner = GL20.glGetUniformLocation(prog, "uInner");
        uRange = GL20.glGetUniformLocation(prog, "uRange");
        uSkirt = GL20.glGetUniformLocation(prog, "uSkirt");
        uFogColor = GL20.glGetUniformLocation(prog, "uFogColor");

        vao = GL30.glGenVertexArrays();
        growTo(4096);
        ready = true;
    }

    private static int compile(int type, String src) {
        int s = GL20.glCreateShader(type);
        GL20.glShaderSource(s, src);
        GL20.glCompileShader(s);
        if (GL20.glGetShaderi(s, GL20.GL_COMPILE_STATUS) == 0)
            throw new IllegalStateException(GL20.glGetShaderInfoLog(s));
        return s;
    }

    /** Perbesar buffer VRAM; data lama disalin langsung di GPU. */
    private void growTo(int slots) {
        if (slots <= capacitySlots) return;
        int newCap = Math.max(capacitySlots * 2, slots);
        int nb = GL15.glGenBuffers();
        GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, nb);
        GL15.glBufferData(GL31.GL_COPY_WRITE_BUFFER, (long) newCap * SLOT_BYTES, GL15.GL_DYNAMIC_DRAW);
        if (vbo != 0) {
            if (usedSlots > 0) {
                GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER, vbo);
                GL31.glCopyBufferSubData(GL31.GL_COPY_READ_BUFFER, GL31.GL_COPY_WRITE_BUFFER,
                        0L, 0L, (long) usedSlots * SLOT_BYTES);
                GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER, 0);
            }
            GL15.glDeleteBuffers(vbo);
        }
        GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, 0);
        vbo = nb;
        capacitySlots = newCap;

        GL30.glBindVertexArray(vao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        GL20.glEnableVertexAttribArray(0);
        GL30.glVertexAttribIPointer(0, 4, GL11.GL_INT, CELL_BYTES, 0L);
        GL33.glVertexAttribDivisor(0, 1);
        GL30.glBindVertexArray(0);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
    }

    // -------------------------------------------------------------- upload

    private void upload() {
        if (store.reset) {
            usedSlots = 0;
            slotOf.clear();
            store.reset = false;
        }
        if (store.pending.isEmpty()) return;

        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0); // pastikan tidak mengganggu state lain
        int budget = UPLOAD_BUDGET;
        try (MemoryStack st = MemoryStack.stackPush()) {
            IntBuffer ib = st.mallocInt(ChunkLod.COUNT * 4);
            Iterator<Long2ObjectMap.Entry<ChunkLod>> it = store.pending.long2ObjectEntrySet().fastIterator();
            while (it.hasNext() && budget-- > 0) {
                Long2ObjectMap.Entry<ChunkLod> e = it.next();
                long key = e.getLongKey();
                ChunkLod l = e.getValue();
                it.remove();

                int slot = slotOf.get(key);
                if (slot < 0) {
                    growTo(usedSlots + 1);
                    slot = usedSlots++;
                    slotOf.put(key, slot);
                }

                ib.clear();
                for (int i = 0; i < ChunkLod.COUNT; i++) {
                    boolean valid = l.height[i] != ChunkLod.EMPTY;
                    ib.put(l.x * 16 + (i % ChunkLod.CELLS) * ChunkLod.CELL);
                    ib.put(valid ? l.height[i] : 0);
                    ib.put(l.z * 16 + (i / ChunkLod.CELLS) * ChunkLod.CELL);
                    ib.put((l.rgb[i] & 0xFFFFFF) | (valid ? (1 << 24) : 0));
                }
                ib.flip();
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
                GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, (long) slot * SLOT_BYTES, ib);
            }
        }
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
    }

    // -------------------------------------------------------------- render

    public void render(WorldRenderContext ctx) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null || mc.player == null) return;
        if (!ready) init();
        upload();
        if (usedSlots == 0) return;

        Window w = mc.getWindow();
        float fov = (float) Math.toRadians(mc.options.getFov().getValue());
        float aspect = (float) w.getFramebufferWidth() / w.getFramebufferHeight();
        // near sama dgn vanilla agar depth konsisten; far dibuat jauh
        Matrix4f mvp = new Matrix4f().perspective(fov, aspect, 0.05f, 16384f).mul(ctx.positionMatrix());

        Vec3d cam = ctx.camera().getPos();
        int cx = (int) Math.floor(cam.x), cy = (int) Math.floor(cam.y), cz = (int) Math.floor(cam.z);
        ChunkPos pc = mc.player.getChunkPos();
        float inner = mc.options.getClampedViewDistance() * 16f + 8f;
        float[] fog = RenderSystem.getShaderFogColor();

        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
        RenderSystem.disableCull();

        GL20.glUseProgram(prog);
        try (MemoryStack st = MemoryStack.stackPush()) {
            FloatBuffer fb = st.mallocFloat(16);
            mvp.get(fb);
            GL20.glUniformMatrix4fv(uMvp, false, fb);
        }
        GL20.glUniform3i(uCamI, cx, cy, cz);
        GL20.glUniform3f(uCamF, (float) (cam.x - cx), (float) (cam.y - cy), (float) (cam.z - cz));
        GL20.glUniform2f(uCenter, pc.x * 16f + 8f, pc.z * 16f + 8f);
        GL20.glUniform1f(uInner, inner);
        GL20.glUniform1f(uRange, RANGE);
        GL20.glUniform1f(uSkirt, SKIRT);
        GL20.glUniform3f(uFogColor, fog[0], fog[1], fog[2]);

        GL30.glBindVertexArray(vao);
        GL31.glDrawArraysInstanced(GL11.GL_TRIANGLES, 0, VERTS_PER_CELL, usedSlots * ChunkLod.COUNT);
        GL30.glBindVertexArray(0);
        GL20.glUseProgram(0);

        RenderSystem.enableCull();
        BufferRenderer.reset();
    }
}
