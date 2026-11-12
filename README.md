# DropSwift

An Android app for sending files phone to phone. Nearby without internet, or far away over the internet.

**Status: test build.** Every feature below says whether it has been tested on real phones. A feature that has not been tested on real phones is not counted as done.

## Download

Latest APK: [Releases](https://github.com/himanshu161106-crypto/dropswift/releases/latest)

Install the same build on both phones.

## How it works

1. **Sender:** Send files → pick files → "Paas mein" (nearby). The phone creates its own direct Wi-Fi link and shows a QR code.
2. **Receiver:** Receive files → "Paas mein" → scan the QR. The files start arriving on their own.
3. Received files are saved in `Download/DropSwift`.

The app's own text is in Hinglish.

## What you need

| | Minimum | For the best speed |
|---|---|---|
| Android version | Android 10 | Any newer version; the version itself does not change speed |
| Wi-Fi | Wi-Fi Direct support (almost every Android phone) | 5 GHz Wi-Fi on **both** phones |
| RAM | Not the limit. The app holds only a few MB of the file in memory at a time | Same |
| Storage | Free space larger than the files you receive | Same |
| Distance | Same room | Within about 1 metre, nothing between the phones |

**What decides the speed is the Wi-Fi chip, and the slower of the two phones sets the limit.** RAM, processor and Android version matter very little. Two phones with the same RAM can differ by 10 times.

## Measured speeds (real phones)

These are the only numbers that have actually been measured. Everything else is an expectation.

| Sender → Receiver | Link | Speed | File |
|---|---|---|---|
| Honor 200 Pro (Android 16) → Xiaomi 22101316I (Android 14) | 5 GHz, 5240 MHz, 4 connections | 94.9 MB/s | 6.14 GB in 66 s |
| Same pair, 1 connection | 5 GHz, 5240 MHz | 77.9 MB/s | 4.15 GB in 55 s |
| Honor 200 Pro → OnePlus CPH2381 (Android 14), OnePlus made the link | 5 GHz, 5785 MHz | 9.4 MB/s | 4.15 GB in 455 s |
| Honor 200 Pro → OnePlus CPH2381, Honor made the link while also on a home router | 5 GHz, 5200 MHz | 16.7 MB/s | 246 MB in 15 s |
| Honor 200 Pro ↔ Xiaomi, long-range mode | 2.4 GHz | 3.3–6 MB/s, works to about 20 m | |

So the honest claim today is: **up to about 95 MB/s between two phones with good 5 GHz Wi-Fi, and much less when one phone's Wi-Fi is weak.** Why the OnePlus pair is slow is not yet settled.

## What to expect on other phones (not measured)

- Both phones with 5 GHz Wi-Fi 5 or Wi-Fi 6: roughly 30–95 MB/s.
- One phone with 2.4 GHz only: roughly 3–10 MB/s.
- Higher than 95 MB/s needs wider channels (160 MHz) or 6 GHz on both phones. Not tested, so no number is promised.

Things that lower speed on any phone:

- The sender is connected to a home router on a different channel. Build 12 tries to make the link on the router's channel (not yet tested).
- Battery saver is on.
- Several receivers at once: the link is shared between them.
- Many small files instead of a few big ones.

## Features

| Feature | What it does | Tested on real phones |
|---|---|---|
| Nearby transfer (Wi-Fi Direct, 5 GHz) | No internet, no router; scan a QR and it sends | Yes |
| 4 connections at once | Blocks of the file travel in parallel | Yes (77.9 → 94.9 MB/s) |
| One QR, many receivers | The sender owns the link; 3 or more phones can scan the same QR | Only with 1 receiver so far. 3 receivers pending |
| Integrity (SHA-256) | Every file is checked on the receiver; a mismatch is deleted | "100% match" seen on 1 file. Big files and a corrupted-file test pending |
| Large files (5 GB, 10 GB) | Sent in blocks, never held whole in memory | Works up to 6.14 GB. 10 GB pending |
| Long-range mode (2.4 GHz) | Slower, reaches a bit further | Yes: about 20 m, 3–6 MB/s |
| Internet mode | Send to a far-away phone with a code; data goes directly phone to phone | Pending |
| iPhone / laptop through the browser | No app needed: join with the Wi-Fi QR, download in the browser | Pending |
| Photo / video picker | Gallery-style picker, or any file | Yes |
| Find a file by name | Type part of the name | Pending |
| Swifty bot | Type what you want ("video bhejo", "file leni hai", "speed kam kyun"). On a problem it says whether the fault is the phone, the network or the app | Partly. Typed Hinglish pending |
| Received files list | Tap a name to open the file | Pending |
| Show details | A log at the bottom: link band, speed, where the time went | Yes |

## What internet mode sends outside

File data never goes to a server. Two public services are used only to connect the two phones: STUN servers (to learn each phone's public address) and ntfy.sh (to hold the two short "how to reach me" notes). On some networks, especially mobile data on both sides, the link cannot be made.

## Not there yet

- Transfer keeps running with the screen off or the app in the background
- Resume from where it stopped when the link breaks
- A native iPhone app
- Play Store release (see `PLAY_STORE.md`)

## Other files

- `TESTING.md`: checklist for real-phone tests
- `PLAY_STORE.md`: work needed before a Play Store release
