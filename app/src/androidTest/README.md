The header instrumentation runner checks real Android view measurements without an external test framework. It covers channel, game, and list headers at 600dp and 900dp widths, repeated rotation, delayed toolbar resizing, preserved tab selection and sort listeners, and the editable search field.

With an Android device or emulator connected:

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w com.github.artanvrajolli.xtralite.debug.test/com.github.andreyasadchy.xtra.util.BrowseHeaderInstrumentation
```

Success prints `Passed 9 header scenarios, including repeated rotation and late toolbar resizing.` A failed check prints an exception or an instrumentation failure.
