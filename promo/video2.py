"""Anuncio de MegaDown en tono burlón (vertical 1080x1920, 30 fps).

Reutiliza las utilidades de video.py (fuentes, animaciones, música y efectos sintetizados).
Los anuncios de "página pirata" son recreaciones paródicas, no capturas de anuncios reales.
Uso:  python voz2.py  &&  python video2.py   (o "python video2.py frames" para vistas previas)
"""
import json
import math
import subprocess
import sys

from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

sys.path.insert(0, str(Path(__file__).resolve().parent))
import video as V   # utilidades compartidas
from video import (W, H, FPS, SR, BUILD, SHOTS, fnt, clamp, lerp, ease_out, ease_back, hexc,
                   draw_center, text_size, wrap, paste_scaled, rounded, phone, make_popup, chip,
                   notes, gradient)

QR_URL_TEXT = "github.com/superprinx2/YT-MP3"

# ---------------------------------------------------------------- tiempos
voz = json.loads((BUILD / "voz2" / "voz.json").read_text(encoding="utf-8"))
GAP = 0.28
t = 0.4
SCENES = []
for v in voz:
    SCENES.append({"key": v["key"], "start": t, "dur": v["dur"], "file": v["file"]})
    t += v["dur"] + GAP
TOTAL = t + 2.4
for i, s in enumerate(SCENES):
    s["end"] = SCENES[i + 1]["start"] if i + 1 < len(SCENES) else TOTAL
SC = {s["key"]: s for s in SCENES}

CAPTIONS = {
    "piratas": "¿Sigues bajando música de páginas piratas?",
    "virus": "Descargas una canción… y te llevas 5 virus, 1 ransomware… ¡y 2 testigos de Jehová tocándote la puerta!",
    "tranqui": "¡Tranquilo, compadre! Llegó MegaDown.",
    "plats": "Aquí descargas de cualquier plataforma: YouTube, TikTok, Instagram, Facebook… ¡y Spotify!",
    "spotify": "¿Aburrido de bajar canción por canción de Spotify? Usa el modo playlist y bájate toda tu música de un jalón.",
    "ultra": "En velocidad Ultra: ¡hasta 8 canciones a la vez! Ahorras un montón de tiempo.",
    "gratis": "Y lo mejor… ¡es gratis! Ni tu ex se había regalado tanto.",
    "qr": "Escanea el código y descarga la app de una vez.",
    "cierre": "Deja de depender del internet o de pagar Spotify para escuchar las mismas 10 canciones aburridas de siempre. MegaDown: ¡bájalo todo!",
}


def scene_at(tt):
    for s in SCENES:
        if tt < s["end"]:
            return s
    return SCENES[-1]


# ---------------------------------------------------------------- recursos
def shot(n):
    return Image.open(SHOTS / f"{n}.png").convert("RGBA")


SH = {n: shot(n) for n in ["md_home", "md_spotify_top", "md_spotify_list", "md_ultra"]}
PH = {n: phone(img) for n, img in SH.items()}
HERO = rounded(SH["md_home"].crop((42, 160, 1038, 622)), 40)
ULTRA_CARD = rounded(SH["md_ultra"].crop((30, 1590, 1050, 2010)), 36)

qr = Image.open(BUILD / "qr_megadown.png").convert("RGBA")
QR = Image.new("RGBA", (760, 920), (0, 0, 0, 0))
_d = ImageDraw.Draw(QR)
_d.rounded_rectangle((0, 0, 759, 919), 48, fill=(255, 255, 255, 255))
QR.alpha_composite(qr.resize((620, 620), Image.NEAREST), (70, 70))
draw_center(QR, "Escanea y descarga", 712, fnt("bold", 52), fill=(20, 20, 40, 255), shadow=False, x=380)
draw_center(QR, QR_URL_TEXT, 790, fnt("semibold", 36), fill=(90, 90, 120, 255), shadow=False, x=380)

# Anuncios paródicos (al estilo de las páginas piratas; sin marcas ni anuncios reales)
ADS = [
    ("#FF3B30", "¡5 VIRUS!", "Tu celular está infectado", "ELIMINAR"),        # 0  "cinco virus"
    ("#1C1C1E", "RANSOMWARE", "Paga 0.5 BTC por tus fotos", "PAGAR"),        # 1  "un ransomware"
    ("#5856D6", "¡TOC TOC!", "2 Testigos de Jehová en tu puerta", "ABRIR"),  # 2  "testigos"
    ("#FFCC00", "¡GANASTE!", "Un celular nuevecito", "RECLAMAR"),            # 3
    ("#34C759", "DESCARGAR", "(este tampoco es el botón)", "DESCARGAR"),    # 4
    ("#FF9500", "Tu RAM está llena", "Instala este limpiador", "INSTALAR"),  # 5
    ("#007AFF", "Espera 30 s…", "Tu descarga empezará pronto", "SALTAR"),    # 6
    ("#FF2D55", "¿Ya te vas?", "Mira estos 10 trucos", "VER"),               # 7
]
POPS = [make_popup(*a) for a in ADS]
POS = [(380, 560), (700, 820), (390, 1090), (690, 430), (360, 1330), (700, 1210), (420, 760), (680, 1460)]
# cuándo aparece cada anuncio (segundos desde el inicio del video)
APPEAR = {
    3: SC["piratas"]["start"] + 0.9,
    4: SC["piratas"]["start"] + 1.6,
    6: SC["piratas"]["start"] + 2.2,
    0: SC["virus"]["start"] + 1.55,
    1: SC["virus"]["start"] + 2.75,
    5: SC["virus"]["start"] + 3.3,
    2: SC["virus"]["start"] + 4.2,
    7: SC["virus"]["start"] + 5.0,
}


def pirate_page():
    im = gradient(W, H, ["#1A1A1A", "#2B2B2B"])
    d = ImageDraw.Draw(im)
    d.rounded_rectangle((40, 70, W - 40, 170), 30, fill=(255, 255, 255, 255))
    d.text((80, 98), "mp3-full-gratis-sin-virus.ejemplo", font=fnt("medium", 36), fill=(200, 40, 40, 255))
    d.rounded_rectangle((40, 210, W - 40, 430), 24, fill=hexc("#FF2D55"))
    draw_center(im, "MÚSICA GRATIS", 235, fnt("bold", 86), shadow=False)
    draw_center(im, "100% SEGURO · CONFÍA · NO ES ESTAFA", 350, fnt("bold", 34), fill=(255, 241, 118, 255), shadow=False)
    labels = ["DESCARGAR", "DESCARGA AQUÍ", "DESCARGAR MP3", "BAJAR YA"]
    for i, y in enumerate(range(480, 1700, 165)):
        d.rounded_rectangle((70 + (i % 2) * 40, y, W - 70, y + 118), 60,
                            fill=hexc(["#34C759", "#007AFF", "#FF9500", "#AF52DE"][i % 4]))
        draw_center(im, labels[i % 4], y + 32, fnt("bold", 50), shadow=False)
    return im


PAGE = pirate_page()


def scene_pirate(img, tt):
    s = scene_at(tt)
    img.alpha_composite(PAGE)
    in_virus = s["key"] == "virus"
    for idx, at in APPEAR.items():
        age = tt - at
        if age < 0:
            continue
        x, y = POS[idx]
        k = ease_back(age / 0.33)
        jitter = math.sin((age + idx) * 37) * 7 if in_virus else 0
        big = 0.92 if idx in (0, 1, 2) else 0.78
        paste_scaled(img, POPS[idx], x + jitter, y, big * k)
    # contador rojo de virus
    if in_virus and tt > APPEAR[0]:
        n = min(5, 1 + int((tt - APPEAR[0]) / 0.18))
        draw_center(img, f"VIRUS DETECTADOS: {n}", 1640, fnt("bold", 54), fill=(255, 59, 48, 255), stroke=3)


def scene_tranqui(img, lt, tt):
    img.alpha_composite(PAGE)
    k = ease_out(lt / 0.5)
    for idx in APPEAR:
        x, y = POS[idx]
        paste_scaled(img, POPS[idx], x + (x - W / 2) * 2.4 * k, y + (y - H / 2) * 2.4 * k,
                     0.85 * (1 - 0.5 * k), alpha=1 - k, rot=(idx - 4) * 25 * k)
    fl = clamp(1 - lt / 0.3)
    if fl > 0:
        img.alpha_composite(Image.new("RGBA", (W, H), (255, 255, 255, int(220 * fl))))
    img.alpha_composite(V.BG["brand"].copy() if k >= 1 else Image.blend(
        Image.new("RGBA", (W, H), (0, 0, 0, 0)), V.BG["brand"], k))
    notes(img, tt, (255, 255, 255))
    t1 = Image.new("RGBA", (W, 260), (0, 0, 0, 0))
    draw_center(t1, "¡Tranquilo!", 40, fnt("bold", 140))
    paste_scaled(img, t1, W / 2, 470, ease_back((lt - 0.15) / 0.4))
    paste_scaled(img, HERO, W / 2, 900, ease_back((lt - 0.9) / 0.5, 2.2))
    a = clamp((lt - 1.4) / 0.4)
    draw_center(img, "Llegó MegaDown", 1200, fnt("bold", 84), fill=(255, 241, 118, int(255 * a)))


PLATS = [("YouTube", "#FF2D55", 2.3), ("TikTok", "#111114", 2.85), ("Instagram", "#D62976", 3.45),
         ("Facebook", "#1877F2", 4.1), ("Spotify", "#1DB954", 4.9)]


def scene_plats(img, lt, tt):
    img.alpha_composite(V.BG["dark"])
    notes(img, tt, (180, 160, 255))
    k = ease_out(lt / 0.6)
    paste_scaled(img, PH["md_home"], W / 2, 1180 + (1 - k) * 700, 0.88)
    draw_center(img, "De cualquier plataforma", 120, fnt("bold", 66), fill=(255, 255, 255, int(255 * k)))
    xs = [128, 326, 540, 760, 958]
    for i, (n, c, at) in enumerate(PLATS):
        a = clamp((lt - at) / 0.25)
        if a > 0:
            paste_scaled(img, chip(n, c if c != "#111114" else "#2A2A30", size=25), xs[i], 250, ease_back(a, 2.6))


def scene_spotify(img, lt, tt):
    img.alpha_composite(V.BG["green"])
    notes(img, tt, (150, 255, 180))
    k = ease_out(lt / 0.5)
    sw = ease_out((lt - 3.2) / 0.5)
    if sw < 1:
        paste_scaled(img, PH["md_spotify_top"], W / 2 - sw * 300, 1180 + (1 - k) * 700, 0.88, alpha=1 - sw)
    if sw > 0:
        paste_scaled(img, PH["md_spotify_list"], W / 2 + (1 - sw) * 300, 1180, 0.88, alpha=sw)
    a = clamp((lt - 0.2) / 0.3)
    paste_scaled(img, chip("¿Canción por canción?", "#FF3B30", size=46), W / 2, 150, ease_back(a))
    b = clamp((lt - 3.2) / 0.3)
    if b > 0:
        paste_scaled(img, chip("MODO PLAYLIST", "#1DB954", size=66), W / 2, 290, ease_back(b, 2.5))


def scene_ultra(img, lt, tt):
    img.alpha_composite(V.BG["dark"])
    notes(img, tt, (255, 200, 120))
    k = ease_out(lt / 0.5)
    z = ease_out((lt - 1.0) / 0.5)
    if z < 1:
        paste_scaled(img, PH["md_ultra"], W / 2, 1150 + (1 - k) * 600 + z * 300, 0.86 * (1 - 0.3 * z), alpha=1 - z)
    if z > 0:
        paste_scaled(img, ULTRA_CARD, W / 2, 860, lerp(0.7, 1.0, z) * (1 + 0.015 * math.sin(tt * 4)), alpha=z)
    paste_scaled(img, chip("VELOCIDAD ULTRA", "#FF9500", size=66), W / 2, 200, ease_back((lt - 0.2) / 0.3))
    b = clamp((lt - 1.4) / 0.3)
    if b > 0:
        t1 = Image.new("RGBA", (W, 260), (0, 0, 0, 0))
        draw_center(t1, "¡HASTA 8 A LA VEZ!", 40, fnt("bold", 92), fill=(255, 241, 118, 255), stroke=3, stroke_fill=(120, 60, 0, 255))
        paste_scaled(img, t1, W / 2, 1230, ease_back(b, 2.6))
    c = clamp((lt - 3.2) / 0.3)
    if c > 0:
        paste_scaled(img, chip("Ahorra un montón de tiempo", "#AF52DE", size=46), W / 2, 1420, ease_back(c))


def scene_gratis(img, lt, tt):
    img.alpha_composite(V.BG["dark"])
    g = clamp((lt - 0.7) / 0.35)
    if g > 0:
        t2 = Image.new("RGBA", (W, 360), (0, 0, 0, 0))
        draw_center(t2, "¡GRATIS!", 60, fnt("bold", 190), fill=(52, 199, 89, 255), stroke=8, stroke_fill=(255, 255, 255, 255))
        paste_scaled(img, t2, W / 2, 700, ease_back(g, 3.0))
        V.confetti(img, lt - 0.7)
    j = clamp((lt - 1.75) / 0.3)
    if j > 0:
        shake = math.sin(lt * 60) * 10 * clamp(1 - (lt - 1.75) / 0.6)
        t3 = Image.new("RGBA", (W, 300), (0, 0, 0, 0))
        draw_center(t3, "Ni tu ex se había", 20, fnt("bold", 76), fill=(255, 255, 255, 255))
        draw_center(t3, "regalado tanto", 120, fnt("bold", 76), fill=(255, 120, 150, 255))
        paste_scaled(img, t3, W / 2 + shake, 1150, ease_back(j, 2.4), rot=-4 * j)


def scene_qr(img, lt, tt):
    img.alpha_composite(V.BG["end"])
    notes(img, tt, (255, 255, 255))
    draw_center(img, "Descarga MegaDown", 150, fnt("bold", 80), fill=(255, 255, 255, int(255 * clamp(lt / 0.3))))
    k = ease_back(lt / 0.5, 2.0)
    paste_scaled(img, QR, W / 2, 900, k * (1 + 0.02 * math.sin(tt * 6)))
    draw_center(img, "Gratis · Sin anuncios · Android", 1430, fnt("semibold", 44), fill=(255, 255, 255, int(255 * clamp((lt - 0.6) / 0.4))))


def scene_cierre(img, lt, tt):
    img.alpha_composite(V.BG["end"])
    notes(img, tt, (255, 255, 255))
    # 1ª parte: las 10 canciones de siempre (tachado)
    p1 = clamp(1 - (lt - 4.3) / 0.4)
    if p1 > 0:
        a = clamp(lt / 0.3) * p1
        t1 = Image.new("RGBA", (W, 520), (0, 0, 0, 0))
        draw_center(t1, "¿Pagar Spotify por", 30, fnt("bold", 70))
        draw_center(t1, "tus mismas 10", 130, fnt("bold", 70), fill=(255, 241, 118, 255))
        draw_center(t1, "canciones aburridas?", 230, fnt("bold", 70))
        paste_scaled(img, t1, W / 2, 520, ease_back(clamp(lt / 0.4)), alpha=a)
        x = clamp((lt - 2.6) / 0.35)
        if x > 0:
            d = ImageDraw.Draw(img)
            d.line((140, 640, 140 + (W - 280) * x, 380), fill=(255, 59, 48, int(255 * p1)), width=18)
            if x >= 1:
                draw_center(img, "¡NUNCA MÁS!", 820, fnt("bold", 110), fill=(255, 59, 48, int(255 * p1)), stroke=4)
    # 2ª parte: tarjeta final con QR
    e = clamp((lt - 4.3) / 0.5)
    if e > 0:
        paste_scaled(img, HERO, W / 2, 400, 0.95 * ease_back(e))
        paste_scaled(img, QR, W / 2, 930, 0.52 * ease_back(clamp((lt - 4.6) / 0.5)))
        b = clamp((lt - 5.6) / 0.4)
        draw_center(img, "¡Bájalo todo!", 1210, fnt("bold", 96), fill=(255, 241, 118, int(255 * b)))
        draw_center(img, "Desarrollado por Gabo Matica · @GabLuciani", 1330, fnt("semibold", 38),
                    fill=(255, 255, 255, int(230 * b)))


def caption(img, tt):
    s = scene_at(tt)
    if tt < s["start"] - 0.1 or tt > s["start"] + s["dur"] + 0.2:
        return
    a = clamp((tt - s["start"] + 0.1) / 0.2) * clamp((s["start"] + s["dur"] + 0.2 - tt) / 0.2)
    f = fnt("semibold", 42)
    d0 = ImageDraw.Draw(img)
    lines = wrap(d0, CAPTIONS[s["key"]], f, W - 160)
    lh = 56
    box_h = lh * len(lines) + 40
    y0 = H - 130 - box_h
    ov = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    od = ImageDraw.Draw(ov)
    od.rounded_rectangle((50, y0, W - 50, y0 + box_h), 30, fill=(0, 0, 0, int(170 * a)))
    for i, ln in enumerate(lines):
        tw, _ = text_size(od, ln, f)
        od.text(((W - tw) / 2, y0 + 18 + i * lh), ln, font=f, fill=(255, 255, 255, int(255 * a)))
    img.alpha_composite(ov)


def render(tt):
    img = Image.new("RGBA", (W, H), (0, 0, 0, 255))
    s = scene_at(tt)
    lt = tt - s["start"]
    key = s["key"] if tt >= SCENES[0]["start"] else "piratas"
    if key in ("piratas", "virus"):
        scene_pirate(img, tt)
    elif key == "tranqui":
        scene_tranqui(img, lt, tt)
    elif key == "plats":
        scene_plats(img, lt, tt)
    elif key == "spotify":
        scene_spotify(img, lt, tt)
    elif key == "ultra":
        scene_ultra(img, lt, tt)
    elif key == "gratis":
        scene_gratis(img, lt, tt)
    elif key == "qr":
        scene_qr(img, lt, tt)
    else:
        scene_cierre(img, lt, tt)
    if key not in ("piratas", "virus", "tranqui") and lt < 0.12:
        img.alpha_composite(Image.new("RGBA", (W, H), (255, 255, 255, int(150 * (1 - lt / 0.12)))))
    caption(img, tt)
    return img.convert("RGB")


# ---------------------------------------------------------------- audio
def sfx_rimshot():
    """Ba-dum-tss: dos golpes de tambor y un platillo."""
    out = np.zeros(int(1.4 * SR), np.float32)
    rng = np.random.default_rng(7)
    for at, f0 in ((0.0, 180), (0.18, 140)):
        L = int(0.2 * SR)
        tt_ = np.arange(L) / SR
        drum = np.sin(2 * np.pi * f0 * tt_) * np.exp(-tt_ * 18) + 0.5 * rng.normal(0, 1, L) * np.exp(-tt_ * 30)
        i = int(at * SR)
        out[i:i + L] += drum
    L = int(1.0 * SR)
    tt_ = np.arange(L) / SR
    cym = rng.normal(0, 1, L)
    cym = cym - np.convolve(cym, np.ones(4) / 4, "same")
    i = int(0.38 * SR)
    out[i:i + L] += 0.8 * cym * np.exp(-tt_ * 3.5)
    return out * 0.7


def sfx_alarm():
    L = int(0.9 * SR)
    tt_ = np.arange(L) / SR
    f = np.where((tt_ * 6).astype(int) % 2 == 0, 880, 660)
    return np.sign(np.sin(2 * np.pi * f * tt_)) * 0.25 * np.exp(-tt_ * 1.5)


def sfx_knock():
    out = np.zeros(int(0.7 * SR), np.float32)
    for at in (0.0, 0.18, 0.36):
        L = int(0.08 * SR)
        tt_ = np.arange(L) / SR
        i = int(at * SR)
        out[i:i + L] += np.sin(2 * np.pi * 120 * tt_) * np.exp(-tt_ * 60)
    return out


def build_audio(path):
    n = int(TOTAL * SR)
    mus = V.music(TOTAL)
    voice = np.zeros(n, np.float32)
    for s in SCENES:
        V.add(voice, V.load_wav(s["file"]), s["start"], 1.0)
    envv = np.convolve(np.abs(voice), np.ones(int(0.25 * SR)) / int(0.25 * SR), "same")
    duck = 1 - 0.55 * np.clip(envv / 0.03, 0, 1)
    mix = 0.30 * mus * duck + voice * 1.15
    fx = np.zeros(n, np.float32)
    for at in APPEAR.values():
        V.add(fx, V.sfx_pop(), at, 0.5)
    V.add(fx, sfx_alarm(), APPEAR[0], 0.5)
    V.add(fx, sfx_knock(), APPEAR[2] + 0.1, 0.9)
    V.add(fx, V.sfx_scratch(), SC["tranqui"]["start"] - 0.05, 0.55)
    for k in ("plats", "spotify", "ultra", "gratis", "qr", "cierre"):
        V.add(fx, V.sfx_whoosh(), SC[k]["start"] - 0.2, 0.35)
    V.add(fx, V.sfx_ding(), SC["gratis"]["start"] + 0.7, 0.8)
    V.add(fx, sfx_rimshot(), SC["gratis"]["start"] + 3.2, 0.9)
    mix += fx
    mix /= max(1.0, np.max(np.abs(mix)) / 0.95)
    V_write(path, mix)


def V_write(path, mix):
    import wave
    pcm = (mix * 32767).astype(np.int16)
    with wave.open(str(path), "w") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(pcm.tobytes())


def main():
    audio = BUILD / "audio2.wav"
    print("audio…")
    build_audio(audio)
    out = BUILD / "MegaDown-anuncio.mp4"
    nframes = int(TOTAL * FPS)
    print(f"video: {nframes} cuadros ({TOTAL:.1f} s)…")
    cmd = [str(V.FFMPEG), "-y", "-loglevel", "error",
           "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{W}x{H}", "-r", str(FPS), "-i", "-",
           "-i", str(audio), "-c:v", "libx264", "-preset", "medium", "-crf", "20", "-pix_fmt", "yuv420p",
           "-c:a", "aac", "-b:a", "160k", "-movflags", "+faststart", "-shortest", str(out)]
    p = subprocess.Popen(cmd, stdin=subprocess.PIPE)
    for f in range(nframes):
        p.stdin.write(render(f / FPS).tobytes())
        if f % 150 == 0:
            print(f"  {f}/{nframes}", flush=True)
    p.stdin.close()
    p.wait()
    print("listo:", out)


if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "frames":
        for s in SCENES:
            for off in (0.5, s["dur"] * 0.85):
                render(s["start"] + off).resize((360, 640)).save(BUILD / f"p2_{s['key']}_{off:.1f}.png")
        print("previas listas")
    else:
        main()
