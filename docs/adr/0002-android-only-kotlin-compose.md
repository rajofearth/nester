# 0002 Android only, Kotlin + Compose, no React Native

Date: 2026-09-19
Status: accepted

## Context

The phone app needs background sync, a gallery, camera-roll backup, QR scanning, and fast LAN transfers. The team considered React Native with Expo for a single codebase that could later ship iOS.

## Decision

v1 is Android only, Kotlin + Jetpack Compose. A future iOS app would be Swift, speaking the same API. The portability seam is the server's OpenAPI contract (utoipa), not a shared JS codebase.

## Analysis

The hard features are all first-class Android APIs. Expo wraps each one in a third-party module:

| Feature | Android platform API | Expo equivalent |
|---|---|---|
| Background sync | WorkManager / foreground service | expo-task-manager + expo-background-task (community) |
| Share target | intent filters | community plugin, partial |
| Gallery | MediaStore + Paging3 + Coil | expo-media-library, no paging story |
| Video playback | Media3 | expo-av / expo-video |
| LAN transfer | OkHttp | wrapped fetch, no offset-PATCH control |
| QR scanning | CameraX + MLKit | expo-camera |

Every row is the core of the app, not a side feature. Wrapping them means debugging modules when they lag behind OS releases, which is the failure mode Expo projects hit exactly when the app gets interesting. The single-codebase iOS advantage is void because v1 ships Android only. Native Kotlin on one platform is less total work than native-shaped work in JS on one platform.

## Machine constraints

The dev machine is a Windows ARM64 PC: Android SDK at P:\Applications\Android\Sdk, JDK 17 pinned, no Android emulator possible so testing is on physical devices over adb. Robolectric and Roborazzi are unstable locally, so plain JVM unit tests only locally, Robolectric allowed in Linux CI later. gradle.properties is tuned for low RAM (-Xmx4g, workers.max=4, kotlin in-process). The foojay resolver keeps JDK mismatch from blocking builds.

## Consequences

Faster path to every hard feature. Cost: no shared code with a future iOS app, and a second client to write when iOS comes. That cost is bounded because the Kotlin client is thin over the OpenAPI contract; the server is the portable surface.
