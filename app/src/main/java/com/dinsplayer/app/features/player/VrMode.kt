package com.dinsplayer.app.features.player

/**
 * 4 Mode pemutaran video / streaming pada AOX Player:
 * 1. NORMAL: Video 2D standar pada PlayerView biasa.
 * 2. TOUCH: 360° interaktif dengan usapan jari (Touch Drag).
 * 3. MOTION: 360° otomatis mengikuti sensor giroskop/gerakan HP.
 * 4. VR_SPLIT: Mode Split-Screen stereoskopis (dua mata) untuk headset kacamata VR / Cardboard.
 */
enum class VrMode(
    val title: String,
    val subtitle: String,
    val description: String
) {
    NORMAL(
        title = "Normal (2D)",
        subtitle = "Tampilan Standar",
        description = "Mode datar standar dengan kontrol rasio aspek Fit/Fill/Zoom."
    ),
    TOUCH(
        title = "360° Sentuh",
        subtitle = "Navigasi Manual Jari",
        description = "Geser jari di layar untuk menjelajahi sudut pandang 360° secara manual."
    ),
    MOTION(
        title = "360° Sensor",
        subtitle = "Head Tracking Giroskop",
        description = "Sudut pandang bergerak otomatis mengikuti orientasi dan arah gerakan ponsel Anda."
    ),
    VR_SPLIT(
        title = "VR Split-Screen",
        subtitle = "Kacamata VR / Cardboard",
        description = "Layar terbagi dua (kiri & kanan) dengan pelacakan sensor untuk kacamata VR Box."
    )
}
