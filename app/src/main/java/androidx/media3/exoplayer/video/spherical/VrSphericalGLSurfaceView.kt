package androidx.media3.exoplayer.video.spherical

import androidx.media3.common.C
import com.dinsplayer.app.features.player.VrMode

/**
 * Helper extension untuk SphericalGLSurfaceView.
 * Karena SphericalGLSurfaceView adalah final, kita tidak bisa extend.
 * Gunakan extension function sebagai pengganti.
 */
fun SphericalGLSurfaceView.applyVrMode(mode: VrMode) {
    when (mode) {
        VrMode.NORMAL -> Unit
        VrMode.TOUCH -> {
            setUseSensorRotation(false)
            setDefaultStereoMode(C.STEREO_MODE_MONO)
        }
        VrMode.MOTION -> {
            setUseSensorRotation(true)
            setDefaultStereoMode(C.STEREO_MODE_MONO)
        }
        VrMode.VR_SPLIT -> {
            setUseSensorRotation(true)
            setDefaultStereoMode(C.STEREO_MODE_LEFT_RIGHT)
        }
    }
}
