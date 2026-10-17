@echo off
rem Starts the "SemaPhone" Android emulator (Pixel 7, Android 14, 6 GB RAM, Play Store).
rem Uses the real graphics card and passes your PC microphone into the phone.
set SDK=C:\Users\Tedz\scoop\apps\android-clt\current
echo Starting SemaPhone... the first start can take a few minutes.
start "" "%SDK%\emulator\emulator.exe" -avd SemaPhone -no-boot-anim -allow-host-audio -gpu host
