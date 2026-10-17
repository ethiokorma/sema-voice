@echo off
rem Installs the newest Sema Voice build into the running SemaPhone emulator.
set SDK=C:\Users\Tedz\scoop\apps\android-clt\current
set APK=G:\BUSSINESS IDEAS\SEMAVOICE\app\build\outputs\apk\debug\app-debug.apk
"%SDK%\platform-tools\adb.exe" wait-for-device
"%SDK%\platform-tools\adb.exe" install -r "%APK%"
"%SDK%\platform-tools\adb.exe" shell pm grant com.semavoice.app.debug android.permission.RECORD_AUDIO
"%SDK%\platform-tools\adb.exe" shell am start -n com.semavoice.app.debug/com.semavoice.app.MainActivity
pause
