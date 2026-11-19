# DropSwift – real phone test checklist

Rule: a feature counts as done only after it passes here on real devices.
For every test, send the "Show details" text from every phone.

Install the same build on every phone first. Build 17 and newer do not connect to build 16 or older.

## 1. Multi-device (1 sender, 3 receivers)
- [ ] Sender: Send files → choose 1 big video → "Paas mein - QR dikhao".
- [ ] Receiver 1, 2, 3: Receive files → "Paas mein" → scan the same QR, one after another.
- [ ] All 3 start receiving without touching the sender again.
- [ ] Sender list shows each phone, then "pahunch gayi ✓ 100% match" for each.
- [ ] Note speed per phone (sender "result" lines) and total speed.

## 2. Large files
- [ ] One 5 GB file, 1 receiver: done, "100% sahi (SHA-256 match)".
- [ ] One 10 GB file, 1 receiver: done, "100% sahi". Note time and speed.
- [ ] Open the received video on the receiver: plays to the end.

## 3. Integrity
- [ ] Every receiver says "sab N file 100% sahi (SHA-256 match)".
- [ ] Mixed batch: 30 photos + 2 videos + 1 PDF. Count matches on receiver.
- [ ] Interrupt test: walk away mid-transfer. Receiver must NOT keep a half file; Swifty says "link toot gaya".

## 4. Cross-platform
- [ ] iPhone: tap "iPhone ya laptop" on sender → camera scans Wi-Fi QR → Join → scan link QR → Safari page opens → file downloads → opens from Files app.
- [ ] Windows laptop: join the DIRECT-DS-… Wi-Fi with the shown password → open the shown address in Chrome/Edge → file downloads and opens.
- [ ] Android phone without DropSwift: same as iPhone, using its camera.

## 5. Quality
- [ ] Photo/video on receiver has the same size in bytes as on sender (check file info).
- [ ] Video plays at original resolution.

## 6. Swifty
- [ ] Type "video bhejo", "file leni hai", "door bhejna hai", "speed kam kyun", "iphone pe kaise", a file name. Each gets a sensible action or answer.
- [ ] Turn Wi-Fi off, tap Send/Receive → Swifty says "Phone ki dikkat: Wi-Fi band hai".

## 7. Internet mode
- [ ] One phone on Wi-Fi, other on mobile data: transfer works or Swifty explains "Network ki dikkat".

## 8. Resume, pause, background (build 16)
- [ ] Pause: start a 3–5 GB transfer, press Pause on the receiver at ~40%. Speed shows "Ruka hua". Press Resume: it continues from ~40%, not from 0. Receiver shows "(X pehle se tha, dobara nahi aaya)".
- [ ] Auto resume: during a big transfer walk the receiver away until the link breaks, then come back. Swifty says "Link toot gaya, 3 second mein dobara jod raha hoon (1/5)" and it continues from where it stopped. Final result "100% Original Verified".
- [ ] Give up: walk away and stay away. After 5 tries Swifty says the link broke. `Download/DropSwift` must have NO half file left.
- [ ] Cancel mid-transfer: no half file left in `Download/DropSwift`.
- [ ] Background: start a transfer, lock the screen for 1 minute. Notification shows % and time left. Unlock: transfer still running or done.
- [ ] Background 2: start a transfer, open another app (YouTube) for 1 minute. Transfer keeps going.
- [ ] Back button during a transfer: app goes to background, transfer keeps going.
- [ ] Time left: shows "x min y sec baaki" and roughly matches the real time.
- [ ] Success screen: Kholo opens the file, Share opens the share menu, Done clears the screen.
- [ ] History: after 2–3 transfers, "History" shows them with speed; failed ones show ✗ and the reason; tapping a received one opens the file.
- [ ] Battery under 20% and not charging: Swifty warns at the start.

## 9. Hotspot mode and iPhone → Android
- [ ] Sender hotspot on (5 GHz), receiver joins it. Sender: Send files → "Hotspot ya same Wi-Fi se". Receiver scans. Note speed and the "Network: … link … Mbps" line.
- [ ] iPhone → Android: Android Receive → "iPhone ya laptop se lena". iPhone joins the Wi-Fi QR, opens the second QR, "Is phone ko file bhejo". File appears on Android in the list.

## 10. New in build 17–20
- [ ] Duplicates: send the same file twice to the same phone. Second time: dialog Skip / Replace / Dono rakho. Skip = nothing sent.
- [ ] Save folder: Settings → Files kahan save hon → Apna folder chuno. Receive a file: it lands there.
- [ ] Queue: sender shares 1 file, receiver gets it. Sender taps "+ Aur file jodo", adds 2 files. Receiver gets only those 2 without scanning again.
- [ ] Recent: after one receive, the next Receive shows "↻ <phone> se dobara". Tap it while the sender shows its QR: joins without scanning.
- [ ] Link quality: shows Strong/Medium/Weak during a transfer; changes when you walk away.
- [ ] File types: each picker option opens the right kind of files.
- [ ] Auto mode: works like "Paas mein". Walk far so the link breaks twice: Swifty says it moved to 2.4 GHz and the transfer continues.
- [ ] Folder: send a folder with sub-folders and 50+ files. Receiver has the same folders and file count.
- [ ] Compression ON: send a big .txt/.csv. Log "on the wire" is smaller than the file. Photo/video: no change.
- [ ] Encryption ON: transfer works and is "100% Original Verified". Note the speed vs OFF.
- [ ] Silent mode ON: notification is hidden in the shade, no vibration at the end.
- [ ] UI: home, progress ring, success screen, bottom menu look right; nothing overlaps on small screens.

## 11. Desktop app
- [ ] Windows, phone → laptop: phone Send → "Paas mein" → "iPhone ya laptop". Type the Wi-Fi name and password in "Phone se lo" → "Jodo aur lo". Files arrive in Downloads\DropSwift, verified.
- [ ] Windows, laptop → phone: laptop and phone on the same Wi-Fi/hotspot. "Phone ko bhejo" → files → "Share shuru karo". Phone Receive → "Paas mein" → scan the QR on the laptop screen.
- [ ] Mac: same two tests.
- [ ] Pull the Wi-Fi mid-transfer on the laptop: it continues where it stopped when Wi-Fi returns.
