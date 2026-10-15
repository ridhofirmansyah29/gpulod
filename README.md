# GPU LOD v0.3 (Fabric 1.21.1)

Build (JDK 21):  gradle wrapper && ./gradlew build
Hasil: build/libs/gpulod-0.3.0.jar -> folder mods + Fabric API.

Pastikan Minecraft jalan di GPU dedicated:
Windows > Settings > System > Display > Graphics > tambah javaw.exe > High performance.

Cara kerja: data tiap chunk di-upload sekali ke VRAM (16 sel x 16 byte).
GPU yang mengembangkan sel jadi atap + dinding (instancing), melakukan culling
area vanilla/jarak, shading, dan fog. CPU tidak membangun mesh.

Penyimpanan: .minecraft/gpulod/<dunia>/<dimensi>/r.X.Z.lod (gzip, autosave 60 detik,
simpan saat keluar dunia/tutup game, dimuat otomatis saat join).

Belum ada: octree/LOD bertingkat, distant generation (sisi server),
fog vanilla yang disamakan.

## Dapat file .jar tanpa install apa-apa
Upload folder ini ke repo GitHub baru -> tab Actions -> workflow "build" jalan otomatis
-> buka run-nya -> download artifact "gpulod-jar" (isinya gpulod-0.3.0.jar).
