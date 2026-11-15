"""YouTube -> MP3 (tkinter + yt-dlp + ffmpeg). Réplica local de y2mate sin anuncios.

Requiere yt-dlp y FFmpeg (instalar_ytmp3.bat los instala con winget).
"""
import ctypes
import os
import queue
import re
import shutil
import subprocess
import sys
import threading
import tkinter as tk
import uuid
from pathlib import Path
from tkinter import messagebox, ttk

CREATE_NO_WINDOW = 0x08000000 if sys.platform == "win32" else 0
PROG = "PROG|"
TITLE = "TITLE|"
DONE = "DONE|"
CONV = "CONV|"


# ---------------------------------------------------------------- utilidades
def music_dir() -> Path:
    """Carpeta Música real de Windows (respeta si está movida a OneDrive, etc.)."""
    if sys.platform == "win32":
        try:
            class GUID(ctypes.Structure):
                _fields_ = [("d1", ctypes.c_uint32), ("d2", ctypes.c_uint16),
                            ("d3", ctypes.c_uint16), ("d4", ctypes.c_ubyte * 8)]
            u = uuid.UUID("{4BD8D571-6D19-48D3-BE97-422220080E43}")  # FOLDERID_Music
            g = GUID(u.fields[0], u.fields[1], u.fields[2],
                     (ctypes.c_ubyte * 8)(*u.bytes[8:]))
            p = ctypes.c_wchar_p()
            if ctypes.windll.shell32.SHGetKnownFolderPath(ctypes.byref(g), 0, None,
                                                          ctypes.byref(p)) == 0:
                path = Path(p.value)
                ctypes.windll.ole32.CoTaskMemFree(p)
                return path
        except Exception:
            pass
    return Path.home() / "Music"


def _refresh_path():
    """Añade al PATH del proceso el PATH de usuario/sistema del registro
    (winget lo modifica, pero los procesos ya abiertos no lo ven)."""
    if sys.platform != "win32":
        return
    try:
        import winreg
        extra = []
        for root, key in ((winreg.HKEY_CURRENT_USER, r"Environment"),
                          (winreg.HKEY_LOCAL_MACHINE,
                           r"SYSTEM\CurrentControlSet\Control\Session Manager\Environment")):
            try:
                with winreg.OpenKey(root, key) as k:
                    extra.append(os.path.expandvars(winreg.QueryValueEx(k, "Path")[0]))
            except OSError:
                pass
        os.environ["PATH"] = os.pathsep.join([os.environ.get("PATH", "")] + extra)
    except Exception:
        pass


def find_exe(name: str) -> str | None:
    found = shutil.which(name)
    if found:
        return found
    pkgs = Path(os.environ.get("LOCALAPPDATA", "")) / "Microsoft" / "WinGet" / "Packages"
    if pkgs.is_dir():
        for hit in pkgs.glob(f"**/{name}.exe"):
            return str(hit)
    return None


def fmt_eta(sec) -> str:
    try:
        sec = int(float(sec))
    except (TypeError, ValueError):
        return "--:--"
    h, rem = divmod(sec, 3600)
    m, s = divmod(rem, 60)
    return f"{h}:{m:02d}:{s:02d}" if h else f"{m:02d}:{s:02d}"


def fmt_speed(bps) -> str:
    try:
        bps = float(bps)
    except (TypeError, ValueError):
        return ""
    for unit in ("B/s", "KB/s", "MB/s", "GB/s"):
        if bps < 1024:
            return f"{bps:.1f} {unit}"
        bps /= 1024
    return f"{bps:.1f} TB/s"


def num(v):
    try:
        return float(v)
    except (TypeError, ValueError):
        return None


# ---------------------------------------------------------------- app
class App(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title("YouTube → MP3")
        self.geometry("680x500")
        self.minsize(520, 420)

        _refresh_path()
        self.ytdlp = find_exe("yt-dlp")
        self.ffmpeg = find_exe("ffmpeg")
        self.dest = music_dir()
        self.q: queue.Queue = queue.Queue()
        self.proc: subprocess.Popen | None = None
        self.cancelled = False
        self.worker: threading.Thread | None = None

        self._build_ui()
        self.after(100, self._poll_queue)
        if not self.ytdlp or not self.ffmpeg:
            missing = [n for n, p in (("yt-dlp", self.ytdlp), ("ffmpeg", self.ffmpeg)) if not p]
            messagebox.showerror("Faltan herramientas",
                                 f"No se encontró: {', '.join(missing)}.\n"
                                 "Ejecuta instalar_ytmp3.bat y vuelve a abrir la app.")

    def _build_ui(self):
        pad = {"padx": 10, "pady": 4}
        ttk.Label(self, text="Pega una o varias URLs de YouTube (una por línea o separadas por espacios):"
                  ).pack(anchor="w", **pad)

        frame = ttk.Frame(self)
        frame.pack(fill="both", expand=True, padx=10)
        self.urls = tk.Text(frame, height=8, wrap="none", font=("Consolas", 10), undo=True)
        sb = ttk.Scrollbar(frame, command=self.urls.yview)
        self.urls.configure(yscrollcommand=sb.set)
        self.urls.pack(side="left", fill="both", expand=True)
        sb.pack(side="right", fill="y")

        opts = ttk.Frame(self)
        opts.pack(fill="x", **pad)
        ttk.Label(opts, text="Calidad:").pack(side="left")
        self.quality = ttk.Combobox(opts, width=12, state="readonly",
                                    values=["Mejor (VBR)", "320 kbps", "192 kbps", "128 kbps"])
        self.quality.current(0)
        self.quality.pack(side="left", padx=(4, 16))
        self.playlist = tk.BooleanVar(value=False)
        ttk.Checkbutton(opts, text="Descargar playlist completa", variable=self.playlist
                        ).pack(side="left")
        ttk.Button(opts, text="Pegar", command=self._paste).pack(side="right")
        ttk.Button(opts, text="Limpiar", command=lambda: self.urls.delete("1.0", "end")
                   ).pack(side="right", padx=4)

        self.lbl_item = ttk.Label(self, text="Listo.")
        self.lbl_item.pack(anchor="w", **pad)
        self.bar_item = ttk.Progressbar(self, maximum=100)
        self.bar_item.pack(fill="x", padx=10)
        self.lbl_eta = ttk.Label(self, text="")
        self.lbl_eta.pack(anchor="w", **pad)

        self.lbl_total = ttk.Label(self, text="Total: 0/0")
        self.lbl_total.pack(anchor="w", padx=10)
        self.bar_total = ttk.Progressbar(self, maximum=100)
        self.bar_total.pack(fill="x", padx=10, pady=(0, 6))

        btns = ttk.Frame(self)
        btns.pack(fill="x", padx=10, pady=8)
        self.btn_go = ttk.Button(btns, text="Descargar MP3", command=self.start)
        self.btn_go.pack(side="left")
        self.btn_cancel = ttk.Button(btns, text="Cancelar", command=self.cancel, state="disabled")
        self.btn_cancel.pack(side="left", padx=6)
        ttk.Button(btns, text="Abrir carpeta Música", command=self.open_dest).pack(side="right")
        ttk.Label(self, text=f"Destino: {self.dest}", foreground="gray").pack(anchor="w", padx=10,
                                                                             pady=(0, 6))

    # ------------------------------------------------------------ acciones
    def _paste(self):
        try:
            self.urls.insert("end", self.clipboard_get().strip() + "\n")
        except tk.TclError:
            pass

    def open_dest(self):
        self.dest.mkdir(parents=True, exist_ok=True)
        if sys.platform == "win32":
            os.startfile(self.dest)
        else:
            subprocess.Popen(["xdg-open", str(self.dest)])

    def start(self):
        if self.worker and self.worker.is_alive():
            return
        if not self.ytdlp or not self.ffmpeg:
            messagebox.showerror("Faltan herramientas", "yt-dlp o ffmpeg no están instalados.")
            return
        urls = [u for u in re.split(r"\s+", self.urls.get("1.0", "end")) if u]
        urls = [u for u in urls if re.match(r"https?://", u)]
        if not urls:
            messagebox.showwarning("Sin URLs", "Pega al menos una URL válida (http/https).")
            return
        self.dest.mkdir(parents=True, exist_ok=True)
        self.cancelled = False
        self.btn_go.configure(state="disabled")
        self.btn_cancel.configure(state="normal")
        q_map = {0: "0", 1: "320K", 2: "192K", 3: "128K"}
        self.worker = threading.Thread(
            target=self._run_all,
            args=(urls, q_map[self.quality.current()], self.playlist.get()),
            daemon=True)
        self.worker.start()

    def cancel(self):
        self.cancelled = True
        if self.proc and self.proc.poll() is None:
            self.proc.terminate()

    # ------------------------------------------------------------ worker
    def _run_all(self, urls, quality, playlist):
        n = len(urls)
        ok, failed = [], []
        for i, url in enumerate(urls):
            if self.cancelled:
                break
            self.q.put(("start", i, n, url))
            rc, files, err = self._run_one(url, quality, playlist, i, n)
            if rc == 0 and not self.cancelled:
                ok.extend(files)
            elif not self.cancelled:
                failed.append((url, err))
            self.q.put(("item_done", i + 1, n))
        self.q.put(("finished", ok, failed, self.cancelled))

    def _run_one(self, url, quality, playlist, i, n):
        tmpl = (f"download:{PROG}%(progress.downloaded_bytes)s|%(progress.total_bytes)s|"
                f"%(progress.total_bytes_estimate)s|%(progress.eta)s|%(progress.speed)s|"
                f"%(info.playlist_index)s|%(info.n_entries)s")
        cmd = [
            self.ytdlp, "-x", "--audio-format", "mp3", "--audio-quality", quality,
            "--embed-thumbnail", "--embed-metadata",
            "--ffmpeg-location", str(Path(self.ffmpeg).parent),
            "--yes-playlist" if playlist else "--no-playlist",
            "--encoding", "utf-8", "--newline", "--progress", "--no-simulate",
            "--progress-template", tmpl,
            "--progress-template", f"postprocess:{CONV}%(progress.postprocessor)s|%(progress.status)s",
            "--print", f"before_dl:{TITLE}%(title)s",
            "--print", f"after_move:{DONE}%(filepath)s",
            "-o", str(self.dest / "%(title)s.%(ext)s"),
            url,
        ]
        env = dict(os.environ, PYTHONIOENCODING="utf-8")
        files, errors = [], []
        try:
            self.proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                         stdin=subprocess.DEVNULL, env=env,
                                         creationflags=CREATE_NO_WINDOW)
        except OSError as e:
            return 1, [], str(e)
        for raw in self.proc.stdout:
            line = raw.decode("utf-8", errors="replace").strip()
            if line.startswith(PROG):
                self.q.put(("progress", i, n, line[len(PROG):].split("|")))
            elif line.startswith(TITLE):
                self.q.put(("title", line[len(TITLE):]))
            elif line.startswith(DONE):
                files.append(line[len(DONE):])
                self.q.put(("converted", line[len(DONE):]))
            elif line.startswith(CONV):
                self.q.put(("converting", line[len(CONV):].split("|")[0]))
            elif "ERROR" in line:
                errors.append(line)
        rc = self.proc.wait()
        return rc, files, "\n".join(errors) or f"código {rc}"

    # ------------------------------------------------------------ UI updates
    def _poll_queue(self):
        try:
            while True:
                self._handle(self.q.get_nowait())
        except queue.Empty:
            pass
        self.after(100, self._poll_queue)

    def _handle(self, msg):
        kind = msg[0]
        if kind == "start":
            _, i, n, url = msg
            self.bar_item["value"] = 0
            self.lbl_item.configure(text=f"Obteniendo info: {url}")
            self.lbl_eta.configure(text="")
            self.lbl_total.configure(text=f"Total: {i}/{n}")
            self.bar_total["value"] = i / n * 100
        elif kind == "title":
            self.lbl_item.configure(text=f"Descargando: {msg[1]}")
        elif kind == "progress":
            _, i, n, f = msg
            f += [None] * (7 - len(f))
            done, total, est, eta, speed, pidx, pn = f[:7]
            done, total = num(done), num(total) or num(est)
            frac = min(done / total, 1.0) if done and total else 0.0
            self.bar_item["value"] = frac * 100
            extra = ""
            if num(pn) and num(pidx):
                extra = f"  ·  playlist {int(num(pidx))}/{int(num(pn))}"
            self.lbl_eta.configure(
                text=f"{frac * 100:5.1f}%   ETA {fmt_eta(eta)}   {fmt_speed(speed)}{extra}")
            self.bar_total["value"] = (i + frac * 0.9) / n * 100
        elif kind == "converting":
            self.bar_item["value"] = 100
            step = {"ExtractAudio": "Convirtiendo a MP3 con ffmpeg…",
                    "EmbedThumbnail": "Incrustando portada…",
                    "Metadata": "Escribiendo etiquetas…"}.get(msg[1] if len(msg) > 1 else "",
                                                           "Procesando con ffmpeg…")
            self.lbl_eta.configure(text=step)
        elif kind == "converted":
            self.lbl_eta.configure(text=f"Guardado: {Path(msg[1]).name}")
        elif kind == "item_done":
            _, i, n = msg
            self.lbl_total.configure(text=f"Total: {i}/{n}")
            self.bar_total["value"] = i / n * 100
        elif kind == "finished":
            _, ok, failed, cancelled = msg
            self.btn_go.configure(state="normal")
            self.btn_cancel.configure(state="disabled")
            self.proc = None
            if cancelled:
                self.lbl_item.configure(text="Cancelado.")
                self.lbl_eta.configure(text="")
                return
            self.lbl_item.configure(text=f"Terminado: {len(ok)} MP3, {len(failed)} con error.")
            if failed:
                detail = "\n\n".join(f"{u}\n{e[-400:]}" for u, e in failed)
                messagebox.showerror("Algunas descargas fallaron", detail)


if __name__ == "__main__":
    App().mainloop()
