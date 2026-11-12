# DropSwift

Phone se phone file bhejne ka Android app. Paas baith ke bina internet ke, ya door se internet ke raaste.

**Status: test build.** Neeche har feature ke saamne likha hai ki woh asli phone pe test hua hai ya nahi. Jo test nahi hua, use "done" nahi maana gaya.

## Download

Sabse naya APK: [Releases](https://github.com/himanshu161106-crypto/dropswift/releases/latest)

Android 10 ya upar chahiye. Dono phone pe same build rakho.

## Kaise chalta hai

1. **Bhejne wala:** Send files → file chuno → "Paas mein". Phone apna seedha Wi-Fi link banata hai aur QR dikhata hai.
2. **Lene wala:** Receive files → "Paas mein" → QR scan karo. File apne-aap aane lagti hai.
3. Aayi hui files `Download/DropSwift` folder mein milti hain.

## Features

| Feature | Kya karta hai | Asli phone pe test |
|---|---|---|
| Paas mein bhejna (Wi-Fi Direct, 5 GHz) | Bina internet, bina router, QR scan karke seedha transfer | Haan. Honor 200 Pro → Xiaomi: ~95 MB/s |
| 4 connection ek saath | File ke tukde 4 raaston se saath jaate hain | Haan (77.9 → 94.9 MB/s) |
| Ek QR, kai lene wale | Bhejne wala link banata hai; 3 ya zyada phone wahi QR scan kar sakte hain | Abhi sirf 1 phone ke saath. 3 phone wala test baaki |
| Integrity (SHA-256) | Har file ki jaanch hoti hai; match na ho toh file hata di jaati hai | 1 file pe "100% match" aaya. Badi files aur kharab-file wala test baaki |
| Badi files (5 GB, 10 GB) | File tukdon mein jaati hai, memory mein poori nahi rakhi jaati | 6.14 GB tak chala. 10 GB baaki |
| Door tak mode (2.4 GHz) | Dheema, par thodi zyada doori | Haan: ~20 m tak, 3–6 MB/s |
| Internet mode | Door ke phone ko code se bhejna; file seedhi phone se phone jaati hai | Baaki |
| iPhone / laptop (browser se) | App ke bina: Wi-Fi QR se judo, browser mein file download karo | Baaki |
| Photo / video picker | Gallery jaisa picker, ya koi bhi file | Haan |
| Naam se file dhoondna | Naam ka hissa likho, file mil jaati hai | Baaki |
| Swifty bot | Likh ke bolo ("video bhejo", "file leni hai", "speed kam kyun"); dikkat pe batata hai ki galti phone ki hai, network ki, ya app ki | Thoda. Hinglish samajhna baaki |
| Aayi hui files ki list | Naam pe dabao toh file khul jaati hai | Baaki |
| Show details | Neeche log dikhta hai: link ka band, speed, kahan waqt gaya | Haan |

## Speed ke baare mein

- Speed dono phone ke Wi-Fi pe depend karti hai; dheema phone had tay karta hai.
- Honor 200 Pro + Xiaomi: ~95 MB/s. Honor + OnePlus CPH2381: 9–17 MB/s.
- Bhejne wala phone ghar ke router se juda ho toh speed gir sakti hai. Build 12 se link router ke channel pe banane ki koshish hoti hai (test baaki).
- Ek saath kai phone lenge toh speed un sab mein bantegi.

## Internet mode mein kya bahar jaata hai

File ka data kisi server pe nahi jaata. Sirf do phone ko milane ke liye do public cheezein use hoti hain: STUN server (phone ka internet pata jaanne ke liye) aur ntfy.sh (dono phone ka chhota "mujh tak aise pahuncho" note rakhne ke liye). Kuch network pe, khaaskar dono taraf mobile data ho, link nahi banta.

## Abhi jo nahi hai

- Screen band hone ya app badalne pe transfer chalta rahe
- Link toote toh wahin se dobara shuru
- iPhone ka apna app
- Play Store release (dekho `PLAY_STORE.md`)

## Aur files

- `TESTING.md`: asli phone pe test karne ki checklist
- `PLAY_STORE.md`: Play Store pe daalne se pehle ke kaam
