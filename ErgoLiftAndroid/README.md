> **No Android Studio?** Start with [CLOUD_BUILD_START_HERE.md](CLOUD_BUILD_START_HERE.md). A GitHub Actions workflow is included to build and download the APK through your browser.

# ErgoLift — Android prototype

This project ports the earlier **Python/OpenCV + MediaPipe** lifting-posture demonstration to **native Android (Kotlin)**. It uses the phone camera and runs MediaPipe Pose Landmarker on the device.

## First installation — Windows + Android phone

1. Install **Android Studio** from https://developer.android.com/studio. Complete its Android SDK setup.
2. Extract `ErgoLiftAndroid.zip` into a folder on your PC.
3. Copy your existing `pose_landmarker_lite.task` file from `Desktop\Body Joints` to:
   `ErgoLiftAndroid\app\src\main\assets\pose_landmarker_lite.task`
   If you no longer have it, download the official model:
   https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_lite/float16/1/pose_landmarker_lite.task
4. In **Android Studio**, select **File > Open** and choose the extracted `ErgoLiftAndroid` folder. Allow Gradle to sync and download dependencies (internet needed for initial setup).
5. Connect your Android phone over USB. Enable **Developer options** by tapping **Build number** seven times in phone settings, then enable **USB debugging** (exact menu names depend on phone).
6. Select the connected phone in Android Studio and click **Run ▶**. Allow the Camera permission on the phone.

This archive is a **source project**, not a compiled APK. It is intended to be built inside Android Studio. The Gradle wrapper *properties* are included, but the standalone `gradlew` command-line launcher is not. Android Studio can generally import and sync via its Gradle integration; if it requests a full Gradle wrapper, create a normal **Empty Views Activity** Android Studio project with package `com.example.ergolift`, then copy `app/src/main` and the provided Gradle build files into it (retaining the new project's Gradle wrapper files).

## What the prototype does

- Uses the back camera by default. **Flip camera** switches to the front camera.
- Displays camera frames with a full-frame **FIT_CENTER** view (no fill-and-crop zoom).
- Draws shoulder/arm/torso skeleton and head-to-neck line (side view when possible).
- Estimates image-plane trunk inclination, approximate neck alignment, and elbow angles.
- Tap **Calibrate neck** when upright and viewed from the side to establish a neck reference.
- Estimates **UPRIGHT**, **BENDING**, **REACHING** and **RISING/POSSIBLE LIFT** phases.
- Displays `GOOD POSTURE (DEMO)` or `BAD POSTURE (DEMO)` only when a sufficiently side-on body is detected.

## Limitations and responsible use

- **Not a validated ergonomic assessment**. The 45° trunk and 25° calibrated neck-change thresholds are *illustrative demo rules*, not safety limits.
- The phone does **not** detect load weight, grip, lifting height, vertical/horizontal distance, repetition, rotation, or object contact. A `GOOD POSTURE` label does not mean the lifting operation is safe.
- 2D angles can be biased by viewpoint, foreshortening, body occlusion and landmark detection uncertainty. Keep the phone at approximately hip-to-chest height, stable and perpendicular to the direction of bending, with shoulders and hips in frame.
- No recording, account, or internet upload is implemented. Camera frames and pose processing remain on-device. The first Android Studio dependency download needs internet, but the installed demo does not.
- Processing rates will vary by phone. The MVP uses a synchronous `VIDEO` inference loop on a worker thread for simplicity.
- The source code has been reviewed, but **could not be compiled or run on an Android phone here** because this environment has no Android SDK or physical device. Android Studio may surface device/version-specific build or permission issues that need adjustment.

## Code structure

- `app/src/main/java/com/example/ergolift/MainActivity.kt` — camera pipeline, MediaPipe, skeleton drawing, measurements and phase/posture logic
- `app/src/main/res/layout/activity_main.xml` — app screen
- `app/src/main/AndroidManifest.xml` — camera permission and launch activity
- `app/build.gradle.kts` — dependency configuration
- `app/src/main/assets/` — location for your `.task` model

## Troubleshooting

- **MODEL NOT FOUND:** Put the model file into `app/src/main/assets/` and *rebuild* (not beside MainActivity).
- **CAMERA UNAVAILABLE:** Tap Flip camera, verify phone permission, restart. Some phones may require a different camera configuration.
- **NOT ASSESSED:** Ensure shoulders and hips are visible. For bending assessment, position the phone to capture a side view.
- **Neck has `--`:** Show the visible ear/shoulder/hip from the side and press **Calibrate neck** when upright. A drawn front-view neck line is only visual and is not reliable neck flexion data.
- **Bad posture:** Demonstration limit exceeded; not a medically or occupationally validated determination.
- **No Gradle wrapper for command line:** Open and run through Android Studio, or use the full wrapper provided when creating a new Android Studio project.
