package com.samusynth.questcamera.io

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

/** What the headset shows while [TwoCameraRecordingTest] records: the camera needs a visible app. */
class CameraGateActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = "QuestCameraLib camera gate: recording both cameras for 15 s" })
    }
}
