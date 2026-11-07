# FaceGallery — Android gallery preview

Status: preview source. Local Android compilation, lint, APK signature verification,
and all eleven pure-Java matcher checks pass. Physical-phone behavior and recognition
quality still require testing; do not treat this as a production release.

## Features implemented
- Circular person covers sorted by distinct-photo count, with locale-aware tie breaking.
- A plain left-side hamburger opens a full-screen Settings page that slides in from
  the left. Back or a left swipe closes it and returns to the previous face screen.
- Settings contains a green face-recognition toggle with live scan counts plus naming,
  duplicate review, backup/import, photo access, model terms and About actions.
- Settings also has a self-resetting `Re-evaluate unnamed faces` toggle. It compares
  every unnamed group with all existing named and unnamed groups using the saved
  embeddings, so it can combine two unnamed groups without rescanning original photos.
  Existing Different/Skip decisions and faces that occur in the same photo are excluded.
  A green Home indicator and foreground notification remain visible while this
  CPU-intensive job runs, and switching the toggle off pauses it safely. Re-evaluation
  runs at the lowest thread priority and performs matching outside the shared database
  lock so the gallery remains responsive while it works.
- A rounded floating bar appears only on the Home screen. Home resets to the main
  face list; Search opens a live face-name filter above the grid.
- One-photo groups are collected under a `Single Face Pics` circle and remain browsable.
- Person → uncluttered photo grid → edge-to-edge photo viewer. A single tap hides
  or restores all controls. Repeated double taps cycle through width-fit, height-fill,
  native-resolution zoom and back to width-fit; drag and pinch gestures remain active.
  At width-fit, swipe left for the next photo or right for the previous photo.
- The viewer has overlay Back and three-dot controls, an Info action for MediaStore
  and EXIF details (including embedded GPS when permission/data are available), a
  confirmed `This isn't [face]` action when opened from a face group, swipe-up to Share
  and a bottom confirmed Delete action. Removing the last face tag also marks that photo
  unscanned so the next scan can reconsider it; the original photo is never changed.
- Back returns to the grid and then people; grid position is retained.
- Rename from the person menu; leave blank to remove the name.
- On launch, a continuous review names the most frequently seen unnamed people first;
  each face can also be merged into an existing group. It refreshes its ranking after
  every 20 decisions and continues until closed. `Next` advances without a name,
  and skipped faces do not repeat in that session. The same review is in the menu.
- A continuous possible-duplicates review presents the most similar face groups side
  by side and remembers “different” answers; accepting a pair combines both groups.
  It re-ranks suggestions after every 20 decisions and continues until the user closes
  the dialog or no likely pairs remain. Skips are saved in the scan database and stay
  out of future normal duplicate reviews.
- Each face's photo-grid title has a settings menu for checking that face alone. The
  check runs in the background and prepares every match above the duplicate-review
  similarity threshold. A toast reports when the list is ready, and `Review duplicates`
  remains in that face's menu while even one prepared comparison remains. Review uses
  a virtualized vertical list with row-level Different, Skip and Combine actions.
  Results are split into carousel pages of up to 50 faces, with the previous and next
  pages buffered for direct finger tracking, a thin position bar above the list and a
  remaining-count title. Confirmed `All Different` and `Combine all` actions apply only
  to the current page and use one database transaction.
  Resolving a page advances to the next set, falls back to the previous page at the end,
  and closes with an all-resolved message when no comparisons remain.
- Settings can revalidate and review saved skipped comparisons. A progress dialog is
  shown while stale, merged, deleted and no-longer-similar pairs are discarded and the
  remaining valid pairs are re-ranked.
- Android encrypted backup/device transfer can restore the SQLite face index after
  reinstalling. The menu can also export/import a compressed `.fgbackup` containing
  scan records, embeddings, thumbnails, group corrections and names.
- Each indexed photo receives a SHA-256 content fingerprint. After importing on a new
  phone, Resume relinks byte-identical transferred photos even when Android assigned
  different MediaStore URIs, avoiding another InsightFace recognition pass.
- Model licenses are always available from Settings.
- A foreground service and bounded wake lock let a user-started scan continue while
  the app is backgrounded or the screen is locked; every completed photo is saved
  atomically in SQLite so interrupted scans resume safely.
- Resumes without repeating unchanged photos. Modified images are rescanned.
- Unreadable photos retry on the next scan; deleted images are reconciled when
  full photo-library permission is available.
- Larger, tightly spaced circular covers use an expanded face-and-neck crop with
  extra headroom, centered names and centered photo counts.
- Merge duplicate groups; hold a wrongly grouped photo to separate its face.
- No Internet permission, app-managed uploads, accounts or analytics. The app changes
  an original only when the user explicitly confirms Delete in the viewer. Android
  itself may store the database in its encrypted system backup
  when device backup is enabled; manual backup files stay in the user-selected location.
- Android 9+; modern full-library and selected-photo permissions supported.
- Grid views recycle visible cells, face covers load lazily, bitmap caches are bounded,
  scan images are downsampled, and recognition templates are indexed once per scan.

Android may impose a time limit on foreground media processing on newer releases.
If that limit is reached, the app pauses safely and the next Resume continues from
the database without repeating unchanged photos.

## Recognition
YuNet 2023mar detects faces and five facial landmarks. The full-size InsightFace
antelopev2 `glintr100.onnx` ResNet-100 model extracts 512-dimensional ArcFace
embeddings after canonical 112x112 alignment. OpenCV 4.12 runs both ONNX models
on the CPU. Model downloads are performed at build time, SHA-256 verified, and
bundled in the APK with their license/usage notices.

Each group uses up to 12 distinct samples. Matching requires cosine ≥0.42 and
at least 0.05 separation from the next-best person; these are conservative starter
heuristics, not thresholds calibrated on the user's gallery. Faces in the same
photo are not automatically assigned to the same group. Recognition processes
images scaled to at most 1280 pixels and skips detections smaller than 40 pixels.
This favors distinct groups over accidental merges. Sharp profiles, distant or
obscured faces, similar people and children's changing appearances can still fail.
There is no claim of best-in-class accuracy. Merge/separate controls allow repair.
Matching uses a bounded in-memory template index for the active scan; very large
galleries still need profiling on representative phones before production use.

Official model reference and benchmarks:
https://docs.opencv.org/4.x/d0/dd4/tutorial_dnn_face.html
Detector source: https://github.com/opencv/opencv_zoo
Recognizer source: https://github.com/deepinsight/insightface
Model terms: YuNet is MIT licensed. The public InsightFace antelopev2 pretrained
weight is for non-commercial research use only. The notice is retained in assets
and viewable in the app. This build must not be commercially distributed without
a separate InsightFace model license.

Icons: settings, home, search, albums, back and delete icons are by
[Icons8](https://icons8.com) and used under their free license, which requires this link.

## License

The FaceGallery source code is released under the [MIT License](LICENSE).
The MIT License does not cover third-party assets: the YuNet model (MIT), the
InsightFace pretrained weights (non-commercial research only) and the Icons8 icons
keep their own terms, described above.

The InsightFace model is about 249 MiB by itself, so installation, first model
load, inference latency and peak RAM are substantially higher than the previous
SFace preview. Upgrading from that preview clears the incompatible face index and
saved group names once; Resume then rebuilds the gallery with InsightFace vectors.

## Run locally

### Requirements

- Android Studio, or the Android command-line SDK tools.
- Java 17.
- Python 3, used once to download and verify the face-detection models.
- Android SDK platform 36 and build-tools 35.0.0.
- Gradle 9.3.1 when building outside Android Studio. This repository does not
  include a Gradle wrapper binary.
- An Android 9 (API 28) or newer phone or emulator.
- Several gigabytes of free disk space for the SDK, Gradle cache, models and APK.

### First-time setup

1. Open this repository as a project in Android Studio.
2. Let Android Studio configure the SDK, or create `local.properties` in the
   repository root with your Android SDK path. For example, on Windows:

       sdk.dir=C\:\\Users\\YOUR_NAME\\AppData\\Local\\Android\\Sdk

   On macOS or Linux, use a path such as:

       sdk.dir=/Users/YOUR_NAME/Library/Android/sdk

3. Download and checksum-verify the YuNet and InsightFace models:

       python3 tools/fetch_models.py

   On Windows, `py -3 tools\fetch_models.py` can be used instead. The downloaded
   `.onnx` files are intentionally ignored by Git because the recognition model
   is larger than common Git hosting limits. Its license notice remains tracked
   in `app/src/main/assets`.

### Build and check

From the repository root, run:

    gradle :app:assembleDebug :app:lintDebug

The debug APK is written to:

    app/build/outputs/apk/debug/app-debug.apk

### Release build

Official release APKs are signed with the maintainer's release key, which is not
in this repository. To sign your own release build, create a keystore and a
`keystore.properties` file in the repository root (it is Git-ignored):

    storeFile=/absolute/path/to/your-release.jks
    storePassword=...
    keyAlias=...
    keyPassword=...

Then run `gradle :app:assembleRelease`. The signed APK is written to
`app/build/outputs/apk/release/app-release.apk`. Without `keystore.properties`
the release APK is built unsigned. Android only installs an update when it is
signed with the same key as the installed app, so a build signed with a
different key must be uninstalled first, which deletes its face database.

The standalone Java matcher regression check is documented under
**Matcher test** below.

Alternatively, select the `app` run configuration in Android Studio and choose
**Run**.

### Install and launch on a phone

Enable USB debugging on the phone, connect it, and confirm that ADB can see it:

    adb devices

Then install or update the debug build and launch it:

    adb install -r app/build/outputs/apk/debug/app-debug.apk
    adb shell am start -n com.harry.faces/.MainActivity

Grant the requested photo permissions on first launch. Start or resume face
scanning from the app's Settings menu. Scanning and matching run entirely on the
device; the phone's photos are not uploaded by this app.

## Build online — GitHub Actions

1. Create an empty private GitHub repository without adding a README, license or
   `.gitignore` there.
2. Add that repository as the remote for this local repository and push `main`:

       git remote add origin https://github.com/YOUR_NAME/YOUR_REPOSITORY.git
       git push -u origin main

3. Actions → Build Faces APK starts automatically after the push.
4. The workflow downloads the approximately 344 MiB antelopev2 package, extracts
   its verified recognition weight, builds a debug APK, runs Android lint, and
   exposes a `Faces-debug-APK` artifact after a successful build.
5. Download and extract that artifact; install `app-debug.apk` on Android.

The resulting build is an installable preview, not proof of recognition
quality. GitHub Actions must be enabled and have available build quota. No phone
photos are needed or uploaded to build the app. Debug signing is generated by the
build runner; subsequent builds may need an uninstall unless a stable signing key
is provisioned. Uninstalling deletes group names/index data.

## Updating without losing scans
Install a newer APK over the existing app and choose **Update**; do not uninstall
first. Android preserves the database when the package name and signing certificate
stay the same and the new APK has a higher `versionCode`. Local builds in this
workspace use the same current debug signing key. A production release must use one
permanent release keystore for every version and keep that keystore safely backed up.
If a differently signed build requires uninstalling, export a scan backup first and
import it after installation.

## Checks still required on a phone
- Grant full access and limited selected-photo access; deny and later revoke access.
- Scan portraits, rotated photos, profiles, group shots, no-face photos and corrupt files.
- Pause mid-scan, resume, background, force-stop, reopen and rotate the phone.
- Rename multiple people and confirm named-first ordering/counts/back navigation.
- Verify merging and separating never changes or removes original photographs.
- Check recently added/modified/deleted images and photos on removable storage.
- Measure false matches, duplicate groups, memory, speed, heat and battery on a
  realistic large library. Face recognition quality has not been tested here.

## Matcher test
    mkdir -p /tmp/faces-test
    javac -d /tmp/faces-test app/src/main/java/com/harry/faces/Matcher.java tests/MatcherTest.java
    java -cp /tmp/faces-test com.harry.faces.MatcherTest
