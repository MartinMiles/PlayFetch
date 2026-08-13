# Third-party notices

PlayFetch uses [Aurora OSS GPlayApi](https://gitlab.com/AuroraOSS/gplayapi), version 3.6.4, licensed under GNU GPL v3.0. GPlayApi is an unofficial implementation of Google Play's private API.

The Pixel 9a device profile consumed at runtime is supplied by GPlayApi and carries its upstream GPL-3.0-or-later attribution.

AndroidX libraries are licensed under Apache License 2.0. Material Components for Android is licensed under Apache License 2.0. Kotlin and kotlinx.coroutines are licensed under Apache License 2.0. Protobuf and OkHttp, used transitively by GPlayApi, are licensed under BSD-3-Clause and Apache License 2.0 respectively.

PlayFetch uses Android's APK Signature Scheme verifier (`apksig`) from the Android Open Source Project under Apache License 2.0: https://android.googlesource.com/platform/tools/apksig/

When Google Play refuses anonymous delivery, PlayFetch may use APKPure's public catalog and download service as a fallback. APKPure is a network service and is not bundled with this application.
