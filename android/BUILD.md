# Building the Android app on Arch Linux

This guide builds the TimeTracker companion app from source and installs it on your phone. You don't need Android Studio, only Java 17, the Android SDK command-line tools and `adb`. The phone needs Android 8.0 or newer and Bluetooth LE.

Run every command as your normal user, except the ones that start with `sudo`.

## 1. Install the packages

```sh
sudo pacman -S --needed jdk17-openjdk unzip android-tools android-udev
```

- `jdk17-openjdk`: Gradle and the Android build tools run on Java 17. Other JDKs can stay installed.
- `android-tools`: provides `adb`.
- `android-udev`: lets `adb` reach a phone over USB without root. Add yourself to its group, then log out and back in:

  ```sh
  sudo gpasswd -a "$USER" adbusers
  ```

## 2. Install the Android SDK

The SDK goes in `~/Android/Sdk`. First add two lines to `~/.bashrc` (or `~/.zshrc`), so that every new shell finds the SDK and Java 17:

```sh
export ANDROID_HOME="$HOME/Android/Sdk"
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk
```

Open a new terminal, or run `source ~/.bashrc`, then download the command-line tools:

```sh
mkdir -p "$ANDROID_HOME/cmdline-tools"
cd "$ANDROID_HOME/cmdline-tools"
curl -LO https://dl.google.com/android/repository/commandlinetools-linux-16111833_latest.zip
unzip -q commandlinetools-linux-16111833_latest.zip
mv cmdline-tools latest
rm commandlinetools-linux-16111833_latest.zip
```

> [!NOTE]
> Google replaces this zip from time to time. If the link stops working, copy the current *Command line tools only → Linux* link from <https://developer.android.com/studio#command-tools>.

Install the parts the app needs, one command each:

```sh
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" "platforms;android-36"
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" "build-tools;35.0.0"
```

Afterwards `ls "$ANDROID_HOME"` should list `build-tools`, `cmdline-tools` and `platforms`.

This takes about 0.5 GB. The first build adds about 1 GB of Gradle downloads in `~/.gradle`.

> [!NOTE]
> Recent command-line tools (version 23 and newer) print a warning that `sdkmanager` is deprecated. They then download Google's new `android` tool into `~/.android`, show its terms and install the packages through it. The warning is harmless, and the commands above work as they are. The licenses no longer need to be accepted separately: `sdkmanager --licenses` only answers "no longer needed". You don't need to run `android` yourself. It isn't on your PATH anyway, so `android sdk` gives "command not found".
>
> Older command-line tools ask you to accept the licenses first. If `sdkmanager` stops with a license error, run `sdkmanager --licenses`, answer `y` to each license, then run the install commands again.

## 3. Build

```sh
cd android            # in this repository
./gradlew assembleDebug
```

The first run downloads Gradle itself and the libraries, which takes a few minutes. After that the app is in:

```
app/build/outputs/apk/debug/app-debug.apk
```

To run the unit tests (the time accounting, the Bluetooth byte format and the storage):

```sh
./gradlew testDebugUnitTest
```

## 4. Install on the phone

### Over USB

1. On the phone, open **Settings → About phone** and tap **Build number** seven times. This unlocks **Developer options**, which on most phones are under **Settings → System**.
2. In Developer options, turn on **USB debugging**.
3. Connect the phone with a USB cable and run `adb devices`. Unlock the phone, accept **Allow USB debugging?**, and run `adb devices` again. It should list the phone as `device`.
4. Install the app:

   ```sh
   ./gradlew installDebug
   ```

   Or, with an APK you already built: `adb install -r app/build/outputs/apk/debug/app-debug.apk`

### Over Wi-Fi (Android 11 and newer)

Put the phone and the computer on the same network. In Developer options, open **Wireless debugging** and turn it on.

1. Tap **Pair device with pairing code**. The phone shows an address with a port, and a code.
2. Run `adb pair 192.168.1.23:37000` with the phone's address and port, and enter the code.
3. Run `adb connect 192.168.1.23:41000`. Use the address shown on the Wireless debugging screen itself, whose port differs from the pairing port.
4. Install with `./gradlew installDebug`, as over USB.

### Without adb

Copy `app-debug.apk` to the phone (by USB file transfer, cloud storage, or a message to yourself) and open it in the Files app. Android asks you to allow that app to install unknown apps. Allow it, then confirm the install.

## 5. First start

1. Flash the tracker with the firmware from this branch, as described in the main [README](../README.md#building-and-flashing). The splash screen shows **v3.0**.
2. Open **TimeTracker** on the phone and tap **Connect** in the top-right corner.
3. Allow **Nearby devices**. On Android 11 and older the system asks for location instead, because there Bluetooth scanning needs it.
4. Tap **TimeTracker** in the list. The pill turns to **Connected**.
5. On the **Categories** tab, add a few categories. On the **Tasks** tab, add tasks and give each one a timer, T1 to T5.

## Updating the app

Pull the changes, then run `./gradlew installDebug` again. Your tasks and times stay.

Android installs an update only if it was signed with the same key as the installed app. Debug builds are signed with `~/.android/debug.keystore`, which the first build on this computer creates. Back that file up. If you build on another computer, or lose the file, Android refuses the update. The only way out is to uninstall the app, which deletes its tasks and times.

## Optional: release build

A release build is smaller and a little faster. It is signed with a key of your own:

```sh
keytool -genkeypair -v -keystore ~/timetracker-release.jks -alias timetracker \
        -keyalg RSA -keysize 4096 -validity 36500
```

Then create `android/keystore.properties`. Git ignores it, so it never leaves your computer:

```properties
storeFile=/home/YOU/timetracker-release.jks
storePassword=the password you chose
keyAlias=timetracker
keyPassword=the same password
```

Build and install:

```sh
./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

A release build can't update a debug build, because the keys differ. Uninstall the debug app first (this deletes its data), or decide on one kind of build before you start using the app. Back up the `.jks` file and its password: every future update needs both.

## Troubleshooting

| Problem | Fix |
|---|---|
| Gradle fails right away with `Unsupported class file major version` or a Java version error | `JAVA_HOME` doesn't point to Java 17. Run `export JAVA_HOME=/usr/lib/jvm/java-17-openjdk`. |
| `SDK location not found` | `ANDROID_HOME` isn't set in this shell. Alternatively, create `android/local.properties` with `sdk.dir=/home/YOU/Android/Sdk`. |
| `adb devices` says `unauthorized` | Unlock the phone and accept the USB debugging prompt. If it doesn't appear, revoke USB debugging authorizations in Developer options and plug the phone in again. |
| `adb devices` says `no permissions` | Check that `android-udev` is installed and that you are in `adbusers` (`id -nG`). Log out and back in, then plug the phone in again. |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | The installed app was signed with another key. Run `adb uninstall io.github.verticalhorizon.timetracker` (this deletes the app's data) and install again. |
| The app doesn't find the tracker | Check that the tracker runs the Bluetooth firmware (v3.0 on the splash screen) and is not connected to another phone; it serves one at a time. On Android 11 and older, turn on Location. |
