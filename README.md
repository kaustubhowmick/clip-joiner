# Clip Joiner

An Android app that merges videos quickly and without re-encoding, where it can.

- **Matching clips are stream-copied.** The compressed packets are copied into a new MP4 with shifted timestamps (Android's `MediaExtractor` + `MediaMuxer`, the same idea as `ffmpeg -f concat -c copy`). Nothing is decoded, so a merge takes a second or two and the output is about the size of the inputs combined.
- **Mismatched clips are the only ones converted.** Clips match when their video codec, stored resolution, rotation, frame rate, and audio codec, sample rate and channel count all match. When some clips don't match, the app picks the most common format and re-encodes only the clips that differ (Media3 Transformer, hardware encoder, at the copied clips' bitrate). A clip whose orientation differs is scaled to fit and centred on black bars, never stretched or cropped. Everything is then joined by stream copy.
- **Portrait or landscape?** If the clips have both orientations, a bottom sheet asks which one to use. The majority orientation is preselected and marked "Recommended (most of your clips)". On a tie (1 vs 1, 2 vs 2), nothing is preselected.
- **Clips without audio** get a matching silent track, so the sound stays in sync. Only the silence is encoded; the clip's video is still copied.
- Output is saved to **Movies/Clip Joiner**.

Kotlin, Jetpack Compose, Material 3 (dynamic colour and dark mode), minSdk 26. It needs no storage permission on Android 10+, because it uses the system Photo Picker to read clips and MediaStore to save.

## Install

Open the repository's **Releases** page on your phone and download `ClipJoiner.apk` from the **latest** release. Then open the file and allow your browser or file manager to install apps when Android asks. Each push to `main` updates that release, but only after the unit tests and the emulator merge tests pass.

Every build is signed with the same key (`signing/`), so a newer APK installs over an older one and keeps its data.

## How it's tested

The [Build workflow](.github/workflows/build.yml) does the following:

1. Runs the JVM unit tests for the merge planner (`MergeAnalysisTest`).
2. Builds the signed release APK.
3. Generates test clips with FFmpeg (`scripts/make_test_clips.sh`). The portrait clips are H.264 stored as 1280×720 with a 90° rotation flag, like phone recordings. There is also an H.265 landscape clip and a clip without audio. Each clip has a white flash and a beep at exactly 1.0 s.
4. Runs the real merge engine on an Android 14 emulator (`MergeEngineTest`), decodes every output with the device's decoder, then pulls the outputs and checks them with ffprobe (`scripts/verify_outputs.py`). The checks cover duration, continuous video and audio timelines, clean decoding, flash/beep sync for every clip, black bars only on converted clips, and output size ≈ inputs for stream-copy merges.

## Build locally

You need JDK 17 and the Android SDK (platform 36).

```bash
./gradlew testDebugUnitTest assembleRelease   # app/build/outputs/apk/release/app-release.apk
```
