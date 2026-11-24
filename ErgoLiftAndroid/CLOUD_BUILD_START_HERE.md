# ErgoLift: build the APK without Android Studio

You do **not** need Android Studio or the Android SDK on your computer. GitHub Actions can compile the APK on a cloud computer using the `.github/workflows/build-apk.yml` included in this project.

## Upload the project using a browser

1. Create a free GitHub account or sign in at https://github.com.
2. Visit https://github.com/new and create a repository named `ErgoLiftAndroid` (private is fine; keep GitHub Actions enabled).
3. Extract `ErgoLiftAndroid_Cloud_Build.zip` to a normal folder on your PC.
4. Open the extracted `ErgoLiftAndroid` folder. **Select everything INSIDE it** (including the `.github` directory and `app` directory), then drag these selected items into the GitHub repository's **Add file > Upload files** page. **Do not upload the ZIP file as a single file.** If you upload the parent `ErgoLiftAndroid` folder instead of its contents, the build workflow will be nested and GitHub will not see it.
5. Click **Commit changes**. GitHub Actions should automatically begin the APK build. If it does not, open the **Actions** tab, select **Build ErgoLift APK**, and use **Run workflow**.
6. After the workflow is completed successfully (green check mark), open **Actions > Build ErgoLift APK > the latest successful run**. Under **Artifacts**, download `ErgoLift-Android-APK`.
7. Extract the downloaded artifact ZIP. It contains `app-debug.apk`. Transfer this APK to your Android phone and open it to install (allow "Install unknown apps" for the app that opens the APK when prompted).
8. Open **ErgoLift** on your phone and allow camera access. Use a stable side-view phone position with head, shoulders, and hips visible.

The workflow downloads the official MediaPipe pose-landmarker-lite model during the cloud build. You **do not** need to upload the `.task` model yourself. Network access is needed by the GitHub runner to fetch the model and Android dependencies.

## Important notes

- The GitHub build has **not yet been run** or physically tested on a phone. If it fails, read the red error message under **Actions > the failed run > android** and share it for troubleshooting.
- This produces a development **debug APK**. It is suitable for testing on your phone but not a release-signed Play Store build.
- The app performs on-device pose inference, but the downloaded model is added to the app during the cloud build.
- Posture GOOD/BAD flags are provisional demonstration logic, **not validated ergonomic safety verdicts**.
- Your Android phone needs Android 8.0 or later (minSdk 26).

## Project files

- `app/src/main/java/com/example/ergolift/MainActivity.kt` — camera, pose tracking and demo scoring
- `app/src/main/res/layout/activity_main.xml` — user interface
- `.github/workflows/build-apk.yml` — cloud build and downloadable APK artifact
- `app/build.gradle.kts` — Android dependencies

## Troubleshooting

- **Actions workflow missing:** Confirm `.github/workflows/build-apk.yml` is at the repository *root*, not nested inside another `ErgoLiftAndroid` folder. Check Actions are enabled in the repository settings.
- **Build is red:** Open its logs; share the first meaningful compilation error.
- **No APK artifact:** The build was not successful or the artifact retention expired.
- **App cannot see you:** Position the phone far enough away to keep head/shoulders/hips in the camera view; use side view for lifting analysis.
