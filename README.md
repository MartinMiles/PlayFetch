<p align="center">
  <img src="docs/playfetch-icon.svg" width="128" height="128" alt="PlayFetch icon">
</p>

<h1 align="center">PlayFetch</h1>

<p align="center">
  Download original signed free-app packages for a selected Google Play market.
</p>

<p align="center">
  <a href="https://github.com/MartinMiles/PlayFetch/actions/workflows/android-ci.yml"><img alt="Android CI" src="https://github.com/MartinMiles/PlayFetch/actions/workflows/android-ci.yml/badge.svg"></a>
  <a href="LICENSE"><img alt="GPL-3.0-or-later" src="https://img.shields.io/badge/license-GPL--3.0--or--later-blue.svg"></a>
  <a href="https://github.com/MartinMiles/PlayFetch/releases"><img alt="Latest release" src="https://img.shields.io/github/v/release/MartinMiles/PlayFetch?include_prereleases"></a>
</p>

PlayFetch is an Android client for saving an original, publisher-signed package into the device's standard `Download` folder. Paste a Play URL or package name, choose a country, and start the download. Play links can also be sent to PlayFetch from Android's share sheet, **Open with**, or **Process text** actions.

## Highlights

- Select from Play markets with flags; United Kingdom and United States are pinned first.
- Try anonymous Google Play delivery first, preserving Google's original APK or split APK signatures.
- Verify Google-delivered file hashes against Play metadata.
- Use a signer-verified APKPure catalog fallback when Google exposes a listing but refuses anonymous delivery.
- Share the downloaded package to Telegram's recipient picker.
- Delete the last download from the app to avoid filling device storage.
- Support adaptive, round, and Android themed launcher icons.

## Package output and integrity

- A monolithic Play package is saved as `package-version.apk`.
- An app delivered as a Play App Bundle is saved as `package-version.apks`. The archive contains Google's untouched, signed `base.apk` and configuration splits plus `playfetch.json` integrity metadata.
- Google-delivered files are size- and hash-checked against Play delivery metadata.
- Fallback APKs undergo full Android APK signature verification and are checked against available Google/catalog signer metadata, the requested package and version, and any installed signing lineage.
- PlayFetch never merges or re-signs downloaded apps.

## Build locally

Requirements:

- JDK 21
- Android SDK Platform 37

The checked-in Gradle wrapper downloads the required Gradle version. On Windows:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME="$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

The installable debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`. CI runs the same tests and lint checks for every pull request and push to `main`.

## Limitations

PlayFetch supports free apps. Paid apps, private testing releases, account-entitled content, Play Asset Delivery edge cases, and server-side device restrictions may be unavailable to anonymous sessions. Google can change its private Play protocol, and anonymous token services can be unavailable or rate-limited. A selected country changes the locale/market context presented to Play; account, network, policy, and device compatibility can still affect availability.

The fallback service is not a regional storefront and currently requires a monolithic APK. It cannot turn a split-only release into one APK without invalidating publisher signatures.

Google owns and verifies the `play.google.com` domain. On newer Android versions, its verified Play Store association can take precedence over an unverified third-party handler. If **Open with** does not show PlayFetch, use **Share** or select the link text and choose PlayFetch under **Process text**; both routes populate the URL automatically.

## Security and legal

This application is unofficial and is not affiliated with Google, Aurora OSS, APKPure, Telegram, or any downloaded app publisher. Installing packages outside Google Play can change Play Integrity behavior and automatic-update availability. Only install packages you are legally entitled to use and verify sensitive apps independently.

Report security issues privately as described in [SECURITY.md](SECURITY.md). PlayFetch is licensed under [GPL-3.0-or-later](LICENSE) because it uses Aurora OSS GPlayApi; dependency attribution is in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

See [CONTRIBUTING.md](CONTRIBUTING.md) before proposing a change, [CHANGELOG.md](CHANGELOG.md) for release history, and [RELEASING.md](RELEASING.md) for the signed-release process.
