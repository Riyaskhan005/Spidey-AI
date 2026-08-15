set "JAVA_HOME=C:\Program Files\Java\jdk-17"
set "ANDROID_HOME=C:\Users\riyas\OneDrive\Desktop\ANDROID\sdk"
set "GRADLE_HOME=C:\Gradle\gradle-9.7.0"
set "PATH=%JAVA_HOME%\bin;%ANDROID_HOME%\platform-tools;%ANDROID_HOME%\cmdline-tools\latest\bin;%GRADLE_HOME%\bin;%PATH%"

cd /d D:\Projects\Spidey AI

gradle clean assembleDebug

adb uninstall com.riyas.offlineassistant

adb install app\build\outputs\apk\debug\app-debug.apk