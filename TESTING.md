# DropSwift – real phone test checklist

Rule: a feature counts as done only after it passes here on real devices.
For every test, send the "Show details" text from every phone.

Install the same build on every phone first.

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
