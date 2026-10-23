# Development machine setup (Debian 13)

What the Android build needs, installed system-wide. Google deprecated
`sdkmanager` in favour of a new `android` CLI that ships in the same
command-line tools zip; the steps below use the new one.

## 1. JDK

    apt install openjdk-21-jdk-headless

## 2. Android SDK

The command-line tools zip ("Command line tools only" at the bottom of
https://developer.android.com/studio) is already unpacked at
`/opt/android-sdk/cmdline-tools/`. Install the SDK packages with the
`android` CLI from that directory:

    cd /opt/android-sdk/cmdline-tools/bin
    ./android --sdk=/opt/android-sdk sdk install platform-tools build-tools/36.0.0 platforms/android-36 platforms/android-37
    ./android --sdk=/opt/android-sdk sdk list

- Leave out `platforms/android-37` if it is not offered yet.
- Package names use slashes (`platforms/android-36`); the old `;` form is
  still accepted.
- Accept licences if prompted. If a Gradle build later complains about
  licences, the deprecated tool still works for that:
  `./sdkmanager --sdk_root=/opt/android-sdk --licenses`
- If you installed as root, afterwards:

      chown -R "$USER:$USER" /opt/android-sdk

  Gradle sometimes installs missing SDK pieces during a build.

Emulator, optional (for UI tests without a phone):

    ./android --sdk=/opt/android-sdk sdk install emulator system-images/android-36/default/x86_64
    adduser "$USER" kvm

The `default` image has no Google services, which matches GrapheneOS.

## 3. Environment

`/etc/profile.d/android.sh`:

    export ANDROID_HOME=/opt/android-sdk
    export PATH="$PATH:/opt/android-sdk/cmdline-tools/bin:/opt/android-sdk/platform-tools"

`~/.androidrc` (one line, so `--sdk` is not needed on every `android` call):

    --sdk=/opt/android-sdk

`/etc/profile.d` is read by login shells only; a terminal inside an editor
usually is not one, so also add to `~/.bashrc`:

    . /etc/profile.d/android.sh

(The `make android*` targets default `ANDROID_HOME` to `/opt/android-sdk`
anyway, and Gradle also reads `android/local.properties`, gitignored,
containing `sdk.dir=/opt/android-sdk`.)

Gradle needs no install: the project's wrapper (`android/gradlew`) fetches
it.

## 4. Phone over USB

    apt install android-sdk-platform-tools-common

That package is only for the udev rules; use the `adb` from the SDK's
`platform-tools`.

On the phone, once:

1. Unlock Developer options: Settings → About phone (on some phones: → Software
   information) → tap **Build number** seven times.
2. Settings → System → Developer options → turn on **USB debugging**.
3. Plug the phone in and accept "Allow USB debugging?" from this computer,
   ticking "Always allow". Until then `adb devices` lists it as
   `unauthorized`; if it does not appear at all, switch the USB mode in the
   notification shade from "Charging only" to File transfer.

`make android-install` then pushes the debug build over `adb`; no
sideloading prompt is involved.

## 5. Publishing (optional)

    apt install gh
    gh auth login

Not used yet: tracker is sideloaded (`make android-release` copies the
signed APK to `dist/`). An Obtainium/GitHub-releases flow like planner's can
follow later; `gh` would then attach the APKs to releases.

## 6. Android Studio (optional)

Only if you want to open the project yourself: https://developer.android.com/studio,
pointed at `/opt/android-sdk`. Open the `android/` directory as the project,
not the repository root.

## On the phone

- OsmAnd: https://osmand.net/ (any flavour: `net.osmand.plus`, `net.osmand`
  or the nightly `net.osmand.dev`); the app shows the entities on its map.
- WireGuard: https://www.wireguard.com/install/
- Obtainium (later, once releases are published):
  https://github.com/ImranR98/Obtainium

## Check

    java -version          # 21
    android info           # shows /opt/android-sdk
    adb devices            # lists the phone when plugged in
    cd ~/Git/tracker/android && ./gradlew assembleDebug
