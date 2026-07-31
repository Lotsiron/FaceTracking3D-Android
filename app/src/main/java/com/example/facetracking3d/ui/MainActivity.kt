package com.example.facetracking3d.ui

import android.Manifest
import android.content.pm.PackageManager
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.example.facetracking3d.R
import com.example.facetracking3d.camera.CameraEngine
import com.example.facetracking3d.graphics.MaskRenderer
import io.github.sceneview.SceneView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.RangeSlider
import android.transition.TransitionManager
import android.view.ViewGroup
import android.widget.ImageButton
class MainActivity : AppCompatActivity() {

    private val CAMERA_REQUEST_CODE = 1001
    private lateinit var cameraEngine: CameraEngine
    private lateinit var maskRenderer: MaskRenderer
    private val enableFaceTracking = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Tells Android to draw our app behind the system navigation and status bars (full screen)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        // Applies padding so UI elements don't get hidden behind the phone's camera notch or gesture bar
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.mainRoot)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // SceneView is a modern 3D rendering library based on Google's Filament engine.
        // We use it to render the 3D Objects
        val sceneView = findViewById<SceneView>(R.id.sceneView)
        val glSurfaceView = findViewById<GLSurfaceView>(R.id.glSurfaceView)
        maskRenderer = MaskRenderer(lifecycleScope, sceneView)
        sceneView.visibility = if (enableFaceTracking) View.VISIBLE else View.GONE

        // Initialize the Core Engine with all the dependencies it needs
        cameraEngine = CameraEngine(
            activity = this,
            glSurfaceView = glSurfaceView,
            maskRenderer = maskRenderer,
            useYuvPipeline = true,
            enableGreenScreen = true,
            enableFaceTracking = true
        )

        setupControlPanel()

        // Standard Android runtime permission check. If we have it, start the camera.
        // If not, trigger the popup asking the user for access.
        if (allPermissionsGranted()) {
            cameraEngine.start()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_REQUEST_CODE)
        }
    }

    private fun setupControlPanel() {
        // Find our new UI elements
        val mainRoot = findViewById<ViewGroup>(R.id.mainRoot)
        val controlPanelCard = findViewById<View>(R.id.controlPanelCard)
        val btnSettings = findViewById<ImageButton>(R.id.btnSettings)

        val switchGreenScreen = findViewById<MaterialSwitch>(R.id.switchGreenScreen)
        val switchMirror = findViewById<MaterialSwitch>(R.id.switchMirror)
        val switchFaceTracking = findViewById<MaterialSwitch>(R.id.switchFaceTracking)
        val sliderSensitivity = findViewById<RangeSlider>(R.id.sliderSensitivity)

        // The Collapse/Expand Logic
        btnSettings.setOnClickListener {
            // This single line tells Android to automatically animate any layout changes that happen next!
            TransitionManager.beginDelayedTransition(mainRoot)

            if (controlPanelCard.visibility == View.VISIBLE) {
                controlPanelCard.visibility = View.GONE
            } else {
                controlPanelCard.visibility = View.VISIBLE
            }
        }

        switchGreenScreen.setOnCheckedChangeListener { _, isChecked ->
            cameraEngine.setGreenScreenEnabled(isChecked)
        }

        switchMirror.setOnCheckedChangeListener { _, isChecked ->
            cameraEngine.setMirroringEnabled(isChecked)
        }

        switchFaceTracking.setOnCheckedChangeListener { _, isChecked ->
            cameraEngine.setFaceTrackingEnabled(isChecked)
        }

        sliderSensitivity.addOnChangeListener { slider, _, _ ->
            val min = slider.values[0]
            val max = slider.values[1]
            cameraEngine.setMaskSensitivity(min, max)
        }
    }

    private fun allPermissionsGranted() = ContextCompat.checkSelfPermission(
        baseContext, Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED

    // Callback that fires when the user taps "Allow" or "Deny" on the permission popup
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_REQUEST_CODE && allPermissionsGranted()) {
            cameraEngine.start()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Always clean up the background threads so we don't cause a memory leak when the app closes
        cameraEngine.shutdown()
    }
}