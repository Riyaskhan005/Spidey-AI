OFFLINE ASSISTANT - BUILD INSTRUCTIONS (Gemma 4 / LiteRT-LM edition)
=====================================================================

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

6. PUSH THE MODEL to your phone (one-time, or whenever it changes):
   - Connect Realme phone via USB, USB debugging enabled
   - adb push "D:\Projects\gemma-4-E4B.litertlm" /data/local/tmp/gemma-4-E4B.litertlm
   - This path MUST match modelPath in MainActivity.kt (already set to this)

7. INSTALL:
   - adb install app\build\outputs\apk\debug\app-debug.apk

8. RUN on your phone. Shows "Loading model..." then "Ready." Type a
   question, tap Send, response streams in.

NOTES
-----
- No internet permission declared - fully offline once model is pushed.
- Backend is set to CPU() by default for reliability on first run. Once
  working, you can try Backend.GPU() in MainActivity.kt for better speed -
  requires adding <uses-native-library> entries to AndroidManifest.xml
  for libvndksupport.so and libOpenCL.so (see comments in code).
- Model loading can take several seconds to ~10s on mid-range hardware -
  this is normal, matches Google's own documentation.
- If OneDrive causes file-lock build issues, that's why the project
  itself lives on D:\ instead of OneDrive.
