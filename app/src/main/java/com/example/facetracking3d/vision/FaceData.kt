package com.example.facetracking3d.vision

// Veri taşımak için özel 'data class' kullanıyoruz.
data class FaceData(
    val isVisible: Boolean = false,  // Kriz A: Yüz kaybolduğunda false olacak (Modeli gizle)
    val x: Float = 0f,               // Yüzün ekrandaki yatay merkez noktası
    val y: Float = 0f,               // Yüzün ekrandaki dikey merkez noktası
    val width: Float = 0f,           // 3D modelin büyüklüğünü (scale) ayarlamak için yüz genişliği
    val height: Float = 0f,          // Yüz yüksekliği
    val headEulerAngleX: Float = 0f, // Pitch (Kafayı aşağı/yukarı eğme)
    val headEulerAngleY: Float = 0f, // Yaw (Kafayı sağa/sola çevirme)
    val headEulerAngleZ: Float = 0f, // Roll (Kafayı omuza yatırma)
    val frameWidth: Int = 0,         // 2D-3D Dönüşüm Matematiği İçin
    val frameHeight: Int = 0         // 2D-3D Dönüşüm Matematiği İçin
)