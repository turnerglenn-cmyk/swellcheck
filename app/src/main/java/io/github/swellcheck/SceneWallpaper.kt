package io.github.swellcheck

import android.app.Activity
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import android.widget.Toast

/**
 * The live scene as a phone wallpaper. It animates only while the home screen
 * is showing, rebuilds the picture from the saved forecast every minute, and
 * fetches a fresh forecast in the background when the saved one is over
 * 30 minutes old. No extra permissions: it uses the place saved by the app.
 */
class SceneWallpaper : WallpaperService() {

    override fun onCreateEngine(): Engine = SceneEngine()

    inner class SceneEngine : Engine() {
        private val handler = Handler(Looper.getMainLooper())
        private val start = System.nanoTime()
        private var visible = false
        private var input: SceneInput? = null
        private var builtAt = 0L
        private var builtStamp = -1L
        @Volatile private var fetching = false

        private val frame = object : Runnable {
            override fun run() {
                drawFrame()
                if (visible) handler.postDelayed(this, FRAME_MS)
            }
        }

        override fun onVisibilityChanged(visible: Boolean) {
            this.visible = visible
            handler.removeCallbacks(frame)
            if (visible) {
                builtAt = 0L          // pick up any new forecast straight away
                handler.post(frame)
            }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            visible = false
            handler.removeCallbacks(frame)
            super.onSurfaceDestroyed(holder)
        }

        override fun onDestroy() {
            handler.removeCallbacks(frame)
            super.onDestroy()
        }

        private fun refreshInBackground() {
            if (fetching) return
            fetching = true
            val app = applicationContext
            Thread {
                val fresh = runCatching { Repo.refresh(app, force = false) }.getOrNull()
                if (fresh != null) runCatching { ConditionsWidget.updateAll(app) }
                fetching = false
                handler.post { builtAt = 0L }   // redraw from the new forecast
            }.start()
        }

        private fun drawFrame() {
            val nowMs = System.currentTimeMillis()
            val stamp = Prefs(applicationContext).settingsChanged
            if (nowMs - builtAt > REBUILD_MS || stamp != builtStamp) {
                builtStamp = stamp
                input = SceneInput.fromCache(applicationContext)
                builtAt = nowMs
                if (nowMs - Prefs(applicationContext).fetchedAt > STALE_MS) refreshInBackground()
            }
            val holder = surfaceHolder
            var c: Canvas? = null
            try {
                c = holder.lockCanvas() ?: return
                val d = resources.displayMetrics.density
                val s = input
                if (s == null) {
                    c.drawColor(0xFF0B1633.toInt())
                    c.drawText("Open SwellCheck once to choose a place", c.width / 2f, c.height / 2f,
                        Draw.paint(0xFFFFFFFF.toInt(), 15f * d, bold = true, align = Paint.Align.CENTER))
                } else {
                    val t = (System.nanoTime() - start) / 1e9f
                    SceneArt.drawTall(c, c.width.toFloat(), c.height.toFloat(), d, t, s)
                }
            } catch (e: Exception) {
                // Surface went away mid-frame; the next visibility change starts again.
            } finally {
                if (c != null) runCatching { holder.unlockCanvasAndPost(c) }
            }
        }
    }

    companion object {
        private const val FRAME_MS = 45L            // about 22 frames a second, only while visible
        private const val REBUILD_MS = 60_000L      // re-read the saved forecast every minute
        private const val STALE_MS = 30 * 60_000L   // fetch a new one when it's older than this
        const val REQ_SET = 4242

        fun isActive(ctx: Context): Boolean = runCatching {
            WallpaperManager.getInstance(ctx).wallpaperInfo?.packageName == ctx.packageName
        }.getOrDefault(false)

        /** Open Android's live wallpaper preview with the scene ready to set. */
        fun offer(activity: Activity) {
            val direct = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
                .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, ComponentName(activity, SceneWallpaper::class.java))
            try {
                activity.startActivityForResult(direct, REQ_SET)
            } catch (e: Exception) {
                try {
                    activity.startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
                    Toast.makeText(activity, "Choose \"SwellCheck live scene\" from the list", Toast.LENGTH_LONG).show()
                } catch (e2: Exception) {
                    Toast.makeText(activity, "This phone doesn't support live wallpapers", Toast.LENGTH_LONG).show()
                }
            }
        }

        /** After the wallpaper is set, step aside to the home screen so it can be seen. */
        fun onResult(activity: Activity, requestCode: Int, resultCode: Int) {
            if (requestCode == REQ_SET && resultCode == Activity.RESULT_OK) {
                activity.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }
}
