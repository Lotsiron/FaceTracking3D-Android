package com.example.facetracking3d.graphics

import android.content.Context
import androidx.lifecycle.LifecycleCoroutineScope
import io.github.sceneview.SceneView
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.node.ModelNode
import io.github.sceneview.node.Node
import com.example.facetracking3d.vision.FaceData
import kotlinx.coroutines.launch
import android.graphics.Color // YENİ EKLENDİ

class MaskRenderer(
    private val lifecycleScope: LifecycleCoroutineScope,
    private val sceneView: SceneView
) {
    private val faceNode = Node(sceneView.engine)

    private var maskNode: ModelNode? = null
    private var occluderNode: ModelNode? = null

    init {
        setupScene()
    }

    private fun setupScene() {
// 1. ANDROID KATMANI: SurfaceView'u saydam ve en üste ayarla
        sceneView.setZOrderOnTop(true)
        sceneView.holder.setFormat(android.graphics.PixelFormat.TRANSPARENT)
        sceneView.setBackgroundColor(android.graphics.Color.TRANSPARENT)

        // 2. FİLAMENT (C++) KATMANI: Motorun kendi siyah arkaplanını zorla yok et
        // Filament Scene'e ulaşıp siyah gökyüzü kutusunu siliyoruz
        sceneView.scene?.skybox = null
        // Render motorunun harmanlama modunu (Blend Mode) yarı saydama (Translucent) geçiriyoruz
        sceneView.view?.blendMode = com.google.android.filament.View.BlendMode.TRANSLUCENT

        // -- Kodun geri kalanı aynı şekilde devam edecek --
        sceneView.addChildNode(faceNode)
        faceNode.isVisible = false

        lifecycleScope.launch {
            try {
                val occluderInstance = sceneView.modelLoader.loadModelInstance("green_head.glb")
                if (occluderInstance != null) {
                    occluderNode = ModelNode(occluderInstance)
                    faceNode.addChildNode(occluderNode!!)
                }
            } catch (e: Exception) {
                android.util.Log.e("MaskRenderer", "Görünmez kafa bulunamadı.", e)
            }

            try {
                val maskInstance = sceneView.modelLoader.loadModelInstance("top_hat.glb")
                if (maskInstance != null) {
                    maskNode = ModelNode(maskInstance)
                    faceNode.addChildNode(maskNode!!)
                }
            } catch (e: Exception) {
                android.util.Log.e("MaskRenderer", "Maske bulunamadı.", e)
            }
        }
    }

    fun updateFace(faceData: FaceData) {
        if (!faceData.isVisible) {
            faceNode.isVisible = false
            return
        }

        faceNode.isVisible = true

        // 1. DÖNÜŞ AÇILARI
        faceNode.rotation = Rotation(
            x = faceData.headEulerAngleX,
            y = faceData.headEulerAngleY,
            z = faceData.headEulerAngleZ
        )

        faceNode.scale = io.github.sceneview.math.Scale(0.05f, 0.05f, 0.05f)

        // 2. POZİSYON MATEMATİĞİ (Genişliği de gönderiyoruz ki Z eksenini hesaplayalım)
        val worldPosition = convertPixelsToWorldSpace(
            faceX = faceData.x,
            faceY = faceData.y,
            faceWidth = faceData.width, // YENİ: Yüzün genişliği
            frameWidth = faceData.frameWidth,
            frameHeight = faceData.frameHeight
        )
        faceNode.position = worldPosition
    }

    private fun convertPixelsToWorldSpace(faceX: Float, faceY: Float, faceWidth: Float, frameWidth: Int, frameHeight: Int): Position {

        // 1. AYNA ETKİSİNİ TERSİNE ÇEVİRME (X-Axis Fix)
        // Selfie kamerası ayna gibidir. Ekranda sola gitmiş gibi görünsek de pikseller sağa kayar.
        // Bu yüzden 1.0f'den çıkararak X eksenini ters çeviriyoruz.
        val percentX = 1.0f - (faceX / frameWidth.toFloat())
        val percentY = faceY / frameHeight.toFloat()

        val normalizedX = (percentX * 2f) - 1f
        val normalizedY = -((percentY * 2f) - 1f)

        // 2. GERÇEK DERİNLİK MATEMATİĞİ (Z-Axis Fix)
        // Yüzün ekranda kapladığı oran. (Örn: Yüz çok küçükse bu değer 0.1, büyükse 0.5 olur)
        val faceRatio = faceWidth / frameWidth.toFloat()

        // Oran küçüldükçe (biz uzaklaştıkça), zDepth daha büyük bir eksi değer olur ve şapka geriye itilir.
        // "0.3f" bir kalibrasyon katsayısıdır. Eğer şapka çok uzak gelirse bu değeri 0.15f yapabilir, çok yakınsa 0.6f yapabilirsin.
        val zDepth = -(0.3f / faceRatio)

        // 3. FOV AYARI
        val fovScaleX = 0.4f
        val fovScaleY = fovScaleX * (frameHeight.toFloat() / frameWidth.toFloat())

        return Position(
            x = normalizedX * fovScaleX,
            y = normalizedY * fovScaleY,
            z = zDepth
        )
    }
}
