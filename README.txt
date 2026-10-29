OFFLINE ASSISTANT - BUILD INSTRUCTIONS (Gemma 4 / LiteRT-LM edition)
=====================================================================

KOKORO VOICE OUTPUT
-------------------
- Choose a Kokoro `.litertlm` file and voice in AI settings. Puck
  (`am_puck`) is selected by default. The model is copied to app storage
  the first time speech is generated, not bundled in the APK.
- Tamil speech uses an installed offline Android text-to-speech voice;
  Kokoro does not include Tamil.
- Normal Gradle build/install needs no extra batch file. Kokoro speech
  requires `libkokoro_runner.so`; when that native runtime is absent, English
  speech falls back to an installed offline Android voice.
- To use an offline chat model, select **Offline model** in AI settings and
  browse to a supported LiteRT-LM `.litertlm` chat model. Its first use
  copies it into app storage.
- Offline model settings include CPU/GPU backend selection, temperature,
  Top K/Top P sampling, context and response token limits, and Gemma 4's
  optional per-image visual token budget. CPU is the reliable default;
  unsupported GPU devices will report an initialization error.
- The offline engine stays loaded between chat turns while the main screen
  remains active, and is released when the app is backgrounded or Android
  reports memory pressure. A short rolling chat context plus the default
  2048 context and 256 output token budgets reduce memory use. A cold model
  load can still take time when returning to the app.

IMPORTANT UPDATE: Gemma 4's recommended on-device runtime is Google's
NEWER "LiteRT-LM" framework, not the older MediaPipe tasks-genai library.
This project has been updated accordingly:
  - Model format is now .litertlm (NOT .task)
  - Gradle dependency: com.google.ai.edge.litertlm:litertlm-android
  - MainActivity.kt uses the LiteRT-LM Kotlin API (Engine/Conversation)

1. EXTRACT this folder to: D:\Projects\Offile Assistant

2. INSTALL GRADLE (no wrapper jar bundled - could not be downloaded in
   the environment this was built in):
   - Download "Binary-only" zip from https://gradle.org/releases/
     (Gradle 8.7 or newer recommended for AGP 8.5.2)
   - Extract to e.g. C:\Gradle\gradle-8.7

3. UPDATE your android-env.bat session script to include Gradle:

   @echo off
   set "JAVA_HOME=C:\Program Files\Java\jdk-17"
   set "ANDROID_HOME=C:\Users\riyas\OneDrive\Desktop\ANDROID\sdk"
   set "GRADLE_HOME=C:\Gradle\gradle-8.7"
   set "PATH=%JAVA_HOME%\bin;%ANDROID_HOME%\platform-tools;%ANDROID_HOME%\cmdline-tools\latest\bin;%GRADLE_HOME%\bin;%PATH%"
   echo Environment ready for this session.

4. DOWNLOAD THE MODEL (.litertlm, NOT .task):
   - Go to https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm
   - Log in / accept Google's license if prompted
   - Download the .litertlm file (mobile-quantized, roughly 2GB range)
   - Save it as, e.g.: D:\Projects\gemma-4-E4B.litertlm

5. BUILD:
   - Open Command Prompt, run your android-env.bat script
   - cd "D:\Projects\Offile Assistant"
   - gradle assembleDebug
   - First build downloads dependencies from Google's Maven repo - needs
     internet, and may take a while.
   - Output APK: app\build\outputs\apk\debug\app-debug.apk

6. SELECT THE OFFLINE MODEL in the app:
   - Open AI settings, select **Offline model**, and browse to a supported
     `.litertlm` chat model. The app copies it into private storage on first use.

7. INSTALL:
   - adb install app\build\outputs\apk\debug\app-debug.apk

8. RUN on your phone. Shows "Loading model..." then "Ready." Type a
   question, tap Send, response streams in.

NOTES
-----
- Offline model inference does not require network access. The app also
  declares internet permission for configured online providers and search.
- CPU is the default backend. Select GPU in offline AI settings to try
  hardware acceleration; it requires compatible device support. Optional
  OpenCL native libraries are declared in AndroidManifest.xml.
- Model loading can take several seconds to ~10s on mid-range hardware -
  this is normal, matches Google's own documentation.
- If OneDrive causes file-lock build issues, that's why the project
  itself lives on D:\ instead of OneDrive.
