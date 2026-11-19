"""Laptop-to-laptop test of the DropSwift v5 protocol on this machine (127.0.0.1)."""
import hashlib, os, random, shutil, sys, tempfile, threading, time
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import dscore as ds

def sha(p):
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""):
            h.update(b)
    return h.hexdigest()

def make_tree(root):
    os.makedirs(os.path.join(root, "Trip", "Day 1"), exist_ok=True)
    files = {
        "big.bin": os.urandom(37 * 1024 * 1024 + 12345),       # not a multiple of 1 MB
        "notes.txt": (b"DropSwift test line\n" * 400000),       # compressible
        "empty.dat": b"",
        "Trip/Day 1/photo.jpg": os.urandom(3 * 1024 * 1024),
        "Trip/हिंदी नाम.txt": "नमस्ते 😀".encode() * 1000,       # non-ASCII name, emoji in content
    }
    for rel, data in files.items():
        with open(os.path.join(root, rel), "wb") as f:
            f.write(data)
    return files

def check_all(src, dst, rels):
    for rel in rels:
        a, b = os.path.join(src, rel), os.path.join(dst, rel)
        assert os.path.isfile(b), "missing " + rel
        assert sha(a) == sha(b), "mismatch " + rel

def run(name, compress, encrypt, break_at=None, port=47900):
    src = tempfile.mkdtemp(); dst = tempfile.mkdtemp()
    files = make_tree(src)
    items = ds.items_for([os.path.join(src, n) for n in ("big.bin", "notes.txt", "empty.dat")] + [os.path.join(src, "Trip")])
    host = ds.Host(items, "secret123", "TestHost", port=port, compress=compress, encrypt=encrypt, log=lambda *a: None)
    host.start()
    rx = ds.Receiver("127.0.0.1", "secret123", dst, port=port, log=lambda *a: None)
    cut = [False]
    if break_at:
        # Cut the link once, from inside the receiver, the moment this many bytes have arrived.
        orig = rx.p.add
        def add(n):
            orig(n)
            if not cut[0] and rx.p.done >= break_at:
                cut[0] = True
                for s in list(rx.socks):
                    try: s.shutdown(2)
                    except Exception: pass
        rx.p.add = add
    batches = []
    t0 = time.time()
    err = rx.run(on_batch=lambda n: batches.append(n), on_status=lambda m: None, wait_for_more=False)
    dt = time.time() - t0
    host.close()
    time.sleep(1)  # let the sender side finish closing its files
    assert err is None, "%s failed: %s" % (name, err)
    if break_at:
        assert cut[0] and rx.p.resumed > 0, "link was not cut or nothing was resumed"
    rels = ["big.bin", "notes.txt", "empty.dat", "Trip/Day 1/photo.jpg", "Trip/हिंदी नाम.txt"]
    check_all(src, dst, rels)
    leftovers = sorted(os.path.relpath(os.path.join(d, f), dst) for d, _, fs in os.walk(dst) for f in fs)
    assert len(leftovers) == len(rels), "extra files: %s" % leftovers
    print("PASS %-34s %d files, resumed %s, %.1fs" % (name, len(rels), ds.fmt_size(rx.p.resumed), dt))
    shutil.rmtree(src); shutil.rmtree(dst)

def run_queue_and_dups(port=47910):
    src = tempfile.mkdtemp(); dst = tempfile.mkdtemp()
    make_tree(src)
    a = ds.items_for([os.path.join(src, "big.bin")])
    host = ds.Host(a, "s", "H", port=port, log=lambda *a: None); host.start()
    rx = ds.Receiver("127.0.0.1", "s", dst, port=port, log=lambda *a: None)
    got = []
    th = threading.Thread(target=lambda: got.append(rx.run(lambda n: got.append(("batch", n)), lambda m: None)), daemon=True)
    th.start()
    while ("batch", 1) not in got: time.sleep(0.05)
    host.set_items(a + ds.items_for([os.path.join(src, "notes.txt")]))     # queue: add a file
    deadline = time.time() + 30
    while len([g for g in got if isinstance(g, tuple)]) < 2 and time.time() < deadline: time.sleep(0.05)
    batches = [g for g in got if isinstance(g, tuple)]
    assert batches == [("batch", 1), ("batch", 1)], "queue sent wrong batches: %s" % batches   # 2nd batch: only the new file
    rx.close(); host.close(); th.join(10)
    print("PASS queue: 2nd batch sent only the new file")
    # duplicates: a new receive of the same file into the same folder, user says skip
    host = ds.Host(a, "s", "H", port=port + 1, log=lambda *a: None); host.start()
    asked = []
    rx2 = ds.Receiver("127.0.0.1", "s", dst, port=port + 1, ask_dup=lambda names: asked.append(names) or 0)
    err = rx2.run(lambda n: None, lambda m: None, wait_for_more=False)
    host.close()
    assert err is None and asked == [["big.bin"]] and rx2.p.saved == [], (err, asked, rx2.p.saved)
    assert not os.path.exists(os.path.join(dst, "big (1).bin"))
    print("PASS duplicates: asked once, skipped, nothing re-sent")
    shutil.rmtree(src); shutil.rmtree(dst)

def run_wrong_secret(port=47920):
    src = tempfile.mkdtemp(); dst = tempfile.mkdtemp(); make_tree(src)
    host = ds.Host(ds.items_for([os.path.join(src, "notes.txt")]), "right", "H", port=port); host.start()
    rx = ds.Receiver("127.0.0.1", "wrong", dst, port=port)
    err = rx.run(lambda n: None, lambda m: None, max_retry=0, wait_for_more=False)
    host.close()
    assert err is not None and not os.listdir(dst), err
    print("PASS wrong password: refused, nothing saved")

def jutf_roundtrip():
    for s in ["abc", "", "नमस्ते", "a\x00b", "😀 emoji", "x" * 1000]:
        b = ds.jutf_encode(s)
        assert ds.jutf_decode(b[2:]) == s, s
    # known Java bytes: writeUTF("\x00") -> 00 02 C0 80 ; writeUTF("😀") -> 00 06 ED A0 BD ED B8 80
    assert ds.jutf_encode("\x00") == bytes([0, 2, 0xC0, 0x80])
    assert ds.jutf_encode("😀") == bytes([0, 6, 0xED, 0xA0, 0xBD, 0xED, 0xB8, 0x80])
    assert ds.bitset_bytes({0, 9}) == bytes([1, 2]) and ds.bitset_from(bytes([1, 2])) == {0, 9}
    print("PASS Java string/BitSet encoding")

jutf_roundtrip()
run("plain", False, False)
run("compression + encryption", True, True, port=47901)
run("link cut at 20 MB, auto resume", False, False, break_at=20 * 1024 * 1024, port=47902)
run("cut + compression + encryption", True, True, break_at=15 * 1024 * 1024, port=47903)
run_queue_and_dups()
run_wrong_secret()
print("ALL PASS")
