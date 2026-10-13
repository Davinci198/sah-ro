package com.sah

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import com.dragonbones.DragonBonesModel
import com.dragonbones.DragonBonesView

/**
 * Optional DragonBones host. The app remains fully usable without model assets.
 * A valid DragonBones export must provide all three files under assets/chess/.
 */
class DragonBonesOverlay(context: Context) : FrameLayout(context) {
    private var dragonBonesView: DragonBonesView? = null

    init {
        visibility = View.GONE
        contentDescription = "Animație DragonBones"
    }

    fun attachIfModelExists() {
        if (dragonBonesView != null || !hasModelAssets()) return

        val view = DragonBonesView(context)
        dragonBonesView = view
        addView(view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        visibility = View.VISIBLE

        val model = DragonBonesModel(
            skeletonPath = MODEL_SKELETON,
            textureJsonPath = MODEL_TEXTURE_JSON,
            textureImagePath = MODEL_TEXTURE_IMAGE,
        )
        view.loadModel(model) {
            view.getAnimationNames { names ->
                names.firstOrNull()?.let { animation ->
                    view.fadeInAnimation(animation, 0, 0, 0.2f)
                }
            }
        }
    }

    override fun onDetachedFromWindow() {
        dragonBonesView?.onPause()
        dragonBonesView?.destroy()
        dragonBonesView = null
        removeAllViews()
        super.onDetachedFromWindow()
    }

    private fun hasModelAssets(): Boolean = try {
        context.assets.open(MODEL_SKELETON).use { }
        context.assets.open(MODEL_TEXTURE_JSON).use { }
        context.assets.open(MODEL_TEXTURE_IMAGE).use { }
        true
    } catch (_: Exception) {
        false
    }

    private companion object {
        const val MODEL_SKELETON = "chess/piece_ske.json"
        const val MODEL_TEXTURE_JSON = "chess/piece_tex.json"
        const val MODEL_TEXTURE_IMAGE = "chess/piece_tex.png"
    }
}
