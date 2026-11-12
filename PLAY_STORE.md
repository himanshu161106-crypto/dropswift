# DropSwift – Play Store release checklist

## Must change in the app before upload
- Target Android 16 (API 36). New apps and updates must target API 36 since 31 Aug 2026. Needs a newer Android build plugin; do it in its own build and re-test.
- Remove "All files access" (MANAGE_EXTERNAL_STORAGE). Play only allows it for apps whose core purpose is things like file management, backup, anti-virus, document management, encryption or device migration, and needs a declaration form + review. Plan: name search uses media permissions (photos/videos/music) and the system file picker for other files.
- Release signing key (not the test key in the repo). Keep it private; losing it means no more updates.
- Build an .aab (Play format) instead of .apk.
- 64-bit native library is 16 KB page aligned (checked on build-10: arm64 = 0x4000). OK.

## Needs the owner's account / decisions
- Google Play developer account (one-time fee) in the owner's name.
- App name, icon, screenshots, short + full description.
- Privacy policy page (public URL). Must say: files go phone to phone; internet mode uses public STUN servers and a public note board (ntfy.sh) only to connect, never for file data; no accounts, no analytics.
- Data safety form in Play Console.
- Content rating questionnaire.
- Closed testing: new personal developer accounts must run a closed test with testers for a period before production (check current rule in Play Console).

## Not ready yet
- Transfer keeps running when the screen is off or app is in background (needs a foreground service + notification).
- iPhone native app (needs Apple developer account).
