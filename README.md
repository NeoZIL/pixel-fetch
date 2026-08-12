# PixelFetch

**PixelFetch** is a small FOSS Android app that reproduces the *public-data fetching portion* of the Pixel Canary discovery used by upstream tooling.

It does **not** require root and does **not** modify Android system properties.

### What it does
1. Reads the current Android Developers version page.
2. Finds the latest Pixel Beta download tables.
3. Selects a Pixel device/product.
4. Reads the current Google Flash Tool client key from its public page.
5. Queries Google's public Flashstation build metadata endpoint.
6. Selects the latest entry marked `canary`.
7. Reads the corresponding Pixel security bulletin.
8. Displays the resulting build/fingerprint metadata.
9. Saves the result inside the app's private storage.

### What it does NOT do
- no root
- no Magisk/KernelSU/APatch
- no `su`
- no `/data/adb`
- no system-property changes
- no Play Integrity spoofing
- no hardware-attestation modification

The fetch flow is based on the current upstream `autopif4.sh` source. Upstream currently checks both Pixel Beta OTA and Factory Image device lists and obtains Canary build information through the Android Flash Tool/Flashstation service. See the upstream source and release notes.

## Build

Open the project in Android Studio, allow Gradle sync, then build `assembleRelease`.

## GitHub Actions

A release workflow is included at `.github/workflows/build.yml`.

## License

Apache-2.0.


## GitHub build
The included GitHub Actions workflow builds a **release variant** only. The initial artifact is unsigned; signing can be added later using GitHub Secrets without putting a `.jks` file in the repository.
