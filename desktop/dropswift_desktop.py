"""
DropSwift Desktop (Windows / Mac). Receive files from the DropSwift phone app, or send files to it.
Same protocol as the app (dscore.py).
"""
import os
import platform
import random
import socket
import string
import subprocess
import tempfile
import threading
import time
import tkinter as tk
from tkinter import filedialog, messagebox, ttk
from urllib.parse import urlparse

import dscore as ds

try:
    import qrcode
    from PIL import ImageTk
except Exception:
    qrcode = None

BG, SURFACE, MINT, TEXT, GREY = "#0B0F19", "#151C2E", "#00E68A", "#FFFFFF", "#9AA9BD"
DEVICE = (platform.node() or "Laptop")[:40]


def default_dir():
    return os.path.join(os.path.expanduser("~"), "Downloads", "DropSwift")


def local_ips():
    ips = set()
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("10.255.255.255", 1))
        ips.add(s.getsockname()[0])
        s.close()
    except OSError:
        pass
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ips.add(info[4][0])
    except OSError:
        pass
    good = [ip for ip in ips if ip.startswith(("192.168.", "10.")) or (ip.startswith("172.") and 16 <= int(ip.split(".")[1]) <= 31)]
    return sorted(good)


def join_wifi(ssid, password, log):
    """Connects this laptop to the phone's DropSwift Wi-Fi. Windows: netsh. Mac: networksetup."""
    system = platform.system()
    if system == "Windows":
        xml = f"""<?xml version="1.0"?>
<WLANProfile xmlns="http://www.microsoft.com/networking/WLAN/profile/v1">
 <name>{ssid}</name>
 <SSIDConfig><SSID><name>{ssid}</name></SSID></SSIDConfig>
 <connectionType>ESS</connectionType><connectionMode>manual</connectionMode>
 <MSM><security>
  <authEncryption><authentication>WPA2PSK</authentication><encryption>AES</encryption><useOneX>false</useOneX></authEncryption>
  <sharedKey><keyType>passPhrase</keyType><protected>false</protected><keyMaterial>{password}</keyMaterial></sharedKey>
 </security></MSM>
</WLANProfile>"""
        path = os.path.join(tempfile.gettempdir(), "dropswift_wifi.xml")
        with open(path, "w", encoding="utf-8") as f:
            f.write(xml)
        try:
            subprocess.run(["netsh", "wlan", "add", "profile", f"filename={path}", "user=current"],
                           capture_output=True, timeout=20, creationflags=0x08000000)
            r = subprocess.run(["netsh", "wlan", "connect", f"name={ssid}", f"ssid={ssid}"],
                               capture_output=True, text=True, timeout=20, creationflags=0x08000000)
            log("Wi-Fi: " + (r.stdout or r.stderr).strip()[:120])
        finally:
            try:
                os.remove(path)
            except OSError:
                pass
    elif system == "Darwin":
        for dev in ("en0", "en1"):
            r = subprocess.run(["networksetup", "-setairportnetwork", dev, ssid, password],
                               capture_output=True, text=True, timeout=30)
            if r.returncode == 0 and not r.stdout.strip():
                log("Wi-Fi: joined on " + dev)
                return
        log("Wi-Fi: khud nahi jud paya. Wi-Fi menu se '%s' chuno aur password daalo." % ssid)
    else:
        log("Wi-Fi: is computer pe khud '%s' Wi-Fi se judo." % ssid)


class App:
    def __init__(self, root):
        self.root = root
        root.title("DropSwift Desktop")
        root.configure(bg=BG)
        root.geometry("560x720")
        self.rx = None
        self.host = None
        self.items = []
        self.last_done = 0
        self.last_t = time.time()
        self.qr_img = None

        style = ttk.Style()
        try:
            style.theme_use("clam")
        except tk.TclError:
            pass
        style.configure("TNotebook", background=BG, borderwidth=0)
        style.configure("TNotebook.Tab", background=SURFACE, foreground=TEXT, padding=(16, 8))
        style.map("TNotebook.Tab", background=[("selected", MINT)], foreground=[("selected", "#04130C")])
        style.configure("green.Horizontal.TProgressbar", background=MINT, troughcolor=SURFACE)

        tk.Label(root, text="⚡ DropSwift", fg=MINT, bg=BG, font=("Segoe UI", 22, "bold")).pack(pady=(14, 0))
        tk.Label(root, text="Fast. Secure. Original.", fg=GREY, bg=BG).pack()

        nb = ttk.Notebook(root)
        nb.pack(fill="both", expand=True, padx=14, pady=10)
        self.rx_tab = tk.Frame(nb, bg=BG)
        self.tx_tab = tk.Frame(nb, bg=BG)
        nb.add(self.rx_tab, text="Phone se lo")
        nb.add(self.tx_tab, text="Phone ko bhejo")
        self.build_rx()
        self.build_tx()

        self.speed = tk.Label(root, text="", fg=MINT, bg=BG, font=("Segoe UI", 24, "bold"))
        self.speed.pack()
        self.bar = ttk.Progressbar(root, style="green.Horizontal.TProgressbar", maximum=1000)
        self.bar.pack(fill="x", padx=18)
        self.detail = tk.Label(root, text="", fg=GREY, bg=BG, justify="center")
        self.detail.pack(pady=4)
        self.logbox = tk.Text(root, height=7, bg=SURFACE, fg=GREY, bd=0, wrap="word")
        self.logbox.pack(fill="x", padx=14, pady=(4, 12))
        self.tick()

    # ------------------------------------------------------------------ helpers

    def log(self, msg):
        def add():
            self.logbox.insert("end", msg + "\n")
            self.logbox.see("end")
        self.root.after(0, add)

    def entry(self, parent, label, default="", show=None):
        tk.Label(parent, text=label, fg=GREY, bg=BG, anchor="w").pack(fill="x", padx=6, pady=(10, 2))
        e = tk.Entry(parent, bg=SURFACE, fg=TEXT, insertbackground=TEXT, bd=0, font=("Segoe UI", 12), show=show)
        e.insert(0, default)
        e.pack(fill="x", padx=6, ipady=7)
        return e

    def btn(self, parent, text, cmd, primary=True):
        return tk.Button(parent, text=text, command=cmd, bg=MINT if primary else "#263650",
                         fg="#04130C" if primary else TEXT, activebackground=MINT, bd=0,
                         font=("Segoe UI", 12, "bold"), padx=14, pady=8, cursor="hand2")

    def ask_dup(self, names):
        """Called from the transfer thread; asks on the window thread."""
        ev = threading.Event()
        ans = [0]

        def ask():
            r = messagebox.askyesnocancel(
                "File pehle se hain",
                "%d file pehle se is folder mein hain (same naam aur size):\n%s\n\n"
                "Yes = Skip karo, No = Replace karo, Cancel = Dono rakho"
                % (len(names), "\n".join(names[:5])))
            ans[0] = 0 if r is True else 1 if r is False else 2
            ev.set()

        self.root.after(0, ask)
        ev.wait(25)
        return ans[0]

    # ------------------------------------------------------------------ receive tab

    def build_rx(self):
        f = self.rx_tab
        tk.Label(f, text="Phone pe: Send files → file chuno → 'Paas mein'.\n"
                         "Phir QR ke neeche 'iPhone ya laptop' dabao. Wahan Wi-Fi naam aur password dikhega.\n"
                         "Hotspot / same Wi-Fi mode mein wahan dikha poora link (http://...) daalo.",
                 fg=GREY, bg=BG, justify="left", wraplength=500).pack(fill="x", padx=6, pady=8)
        self.ssid = self.entry(f, "Wi-Fi naam (DIRECT-DS-...)")
        self.secret = self.entry(f, "Password, ya poora link")
        self.save_dir = default_dir()
        self.dir_label = tk.Label(f, text="Save: " + self.save_dir, fg=GREY, bg=BG, anchor="w")
        self.dir_label.pack(fill="x", padx=6, pady=(10, 0))
        row = tk.Frame(f, bg=BG)
        row.pack(fill="x", padx=6, pady=8)
        self.btn(row, "Folder badlo", self.pick_dir, False).pack(side="left")
        self.rx_btn = self.btn(row, "Jodo aur lo", self.start_rx)
        self.rx_btn.pack(side="right")
        self.btn(row, "Rok do", self.stop_rx, False).pack(side="right", padx=6)

    def pick_dir(self):
        d = filedialog.askdirectory()
        if d:
            self.save_dir = d
            self.dir_label.config(text="Save: " + d)

    def start_rx(self):
        if self.rx:
            return
        raw = self.secret.get().strip()
        ssid = self.ssid.get().strip()
        if not raw:
            messagebox.showinfo("DropSwift", "Phone pe dikha password ya link daalo.")
            return
        if raw.startswith("http"):
            u = urlparse(raw)
            host, secret = u.hostname, u.path.strip("/").split("/")[0]
        else:
            host, secret = "192.168.49.1", raw
        os.makedirs(self.save_dir, exist_ok=True)
        self.rx = ds.Receiver(host, secret, self.save_dir, DEVICE, log=self.log, ask_dup=self.ask_dup)
        self.last_done = 0
        self.log("Jud raha hoon %s ..." % host)

        def work():
            if ssid and not raw.startswith("http"):
                join_wifi(ssid, raw, self.log)
                time.sleep(4)
            err = self.rx.run(
                on_batch=lambda n: self.log("Ho gaya! %d file aayi, 100%% Original Verified (SHA-256). %s"
                                            % (n, self.save_dir)),
                on_status=self.log)
            if err:
                self.log("Ruk gaya: %s" % err)
            else:
                self.log("Bhejne wale ne band kar diya. Sab files save hain.")
            self.rx = None

        threading.Thread(target=work, daemon=True).start()

    def stop_rx(self):
        if self.rx:
            self.rx.close()
            self.log("Rok diya. Adhoori file hata di.")

    # ------------------------------------------------------------------ send tab

    def build_tx(self):
        f = self.tx_tab
        tk.Label(f, text="Laptop aur phone ek hi Wi-Fi ya hotspot pe hone chahiye.\n"
                         "Phone pe: Receive files → 'Paas mein' → neeche wala QR scan karo.",
                 fg=GREY, bg=BG, justify="left", wraplength=500).pack(fill="x", padx=6, pady=8)
        row = tk.Frame(f, bg=BG)
        row.pack(fill="x", padx=6)
        self.btn(row, "+ Files", self.add_files, False).pack(side="left")
        self.btn(row, "+ Folder", self.add_folder, False).pack(side="left", padx=6)
        self.files_label = tk.Label(f, text="Koi file nahi chuni", fg=GREY, bg=BG, anchor="w")
        self.files_label.pack(fill="x", padx=6, pady=6)
        self.compress = tk.BooleanVar(value=False)
        self.encrypt = tk.BooleanVar(value=False)
        for text, var in (("Compression (kamzor link pe)", self.compress), ("Encryption (extra suraksha)", self.encrypt)):
            tk.Checkbutton(f, text=text, variable=var, bg=BG, fg=TEXT, selectcolor=SURFACE,
                           activebackground=BG).pack(anchor="w", padx=6)
        self.tx_btn = self.btn(f, "Share shuru karo (QR dikhao)", self.start_tx)
        self.tx_btn.pack(fill="x", padx=6, pady=8)
        self.btn(f, "Band karo", self.stop_tx, False).pack(fill="x", padx=6)
        self.qr = tk.Label(f, bg=BG)
        self.qr.pack(pady=8)
        self.peers_label = tk.Label(f, text="", fg=TEXT, bg=BG, justify="left")
        self.peers_label.pack(fill="x", padx=6)

    def _add(self, paths):
        new = ds.items_for(paths)
        known = {i.path for i in self.items}
        self.items += [i for i in new if i.path not in known]
        self.files_label.config(text="%d file, %s" % (len(self.items), ds.fmt_size(sum(i.size for i in self.items))))
        if self.host:
            self.host.set_items(self.items)
            self.log("Aur file jod di. Jude hue phone ko sirf nayi files jayengi.")

    def add_files(self):
        self._add(list(filedialog.askopenfilenames()))

    def add_folder(self):
        d = filedialog.askdirectory()
        if d:
            self._add([d])

    def start_tx(self):
        if self.host or not self.items:
            if not self.items:
                messagebox.showinfo("DropSwift", "Pehle file ya folder chuno.")
            return
        ips = local_ips()
        if not ips:
            messagebox.showerror("DropSwift", "Laptop kisi Wi-Fi pe nahi hai. Phone ke hotspot ya ghar ke Wi-Fi se judo.")
            return
        secret = "".join(random.choice("abcdefghjkmnpqrstuvwxyz23456789") for _ in range(12))
        try:
            self.host = ds.Host(self.items, secret, DEVICE, compress=self.compress.get(), encrypt=self.encrypt.get(),
                                log=self.log, on_peer=self.on_peer)
            self.host.start()
        except OSError as e:
            self.host = None
            messagebox.showerror("DropSwift", "Port %d nahi khula: %s" % (ds.PORT, e))
            return
        content = "DSLAN1\n%s\n%d\n%s" % (",".join(ips), ds.PORT, secret)
        if qrcode:
            img = qrcode.make(content).resize((260, 260))
            self.qr_img = ImageTk.PhotoImage(img)
            self.qr.config(image=self.qr_img)
        self.log("Share chal raha hai: %s. Windows Firewall pooche toh 'Allow' dabao." % ", ".join(ips))

    def on_peer(self, name, what, p):
        self.log("%s: %s" % (name, "bhej raha hoon" if what == "start" else
                               "pahunch gayi ✓ 100% Original Verified" if what == "ok" else what))

    def stop_tx(self):
        if self.host:
            self.host.close()
            self.host = None
            self.qr.config(image="")
            self.log("Share band.")

    # ------------------------------------------------------------------ progress

    def tick(self):
        now = time.time()
        done, total, line = 0, 0, ""
        if self.rx:
            p = self.rx.p
            done, total = p.done, p.total
            line = ("SHA-256 jaanch..." if p.phase == "verify" else p.file)
        elif self.host:
            running = [x for x in self.host.peers.values() if not x["done"]]
            done = sum(x["p"].done for x in running)
            total = sum(x["p"].total for x in running)
            line = "\n".join("%s: %s" % (x["name"], "✓" if x["ok"] else ("%d%%" % (x["p"].done * 100 // max(1, x["p"].total)) if not x["done"] else "ruka"))
                             for x in self.host.peers.values())
            self.peers_label.config(text=line)
        dt = now - self.last_t
        if dt > 0 and (self.rx or self.host):
            mbps = max(0, done - self.last_done) / 1048576 / dt
            self.speed.config(text="%.1f MB/s" % mbps if total else "")
        self.last_done, self.last_t = done, now
        self.bar["value"] = (done * 1000 // total) if total else 0
        self.detail.config(text=("%s\n%s / %s" % (line, ds.fmt_size(done), ds.fmt_size(total))) if total else "")
        self.root.after(500, self.tick)


if __name__ == "__main__":
    root = tk.Tk()
    App(root)
    root.mainloop()
