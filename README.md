# FaceGallery

A private Android photo gallery that groups your photos by the people in them.
Face detection and recognition run entirely on your phone: the app has no Internet
permission, no accounts and no analytics, and your photos never leave the device.

> **Status:** preview. It works well on the maintainer's phone, but recognition
> quality varies between galleries and it has not been tested on many devices.
> Bug reports are welcome in [Issues](https://github.com/nayakp/FaceGallery/issues).

## Install

1. Download the latest `FaceGallery-<version>-release.apk` from
   [Releases](https://github.com/nayakp/FaceGallery/releases).
2. Open it on your phone. If Android asks, allow your browser or file manager to
   **install unknown apps**.
3. Open FaceGallery, grant photo access, and start scanning from **Settings**.

Requires Android 9 or newer. The APK is about 360 MB because the face-recognition
model is bundled inside it.

**Updating:** install the newer APK over the old one and choose **Update**. Don't
uninstall first: uninstalling deletes your face groups and names. If you ever must
reinstall, use **Settings → Export scan backup** first and import it afterwards.

## Features

**People**
- Photos are grouped by face, with the most-photographed people first.
- Name people as you go: on launch, a review asks you to name the most frequently
  seen unnamed faces, or merge them into an existing person.
- People who appear in only one photo are collected under **Single Face Pics**.
- Search people by name.
- **Albums:** combine several people into a named album to see their photos together.

**Photo viewer**
- Full-screen viewer with swipe left/right between photos, pinch zoom and
  double-tap zoom levels.
- **Swipe up to share** a photo.
- Photo info, including EXIF details and location when available.
- Delete a photo (Android asks you to confirm).

**Fixing mistakes**
- **Find similar faces to combine** (in Settings): compare likely matches side by side
  and combine groups that show the same person. Answers of "different" are remembered.
- The same option is in each person's photo-grid menu, to search for just that person.
  When the search finishes, choose **Review similar faces** to combine the matches.
- Merge two people, rename a person, or remove a wrong face tag from a photo.
- **Re-evaluate unnamed faces** compares unnamed groups against everyone using the
  saved face data, without rescanning your photos.

**Scanning and data**
- Scanning keeps running in the background or with the screen locked, with a
  notification. It can be paused and resumed at any time; finished photos are saved
  as it goes, and unchanged photos are never scanned twice.
- **Export / import scan backup:** a `.fgbackup` file with your groups, names and
  face data, for moving to a new phone without rescanning.
- **Delete face photos:** select people and delete every photo they appear in, after
  two confirmations that name the selected people.
- Your original photos are only changed when you confirm a delete.
- On Android 12+, the first delete offers to turn on "Media management" access for
  FaceGallery. With it on, deletes only use FaceGallery's own confirmations instead
  of also showing Android's system prompt. This is asked only once.

## Privacy

- Everything runs on the phone. The app has no Internet permission, so it can't
  upload anything.
- The face database stays in the app's private storage. If Android device backup
  is on, Android may include it in its own encrypted backup.
- Backup files you export are saved wherever you choose.
- **Help & Support** opens a GitHub issue with basic device and app details filled
  in. GitHub issues are public, so check that text before submitting.

## How recognition works

[YuNet](https://github.com/opencv/opencv_zoo) (2023mar) finds faces and five facial
landmarks. Each face is aligned to 112×112 and passed to InsightFace's antelopev2
`glintr100.onnx` (ResNet-100), which produces a 512-dimensional face embedding.
[OpenCV](https://opencv.org) 4.12 runs both ONNX models on the CPU.

Each person keeps up to 12 sample faces. A new face joins a person when the cosine
similarity is at least 0.42 and at least 0.05 better than the next-best person.
These thresholds favour separate groups over wrong merges, so expect some duplicates
to review. Photos are scaled to at most 1280 px and faces smaller than 40 px are
skipped. Side profiles, distant or covered faces, look-alikes and children growing
up can still be grouped wrongly. Use merge and the duplicate review to fix them.

## Build from source

### Requirements

- Java 17
- Android SDK platform 36 and build-tools 35.0.0 (Android Studio installs these)
- Gradle 9.3.1 when building from the command line. The repository has no
  Gradle wrapper.
- Python 3, to download the models once
- A few GB of free disk space for the SDK, Gradle cache, models and APK

### Setup

1. Clone the repository and open it in Android Studio, or create
   `local.properties` in the repository root with your SDK path:

       sdk.dir=C\:\\Users\\YOUR_NAME\\AppData\\Local\\Android\\Sdk

   (On macOS or Linux, use a path such as `/Users/YOUR_NAME/Library/Android/sdk`.)

2. Download and checksum-verify the models:

       python3 tools/fetch_models.py

   On Windows, use `py -3 tools\fetch_models.py`. The `.onnx` files are
   Git-ignored because the recognition model is about 250 MB.

### Debug build

    gradle :app:assembleDebug :app:lintDebug

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. To install it
on a phone with USB debugging enabled:

    adb install -r app/build/outputs/apk/debug/app-debug.apk
    adb shell am start -n com.harry.faces/.MainActivity

Or select the `app` run configuration in Android Studio and choose **Run**.

### Release build

Official release APKs are signed with the maintainer's release key, which is not
in this repository. To sign your own release build, create a keystore and a
`keystore.properties` file in the repository root (it is Git-ignored):

    storeFile=/absolute/path/to/your-release.jks
    storePassword=...
    keyAlias=...
    keyPassword=...

Then run `gradle :app:assembleRelease`. The signed APK is written to
`app/build/outputs/apk/release/app-release.apk`. Without `keystore.properties`,
the release APK is built unsigned.

Android only installs an update signed with the same key as the installed app.
A build signed with a different key (for example, your own build over an official
release) must be uninstalled first, which deletes its face database.

### Build with GitHub Actions

Every push to `main` or `master` runs the **Build Faces APK** workflow. It runs the
matcher test, downloads and verifies the models, builds a debug APK, runs lint,
and uploads the APK as the `Faces-debug-APK` artifact. These debug builds are signed with a key generated
by the build runner, so they can't update each other or an official release.

### Matcher test

The face-matching logic has a standalone Java regression test:

    mkdir -p /tmp/faces-test
    javac -d /tmp/faces-test app/src/main/java/com/harry/faces/Matcher.java tests/MatcherTest.java
    java -cp /tmp/faces-test com.harry.faces.MatcherTest

## Contributing

Issues and pull requests are welcome. Useful things to test and report:

- Full and limited ("selected photos") access, and revoking access later.
- Portraits, rotated photos, profiles, group shots, photos without faces and
  corrupt files.
- Pausing, resuming, backgrounding, force-stopping and rotating during a scan.
- False matches, duplicate groups, memory use, speed, heat and battery on a large
  library.

## License and credits

The FaceGallery source code is released under the [MIT License](LICENSE). Third-party
parts keep their own terms:

- **YuNet** face detector: MIT License.
- **InsightFace antelopev2 (`glintr100`)** recognition weights: **non-commercial
  research use only**. Do not sell this app, add ads to it or otherwise use it
  commercially without a separate licence from InsightFace. The full notice is in
  `app/src/main/assets/INSIGHTFACE_MODEL_TERMS.txt` and in the app under
  **Settings → Model licenses**.
- **Icons:** settings, home, search, albums, back and delete icons are by
  [Icons8](https://icons8.com), used under their free licence, which requires this link.
