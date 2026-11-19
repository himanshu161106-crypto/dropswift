"""
DropSwift protocol v5 for laptops (Windows / Mac / Linux). Same wire format as the Android app
(see Transfer.kt). No GUI here, so it can be tested on its own.
"""
import hashlib
import os
import queue
import random
import socket
import struct
import threading
import time
import zlib

try:
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
except Exception:  # encryption simply unavailable
    AESGCM = None

PORT = 47800
STREAMS = 4
MAGIC = 0x44535735
BLOCK = 1 << 20
WIRE_MAX = BLOCK + BLOCK // 8 + 256
SOCK_BUF = 2 << 20

REC_BLOCK, REC_FILE_END, REC_STREAM_END = 1, 2, 3
FLAG_DEFLATE, FLAG_AES = 1, 2
HAVE_NONE, HAVE_ALL, HAVE_SOME = 0, 1, 2
ACK_OK, ACK_BAD = 1, 2

PACKED = {
    "jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "avif", "mp4", "mkv", "mov", "avi", "webm", "3gp",
    "mp3", "aac", "m4a", "ogg", "opus", "flac", "zip", "rar", "7z", "gz", "xz", "bz2", "apk", "apks", "xapk",
    "jar", "docx", "xlsx", "pptx", "odt", "epub",
}


def kill(s):
    """shutdown() first: close() alone does nothing while a makefile() still refers to the socket."""
    try:
        s.shutdown(socket.SHUT_RDWR)
    except Exception:
        pass
    try:
        s.close()
    except Exception:
        pass


class Stop(Exception):
    pass


class Corrupt(IOError):
    pass


# ----------------------------------------------------------------- Java data I/O

def jutf_encode(s):
    """Java DataOutput.writeUTF (modified UTF-8 with a 2-byte length)."""
    out = bytearray()
    units = s.encode("utf-16-be", "surrogatepass")
    for i in range(0, len(units), 2):
        c = (units[i] << 8) | units[i + 1]
        if 0x01 <= c <= 0x7F:
            out.append(c)
        elif c <= 0x7FF:
            out += bytes((0xC0 | (c >> 6), 0x80 | (c & 0x3F)))
        else:
            out += bytes((0xE0 | (c >> 12), 0x80 | ((c >> 6) & 0x3F), 0x80 | (c & 0x3F)))
    if len(out) > 65535:
        raise ValueError("string too long")
    return struct.pack(">H", len(out)) + bytes(out)


def jutf_decode(b):
    units = bytearray()
    i = 0
    while i < len(b):
        x = b[i]
        if x < 0x80:
            c = x
            i += 1
        elif (x & 0xE0) == 0xC0:
            c = ((x & 0x1F) << 6) | (b[i + 1] & 0x3F)
            i += 2
        else:
            c = ((x & 0x0F) << 12) | ((b[i + 1] & 0x3F) << 6) | (b[i + 2] & 0x3F)
            i += 3
        units += struct.pack(">H", c)
    return units.decode("utf-16-be", "surrogatepass").encode("utf-16", "surrogatepass").decode("utf-16")


class Reader:
    """Buffered big-endian reader on a socket."""

    def __init__(self, sock):
        self.f = sock.makefile("rb", buffering=256 * 1024)

    def exact(self, n):
        b = self.f.read(n)
        if b is None or len(b) < n:
            raise EOFError("link closed")
        return b

    def u8(self):
        return self.exact(1)[0]

    def i32(self):
        return struct.unpack(">i", self.exact(4))[0]

    def i64(self):
        return struct.unpack(">q", self.exact(8))[0]

    def utf(self):
        n = struct.unpack(">H", self.exact(2))[0]
        return jutf_decode(self.exact(n))


def i32(v):
    return struct.pack(">i", v)


def i64(v):
    return struct.pack(">q", v)


def bitset_bytes(bits):
    """java.util.BitSet.toByteArray(): little-endian bits, no trailing zero bytes."""
    if not bits:
        return b""
    out = bytearray(max(bits) // 8 + 1)
    for b in bits:
        out[b // 8] |= 1 << (b % 8)
    while out and out[-1] == 0:
        out.pop()
    return bytes(out)


def bitset_from(data):
    return {i * 8 + j for i, byte in enumerate(data) for j in range(8) if byte >> j & 1}


def aes_key(secret, transfer_id):
    return hashlib.sha256(("DropSwift5:%s:%d" % (secret, transfer_id)).encode("utf-8")).digest()


def nonce(file_index, offset):
    return struct.pack(">iq", file_index, offset)


def clean_path(raw):
    parts = [p.replace("\x00", "").strip()[:120] for p in raw.replace("\\", "/").split("/")]
    parts = [p for p in parts if p and p not in (".", "..")]
    if not parts:
        return "file"
    return "/".join(parts[:-1][-8:] + [parts[-1]])


def fmt_size(b):
    if b < 1024:
        return "%d B" % b
    v = b / 1024.0
    for u in ("KB", "MB", "GB", "TB"):
        if v < 1024 or u == "TB":
            return "%.2f %s" % (v, u)
        v /= 1024


class Progress:
    def __init__(self):
        self.lock = threading.Lock()
        self.total = 0
        self.done = 0
        self.resumed = 0
        self.file = ""
        self.phase = ""
        self.verified = 0
        self.saved = []

    def add(self, n):
        with self.lock:
            self.done += n


# ---------------------------------------------------------------------- receive

class ResumeState:
    def __init__(self, tid, names, sizes):
        self.tid, self.names, self.sizes = tid, names, sizes
        self.files = [dict(path=None, blocks=set(), expected=-1, hash=None, complete=False, verified=False,
                           skipped=False, lock=threading.Lock()) for _ in names]


class Receiver:
    """Joins a sender, receives, continues after a broken link (same id), checks SHA-256."""

    def __init__(self, host, secret, save_dir, device="Laptop", port=PORT, log=print, ask_dup=None):
        self.host, self.secret, self.save_dir, self.port = host, secret, save_dir, port
        self.device = device
        self.id = random.getrandbits(63)
        self.state = None
        self.received = {}
        self.sender_name = ""
        self.log = log
        self.ask_dup = ask_dup or (lambda names: 0)
        self.p = Progress()
        self.stop = False
        self.socks = []

    def close(self):
        self.stop = True
        for s in list(self.socks):
            kill(s)

    def join(self):
        socks = []
        for i in range(STREAMS):
            last = None
            for _ in range(40 if i == 0 else 5):
                if self.stop:
                    raise Stop()
                try:
                    s = socket.create_connection((self.host, self.port), timeout=2)
                    s.settimeout(None)
                    s.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, SOCK_BUF)
                    break
                except OSError as e:
                    last = e
                    time.sleep(0.5)
            else:
                raise IOError("sender not reachable (%s)" % last)
            s.sendall(i32(MAGIC) + jutf_encode(self.secret) + i64(self.id) + i32(i) + i32(STREAMS) +
                      jutf_encode(self.device[:60]))
            socks.append(s)
        self.socks = socks
        return socks

    def _target(self, rel):
        path = os.path.join(self.save_dir, *rel.split("/"))
        os.makedirs(os.path.dirname(path), exist_ok=True)
        return path

    def _unique(self, path):
        if not os.path.exists(path):
            return path
        root, ext = os.path.splitext(path)
        n = 1
        while os.path.exists("%s (%d)%s" % (root, n, ext)):
            n += 1
        return "%s (%d)%s" % (root, n, ext)

    def receive_once(self, socks, on_new_data=lambda: None):
        """One batch. Returns number of new files."""
        p = self.p
        rd = [Reader(s) for s in socks]
        c = rd[0]
        if c.i32() != MAGIC:
            raise IOError("Other phone has a different DropSwift version. Update both")
        tid = c.i64()
        self.sender_name = c.utf()
        count = c.i32()
        p.total = c.i64()
        names, sizes = [], []
        for _ in range(count):
            names.append(clean_path(c.utf()))
            sizes.append(c.i64())
        st = self.state
        if st is None or (st.tid, st.names, st.sizes) != (tid, names, sizes):
            self.discard()
            st = self.state = ResumeState(tid, names, sizes)
            dups = []
            for i, f in enumerate(st.files):
                key = "%s|%d" % (names[i], sizes[i])
                if key in self.received:
                    f.update(path=self.received[key], verified=True, skipped=True)
                    continue
                target = os.path.join(self.save_dir, *names[i].split("/"))
                if os.path.isfile(target) and os.path.getsize(target) == sizes[i]:
                    dups.append((i, target))
            if dups:
                choice = self.ask_dup([names[i] for i, _ in dups])
                for i, t in dups:
                    if choice == 0:
                        st.files[i].update(path=t, verified=True, skipped=True)
                    elif choice == 1:
                        try:
                            os.remove(t)
                        except OSError:
                            pass
        reply = bytearray()
        for f in st.files:
            if f["verified"]:
                reply.append(HAVE_ALL)
            elif f["path"] and f["blocks"]:
                bb = bitset_bytes(f["blocks"])
                reply += bytes((HAVE_SOME,)) + i32(len(bb)) + bb
            else:
                reply.append(HAVE_NONE)
            if not f["verified"]:
                f["complete"] = False
        socks[0].sendall(bytes(reply))
        already = sum(sizes[i] if f["verified"] else min(len(f["blocks"]) * BLOCK, sizes[i])
                      for i, f in enumerate(st.files))
        p.resumed = p.done = already
        p.phase = ""
        p.verified = 0
        if any(not f["verified"] for f in st.files):
            on_new_data()
        key = aes_key(self.secret, tid)
        errors = []

        def open_file(i):
            f = st.files[i]
            with f["lock"]:
                if f["path"] is None:
                    f["path"] = self._unique(self._target(names[i]))
                    open(f["path"], "wb").close()
                return f["path"]

        def check(i):
            f = st.files[i]
            if f["complete"] or f["expected"] < 0:
                return
            need = (f["expected"] + BLOCK - 1) // BLOCK
            if all(b in f["blocks"] for b in range(need)):
                f["complete"] = True

        def worker(r):
            gcm = AESGCM(key) if AESGCM else None
            try:
                while True:
                    kind = r.u8()
                    if kind == REC_STREAM_END:
                        return
                    if kind == REC_FILE_END:
                        fi, length, h = r.i32(), r.i64(), r.exact(32)
                        if not 0 <= fi < count:
                            raise IOError("Bad file record")
                        path = open_file(fi)
                        with st.files[fi]["lock"]:
                            st.files[fi].update(expected=length, hash=h)
                            if length == 0:
                                open(path, "ab").close()
                            check(fi)
                        continue
                    if kind != REC_BLOCK:
                        raise IOError("Bad record")
                    fi, off, raw_len, flags, wire_len = r.i32(), r.i64(), r.i32(), r.u8(), r.i32()
                    if not (0 <= fi < count and off >= 0 and off % BLOCK == 0 and 0 < raw_len <= BLOCK
                            and 0 < wire_len <= WIRE_MAX + 32):
                        raise IOError("Bad data block")
                    data = r.exact(wire_len)
                    if flags & FLAG_AES:
                        if gcm is None:
                            raise IOError("encryption library missing")
                        try:
                            data = gcm.decrypt(nonce(fi, off), data, None)
                        except Exception:
                            raise IOError("integrity failed: encrypted block did not open")
                    if flags & FLAG_DEFLATE:
                        data = zlib.decompress(data)
                    if len(data) != raw_len:
                        raise IOError("Bad data block")
                    path = open_file(fi)
                    with open(path, "r+b") as fh:
                        fh.seek(off)
                        fh.write(data)
                    p.file = names[fi]
                    p.add(raw_len)
                    with st.files[fi]["lock"]:
                        st.files[fi]["blocks"].add(off // BLOCK)
                        check(fi)
            except Exception as e:
                errors.append(e)
                for s in socks:
                    kill(s)

        ts = [threading.Thread(target=worker, args=(r,), daemon=True) for r in rd]
        for t in ts:
            t.start()
        for t in ts:
            t.join()
        if errors:
            raise IOError(str(errors[0]))
        if any(not f["verified"] and not f["complete"] for f in st.files):
            raise IOError("Some files did not arrive completely")

        p.phase = "verify"
        bad, new = [], 0
        for i, f in enumerate(st.files):
            if f["verified"] or not f["path"]:
                continue
            h = hashlib.sha256()
            n = 0
            with open(f["path"], "rb") as fh:
                while True:
                    b = fh.read(BLOCK)
                    if not b:
                        break
                    h.update(b)
                    n += len(b)
            if n == f["expected"] and h.digest() == f["hash"]:
                f["verified"] = True
                new += 1
                p.verified += 1
            else:
                bad.append(names[i])
                try:
                    os.remove(f["path"])
                except OSError:
                    pass
                f.update(path=None, blocks=set(), complete=False, expected=-1)
        p.saved = [f["path"] for f in st.files if f["verified"] and not f["skipped"]]
        if bad:
            try:
                socks[0].sendall(bytes((ACK_BAD,)))
            except OSError:
                pass
            raise Corrupt("integrity failed: " + ", ".join(bad)[:200])
        socks[0].sendall(bytes((ACK_OK,)))
        for i, f in enumerate(st.files):
            if f["verified"] and f["path"]:
                self.received["%s|%d" % (names[i], sizes[i])] = f["path"]
        try:
            socks[0].settimeout(10)
            while socks[0].recv(1):
                pass
        except OSError:
            pass
        for s in socks:
            kill(s)
        self.state = None
        return new

    def discard(self):
        """No half files left behind."""
        st = self.state
        if not st:
            return
        for f in st.files:
            if not f["verified"] and f["path"]:
                try:
                    os.remove(f["path"])
                except OSError:
                    pass
        self.state = None

    def run(self, on_batch, on_status, max_retry=5, wait_for_more=True):
        """Receive batches until the sender stops. Returns None, or the error that ended it."""
        tries, started, waiting, batches, poll_fails = 0, False, False, 0, 0
        while not self.stop:
            try:
                socks = self.join()
                poll_fails = 0
                if started and not waiting and tries:
                    on_status("Wapas jud gaya. Jahan ruka tha wahin se le raha hoon...")
                started = True

                def new_data():
                    nonlocal waiting
                    if waiting:
                        waiting = False
                        on_status("Nayi files aa rahi hain...")

                n = self.receive_once(socks, new_data)
                tries = 0
                if n > 0 or batches == 0:
                    batches += 1
                    on_batch(n)
                if not wait_for_more:
                    return None
                waiting = True
                time.sleep(4)
            except Stop:
                break
            except Exception as e:
                for s in self.socks:
                    kill(s)
                if self.stop:
                    break
                if waiting:
                    poll_fails += 1
                    if poll_fails >= 4:
                        return None
                    time.sleep(3)
                    continue
                msg = str(e).lower()
                fatal = "different dropswift version" in msg or "wrong secret" in msg
                if not started or fatal or tries >= max_retry:
                    self.discard()
                    return e
                tries += 1
                on_status("Link toot gaya. 3 second mein dobara jod raha hoon... (%d/%d)" % (tries, max_retry))
                time.sleep(3)
        self.discard()
        return None


# ------------------------------------------------------------------------- send

class Item:
    def __init__(self, path, name):
        self.path, self.name = path, name
        self.size = os.path.getsize(path)


def items_for(paths):
    """Files and folders to Items; folder contents keep their folder path."""
    out = []
    for p in paths:
        if os.path.isdir(p):
            base = os.path.basename(os.path.normpath(p)) or "Folder"
            for dp, _, fs in os.walk(p):
                for fn in sorted(fs):
                    full = os.path.join(dp, fn)
                    rel = os.path.relpath(full, p).replace(os.sep, "/")
                    out.append(Item(full, base + "/" + rel))
        elif os.path.isfile(p):
            out.append(Item(p, os.path.basename(p)))
    return out


def send(socks, items, transfer_id, sender_name, secret, compress, encrypt, p, on_plan=lambda n: None):
    """Sends one batch to one receiver over its connections. Raises on failure."""
    p.total = sum(it.size for it in items)
    hdr = i32(MAGIC) + i64(transfer_id) + jutf_encode(sender_name[:60]) + i32(len(items)) + i64(p.total)
    for it in items:
        hdr += jutf_encode(it.name) + i64(it.size)
    socks[0].sendall(hdr)
    c = Reader(socks[0])
    socks[0].settimeout(90)
    have_all, have_blocks, skipped = [False] * len(items), [None] * len(items), 0
    for i, it in enumerate(items):
        k = c.u8()
        if k == HAVE_ALL:
            have_all[i] = True
            skipped += it.size
        elif k == HAVE_SOME:
            n = c.i32()
            bits = bitset_from(c.exact(n))
            have_blocks[i] = bits
            skipped += min(len(bits) * BLOCK, it.size)
        elif k != HAVE_NONE:
            raise IOError("Bad resume record")
    socks[0].settimeout(None)
    p.resumed = p.done = skipped
    on_plan(max(0, p.total - skipped))
    key = aes_key(secret, transfer_id) if encrypt else None
    if encrypt and AESGCM is None:
        raise IOError("encryption library missing")

    q = queue.Queue(maxsize=len(socks) * 3 + 2)
    errors = []
    END = object()

    def put(item):
        # Never block forever: if the senders stopped (link broke), give up and close the file.
        while not errors:
            try:
                q.put(item, timeout=0.3)
                return True
            except queue.Full:
                pass
        return False

    def reader():
        try:
            for idx, it in enumerate(items):
                if have_all[idx]:
                    continue
                p.file = it.name
                skip = have_blocks[idx] or set()
                h = hashlib.sha256()
                off, bno = 0, 0
                with open(it.path, "rb") as fh:
                    while True:
                        if errors:
                            return
                        b = fh.read(BLOCK)
                        if not b:
                            break
                        h.update(b)
                        if bno not in skip and not put(("b", idx, off, b)):
                            return
                        off += len(b)
                        bno += 1
                if not put(("e", idx, off, h.digest())):
                    return
            put(END)
        except Exception as e:
            errors.append(e)

    def sender(s):
        gcm = AESGCM(key) if key else None
        try:
            while True:
                try:
                    r = q.get(timeout=0.3)
                except queue.Empty:
                    if errors:
                        return
                    continue
                if r is END:
                    q.put(END)
                    s.sendall(bytes((REC_STREAM_END,)))
                    return
                if errors:
                    return
                kind, idx, off, data = r
                if kind == "e":
                    s.sendall(bytes((REC_FILE_END,)) + i32(idx) + i64(off) + data)
                    continue
                wire, flags = data, 0
                if compress and items[idx].name.rsplit(".", 1)[-1].lower() not in PACKED:
                    z = zlib.compress(data, 1)
                    if len(z) < len(data) - len(data) // 20:
                        wire, flags = z, flags | FLAG_DEFLATE
                if gcm:
                    wire = gcm.encrypt(nonce(idx, off), wire, None)
                    flags |= FLAG_AES
                s.sendall(bytes((REC_BLOCK,)) + i32(idx) + i64(off) + i32(len(data)) + bytes((flags,)) +
                          i32(len(wire)) + wire)
                p.add(len(data))
        except Exception as e:
            errors.append(e)
            for x in socks:
                kill(x)

    ts = [threading.Thread(target=reader, daemon=True)] + [threading.Thread(target=sender, args=(s,), daemon=True) for s in socks]
    for t in ts:
        t.start()
    for t in ts:
        t.join()
    if errors:
        raise IOError(str(errors[0]))
    socks[0].settimeout(15 * 60)
    try:
        ack = c.exact(1)
    except (OSError, EOFError) as e:
        raise IOError("not confirmed: %s" % e)
    for s in socks:
        kill(s)
    if ack == bytes((ACK_BAD,)):
        raise Corrupt("integrity failed: receiver found a mismatch")
    if ack != bytes((ACK_OK,)):
        raise IOError("not confirmed: no answer")


class Host:
    """Laptop shares files: any number of phones join with the secret (QR) and receive."""

    def __init__(self, items, secret, name="Laptop", port=PORT, compress=False, encrypt=False, log=print,
                 on_peer=lambda *a: None):
        self.secret, self.name, self.port = secret, name, port
        self.compress, self.encrypt = compress, encrypt
        self.log = log
        self.on_peer = on_peer
        self.set_items(items)
        self.srv = None
        self.peers = {}
        self.lock = threading.Lock()

    def set_items(self, items):
        self.batch = (list(items), random.getrandbits(63))

    def start(self):
        self.srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.srv.bind(("0.0.0.0", self.port))
        self.srv.listen(64)
        threading.Thread(target=self._accept, daemon=True).start()

    def close(self):
        if self.srv:
            kill(self.srv)

    def _accept(self):
        pending = {}
        while True:
            try:
                s, _ = self.srv.accept()
            except OSError:
                return
            threading.Thread(target=self._hello, args=(s, pending), daemon=True).start()

    def _hello(self, s, pending):
        try:
            s.settimeout(10)
            r = Reader(s)
            if r.i32() != MAGIC or r.utf() != self.secret:
                raise IOError("bad hello")
            rid, idx, cnt, name = r.i64(), r.i32(), r.i32(), r.utf()
            s.settimeout(None)
            s.setsockopt(socket.SOL_SOCKET, socket.SO_SNDBUF, SOCK_BUF)
            # the reader may have buffered nothing beyond the hello: the receiver waits for us now
            ready = None
            with self.lock:
                ent = pending.setdefault(rid, [name, [None] * cnt, time.time()])
                if len(ent[1]) == cnt and ent[1][idx] is None:
                    ent[1][idx] = s
                    if all(x is not None for x in ent[1]):
                        ready = pending.pop(rid)
                else:
                    s.close()
            if ready:
                self._serve(rid, ready[0], ready[1])
        except Exception:
            kill(s)

    def _serve(self, rid, name, socks):
        items, tid = self.batch
        p = Progress()
        was_ok = self.peers.get(rid, {}).get("ok", False)
        real = [False]

        def plan(n):
            if n > 0 or not was_ok:
                real[0] = True
                self.peers[rid] = dict(name=name, p=p, ok=False, done=False)
                self.on_peer(name, "start", p)

        try:
            send(socks, items, tid, self.name, self.secret, self.compress, self.encrypt, p, plan)
            ok, why = True, ""
        except Exception as e:
            ok, why = False, str(e)
        if real[0]:
            self.peers[rid] = dict(name=name, p=p, ok=ok, done=True)
            self.on_peer(name, "ok" if ok else "fail: " + why, p)
