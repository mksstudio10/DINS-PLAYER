# DINS Player

Aplikasi pemutar video dan media streaming berbasis Android yang dibangun menggunakan AndroidX Media3 (ExoPlayer) dan Jetpack Compose. Mendukung pemutaran file video lokal serta berbagai protokol streaming jaringan (IPTV, HLS, DASH, Xtream Codes) dengan dukungan kustomisasi header HTTP, pengelolaan DRM, dan kompatibilitas antarmuka untuk perangkat seluler maupun Android TV.

---

## Fitur Utama

### 1. Engine Pemutar Video (Media3 / ExoPlayer)
- Pemutaran stream adaptif berbasis protokol HLS (`.m3u8`), DASH (`.mpd`), dan Progressive HTTP/HTTPS (`.mp4`, `.mkv`, `.ts`).
- Ekstensi dekoder audio tambahan berbasis FFmpeg untuk mendukung format audio lawas seperti MP2, MP3, dan AC3 pada siaran TS/IPTV tanpa kendala "no audio".
- Dukungan latar belakang (*background playback*) menggunakan `MediaSession` dan `PlayerService`.
- Pengaturan sinkronisasi offset audio (*audio delay / sync*).

### 2. Manajemen Lisensi DRM
- Dukungan skema proteksi konten DRM ClearKey dan Widevine.
- Parsing dan injeksi otomatis parameter DRM dari metadata `#KODIPROP` pada format playlist M3U.
- Dukungan masukan pasangan `KID:KEY` langsung melalui antarmuka uji coba stream.

### 3. Pengujian Stream & Kustomisasi Header HTTP
- Formulir pengujian langsung (*Test Play*) untuk URL stream tunggal.
- Kustomisasi header HTTP lengkap sebelum koneksi dibuka:
  - User-Agent (Preset: VLC, TiviMate, Default, atau Custom).
  - Referer dan Origin.
  - X-Forwarded-For (spoofing IP klien).
  - Header kustom tambahan melalui format pasangan *key-value*.
- Penanganan fallback DNS mandiri (`FallbackDns`) untuk mengatasi pemblokiran domain atau kegagalan resolusi operator.

### 4. Manajemen Playlist & EPG
- Parser M3U / M3U8 berbasis *asynchronous coroutines* untuk memproses ribuan daftar saluran tanpa membebani thread antarmuka (UI).
- Integrasi Xtream Codes API (Live Streams, VOD, dan Series).
- Dukungan Electronic Program Guide (EPG) berbasis format XMLTV terkompresi (`.xml`, `.xml.gz`).
- Penyimpanan lokal data playlist dan riwayat stream menggunakan Room Database.

### 5. Galeri & Pemutar Video Lokal
- Pemindaian otomatis file video pada penyimpanan internal perangkat (`.mp4`, `.mkv`, `.avi`, `.mov`, dll.).
- Pengelompokan video berdasarkan folder penyimpanan (*Camera*, *Download*, *Movies*, *WhatsApp Video*).
- Menampilkan metadata ukuran file, durasi, dan resolusi video.

### 6. Dukungan Format Khusus & Mode VR
- Mode tampilan bola 360 derajat (*VR Spherical GL Surface View*) untuk konten video immersive 360 / panorama.
- Kontrol orientasi sensor dan sentuh untuk navigasi sudut pandang kamera VR.

### 7. Antarmuka Ganda (Mobile & Android TV)
- Antarmuka Mobile: Desain berbasis Jetpack Compose dengan navigasi tab modern (*Beranda*, *Lokal*, *Live TV*, *Playlist*, *Setelan*).
- Antarmuka Android TV: Navigasi teroptimasi untuk kendali remote D-Pad, kartu saluran berfokus tinggi, dan dialog konfigurasi khusus layar lebar.

---

## Teknologi yang Digunakan

| Komponen | Pustaka / Teknologi | Keterangan |
| :--- | :--- | :--- |
| Bahasa | Kotlin | Versi target JVM 11 |
| UI Framework | Jetpack Compose & Material 3 | Komponen UI deklaratif |
| Video Engine | AndroidX Media3 (ExoPlayer) | Modul core, HLS, DASH, UI, dan Session |
| Audio Codec | NextLib Media3 Extension (FFmpeg) | Penanganan audio MP2/AC3 stream |
| Jaringan | OkHttp 4 | Client HTTP dengan kustomisasi interceptor |
| Database | Room Database + KSP | Penyimpanan lokal relasional |
| State Management | Kotlin Coroutines & StateFlow | Alur data satu arah (UDF) |
| Penyimpanan Preferensi | Jetpack DataStore | Pengaturan aplikasi dan cache konfigurasi |
| Image Loading | Coil (Compose & Video Frame) | Pemuatan thumbnail gambar dan video |

---

## Struktur Direktori

```text
DINS-PLAYER/
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/com/dinsplayer/app/
│   │   │   │   ├── core/
│   │   │   │   │   ├── cast/         # Konfigurasi Google Cast framework
│   │   │   │   │   ├── epg/          # Parser dan pengelola data EPG XMLTV
│   │   │   │   │   ├── network/      # HTTP Client, M3U parser, Xtream parser, Fallback DNS
│   │   │   │   │   └── player/       # Media3Manager, PlayerService, DRM, Audio Processor
│   │   │   │   ├── data/
│   │   │   │   │   ├── db/           # Room Database, DAO, dan Entitas
│   │   │   │   │   └── models/       # Data class (Channel, Playlist, StreamItem)
│   │   │   │   ├── features/
│   │   │   │   │   ├── beranda/      # Layar utama dan formulir uji stream
│   │   │   │   │   ├── livetv/       # Daftar saluran TV dan kategori
│   │   │   │   │   ├── lokal/        # Penjelajah video lokal perangkat
│   │   │   │   │   ├── mobile/       # Layout navigasi mobile
│   │   │   │   │   ├── player/       # Activity pemutar, overlay kontrol, dan mode VR
│   │   │   │   │   ├── playlist/     # Manajemen dan penambahan playlist
│   │   │   │   │   └── setelan/      # Konfigurasi aplikasi dan decoder
│   │   │   │   └── ui/theme/         # Definisi warna, tipografi, dan tema aplikasi
│   │   │   └── res/                  # Aset layout, drawable, nilai string, dan tema
│   │   └── test/                     # Unit test (EpgTest, NavLayoutTest, AudioMathTest)
│   └── build.gradle.kts
├── gradle/                           # Gradle wrapper dan version catalog (libs.versions.toml)
├── build.gradle.kts
└── settings.gradle.kts
```

---

## Kebutuhan Sistem

- **Android Studio**: Android Studio Koala Feature Drop / Ladybug atau versi lebih baru.
- **JDK**: Java Development Kit 17 atau lebih baru.
- **Minimum SDK**: Android 8.0 (API Level 26).
- **Target / Compile SDK**: Android 15 / 16 (API Level 36).

---

## Panduan Instalasi & Build

### 1. Kloning Repository
```bash
git clone https://github.com/mksstudio10/DINS-PLAYER.git
cd DINS-PLAYER
```

### 2. Membuka di Android Studio
1. Buka Android Studio.
2. Pilih menu **Open** dan arahkan ke folder direktori `DINS-PLAYER`.
3. Tunggu hingga proses **Gradle Sync** selesai secara sempurna.

### 3. Kompilasi via Baris Perintah (CLI)
Untuk melakukan build APK debug:
```bash
# Windows
.\gradlew assembleDebug

# Linux / macOS
./gradlew assembleDebug
```
Berkas APK hasil kompilasi akan berada pada direktori:
`app/build/outputs/apk/debug/app-debug.apk`

---

## Catatan Tambahan

- **Keamanan Jaringan**: Aplikasi mendukung `CleartextTraffic` untuk kompatibilitas stream lokal berbasis HTTP, namun disarankan menggunakan protokol HTTPS untuk keamanan transmisi data.
- **Lisensi Konten**: Aplikasi ini berfungsi murni sebagai pemutar media (*media player*). Pengguna bertanggung jawab penuh atas URL stream, berkas playlist, dan hak cipta konten yang dimasukkan ke dalam aplikasi.

---

## Lisensi

Proyek ini didistribusikan di bawah lisensi **GNU General Public License v3.0 (GPL-3.0)** sejalan dengan ketergantungan ekstensi audio dekoder FFmpeg (`nextlib-media3ext`). Lihat berkas [LICENSE](LICENSE) untuk ketentuan lisensi lengkap.

