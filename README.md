# GPT Web Native

A minimal native Android wrapper for `https://chatgpt.com/` using the platform `WebView` directly — no Flutter, Firebase, ads, analytics, ML Kit, billing SDK, or generic app-shell framework.

## Goals

- Native Android WebView for lower overhead and smoother scrolling than a Flutter-embedded WebView.
- Status/navigation bar icons stay visible while their background visually matches the ChatGPT page.
- ChatGPT content itself stays below system bars, avoiding notch/status-bar overlap without injecting body padding into the website.
- Cookies, DOM storage and JavaScript enabled for normal ChatGPT usage.
- File upload, camera capture, microphone/camera web permissions, standard HTTPS downloads, popup windows, and Android back navigation.
- Strict navigation policy: known ChatGPT/OpenAI auth hosts stay in-app; unrelated links open in the default browser.
- Cleartext HTTP is never loaded inside the WebView.

## Important login limitation

Embedded WebViews can be rejected by some identity providers, especially Google OAuth. Username/password or OpenAI-hosted flows may work normally; third-party social login can still open externally or fail due to provider policy. The app does not attempt to bypass those restrictions.

## Build on GitHub Actions

Push this project to GitHub. The included workflow builds an installable **release-mode** APK using JDK 17, Gradle 8.14 and AGP 8.11.1.

1. Open the repository's **Actions** tab.
2. Run **Build Android APK**, or push to `main`.
3. After success, download the `gpt-web-native-release` artifact.
4. Extract `app-release.apk` and install it on Android.

The release build is signed with the standard debug keystore for convenient personal sideloading. This gives release-mode runtime performance, but the signing key is not suitable for public distribution or stable long-term updates across different CI runners.

## Before using long-term

For reliable in-place updates without reinstalling, create your own persistent keystore and store it as GitHub Actions secrets. Do this only after functionality is validated.

## Current limitations

- `blob:` downloads are not yet bridged to Android; normal HTTPS downloads are supported.
- OAuth behavior depends on the identity provider's embedded-browser policy.
- The app intentionally does not modify ChatGPT's DOM/CSS in v1. If the mobile header remains cramped, add the smallest possible ChatGPT-specific CSS/JS adjustment after testing the untouched page first.

## Package

`com.local.gptwebnative`

This package is deliberately different from the earlier WebSight wrapper so both versions can be installed side-by-side for comparison.
