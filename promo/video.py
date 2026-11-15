"""Video promocional de YT MP3 (vertical 1080x1920, 30 fps).

Todo se genera aquí: escenas con capturas reales de la app, subtítulos, música y efectos
sintetizados (sin pistas con derechos de autor) y la voz creada con voz.py (Piper).
Uso:  python voz.py  &&  python video.py
"""
import json
import math
import random
import subprocess
import wave
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = Path(__file__).resolve().parent
BUILD = ROOT / "build"
SHOTS = ROOT / "shots"
FONTS = ROOT.parent / "android" / "app" / "src" / "main" / "res" / "font"
FFMPEG = Path(r"C:\Users\GABO\AppData\Local\Microsoft\WinGet\Packages\yt-dlp.FFmpeg_Microsoft.Winget.Source_8wekyb3d8bbwe"
              r"\ffmpeg-N-127043-g5a54fcf75e-win64-gpl\bin\ffmpeg.exe")
W, H, FPS = 1080, 1920, 30
SR = 44100

URL_TEXT = "github.com/superprinx2/YT-MP3"

# ---------------------------------------------------------------- tiempos (según la voz)
voz = json.loads((BUILD / "voz" / "voz.json").read_text(encoding="utf-8"))
GAP = 0.32
t = 0.5
SCENES = []
for v in voz:
    SCENES.append({"key": v["key"], "start": t, "dur": v["dur"], "file": v["file"]})
    t += v["dur"] + GAP
TOTAL = t + 2.2
for i, s in enumerate(SCENES):
    s["end"] = SCENES[i + 1]["start"] if i + 1 < len(SCENES) else TOTAL

# Subtítulos con la ortografía correcta (la voz usa la pronunciación).
CAPTIONS = {
    "ads": "¿En serio? ¿Todavía bajas tu música de páginas que te llenan de anuncios?",
    "popups": "Tocas descargar… y te sale que ganaste un celular. Cierras uno… ¡y aparecen tres más!",
    "basta": "¡Ya basta, compadre!",
    "intro": "Te presento: YT MP3",
    "pasos": "Pegas el enlace, eliges MP3 o MP4… ¡y listo!",
    "plats": "YouTube, TikTok sin marca de agua, Instagram, Facebook… ¡y hasta tus playlists de Spotify!",
    "ultra": "¿150 canciones? Activa el modo Ultra y bájalas varias a la vez, incluso con la pantalla bloqueada.",
    "cero": "Cero anuncios. Cero ventanas raras. Cero trucos. Y lo mejor: ¡es gratis!",
    "cta": "Descárgala ya desde GitHub. Desarrollado por Gabo Matica.",
    "cierre": "Deja de sufrir… y empieza a descargar.",
}


def scene_at(tt):
    for s in SCENES:
        if tt < s["end"]:
            return s
    return SCENES[-1]


# ---------------------------------------------------------------- utilidades de dibujo
def font(weight, size):
    return ImageFont.truetype(str(FONTS / f"inter_{weight}.ttf"), size)


F = {k: {} for k in ("regular", "medium", "semibold", "bold")}


def fnt(weight, size):
    if size not in F[weight]:
        F[weight][size] = font(weight, size)
    return F[weight][size]


def clamp(x, a=0.0, b=1.0):
    return max(a, min(b, x))


def ease_out(x):
    x = clamp(x)
    return 1 - (1 - x) ** 3


def ease_back(x, s=1.9):
    x = clamp(x)
    x -= 1
    return x * x * ((s + 1) * x + s) + 1


def lerp(a, b, k):
    return a + (b - a) * k


def hexc(h, a=255):
    h = h.lstrip("#")
    return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), a)


def gradient(w, h, colors, angle_horizontal=False):
    """Degradado vertical (u horizontal) con varios colores."""
    n = w if angle_horizontal else h
    pos = np.linspace(0, 1, n)
    stops = np.linspace(0, 1, len(colors))
    arr = np.zeros((n, 3))
    cols = np.array([hexc(c)[:3] for c in colors], dtype=float)
    for ch in range(3):
        arr[:, ch] = np.interp(pos, stops, cols[:, ch])
    if angle_horizontal:
        img = np.repeat(arr[None, :, :], h, axis=0)
    else:
        img = np.repeat(arr[:, None, :], w, axis=1)
    return Image.fromarray(img.astype(np.uint8), "RGB").convert("RGBA")


def text_size(d, txt, f):
    b = d.textbbox((0, 0), txt, font=f)
    return b[2] - b[0], b[3] - b[1]


def draw_center(img, txt, y, f, fill=(255, 255, 255, 255), shadow=True, x=None, stroke=0, stroke_fill=(0, 0, 0, 255)):
    d = ImageDraw.Draw(img)
    tw, th = text_size(d, txt, f)
    xx = (W - tw) / 2 if x is None else x - tw / 2
    if shadow:
        d.text((xx + 3, y + 5), txt, font=f, fill=(0, 0, 0, 110))
    d.text((xx, y), txt, font=f, fill=fill, stroke_width=stroke, stroke_fill=stroke_fill)
    return tw, th


def wrap(d, txt, f, maxw):
    words, lines, cur = txt.split(), [], ""
    for w_ in words:
        test = (cur + " " + w_).strip()
        if text_size(d, test, f)[0] <= maxw:
            cur = test
        else:
            lines.append(cur)
            cur = w_
    if cur:
        lines.append(cur)
    return lines


def paste_scaled(dst, src, cx, cy, scale, alpha=1.0, rot=0.0):
    if scale <= 0.01 or alpha <= 0.01:
        return
    w, h = src.size
    im = src.resize((max(1, int(w * scale)), max(1, int(h * scale))), Image.BILINEAR)
    if rot:
        im = im.rotate(rot, resample=Image.BICUBIC, expand=True)
    if alpha < 1:
        a = im.getchannel("A").point(lambda v: int(v * alpha))
        im.putalpha(a)
    dst.alpha_composite(im, (int(cx - im.width / 2), int(cy - im.height / 2)))


def rounded(img, r):
    mask = Image.new("L", img.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, img.width - 1, img.height - 1), r, fill=255)
    out = img.convert("RGBA")
    out.putalpha(mask)
    return out


# ---------------------------------------------------------------- recursos
def load_shot(name):
    return Image.open(SHOTS / f"{name}.png").convert("RGBA")


def phone(shot, screen_w=600):
    sh = shot.resize((screen_w, int(screen_w * shot.height / shot.width)), Image.LANCZOS)
    sh = rounded(sh, 44)
    bez = 18
    ph = Image.new("RGBA", (sh.width + bez * 2, sh.height + bez * 2), (0, 0, 0, 0))
    d = ImageDraw.Draw(ph)
    d.rounded_rectangle((0, 0, ph.width - 1, ph.height - 1), 62, fill=(18, 18, 22, 255))
    d.rounded_rectangle((3, 3, ph.width - 4, ph.height - 4), 60, outline=(70, 70, 80, 255), width=2)
    ph.alpha_composite(sh, (bez, bez))
    # sombra
    sh_img = Image.new("RGBA", (ph.width + 80, ph.height + 80), (0, 0, 0, 0))
    ImageDraw.Draw(sh_img).rounded_rectangle((40, 50, ph.width + 40, ph.height + 50), 62, fill=(0, 0, 0, 120))
    sh_img = sh_img.filter(ImageFilter.GaussianBlur(24))
    sh_img.alpha_composite(ph, (40, 40))
    return sh_img


print("cargando recursos…")
SH = {n: load_shot(n) for n in ["yt_top", "yt_list", "tiktok_card", "ig_tab", "fb", "spotify_list", "ultra_run4"]}
PH = {n: phone(img) for n, img in SH.items()}
HERO = rounded(SH["yt_top"].crop((42, 160, 1038, 622)), 40)       # encabezado de la app (logo + título)
LOGO = rounded(SH["yt_top"].crop((89, 207, 241, 357)), 34)          # ícono de la app

BG = {
    "ads": gradient(W, H, ["#E9E9EE", "#DADAE2"]),
    "brand": gradient(W, H, ["#FF2D55", "#FF6B3D", "#AF52DE"]),
    "dark": gradient(W, H, ["#0B0B1A", "#1B1240", "#2A0F3D"]),
    "green": gradient(W, H, ["#0B3D20", "#127A3B", "#1E1240"]),
    "end": gradient(W, H, ["#0A2A6B", "#3F2BB8", "#B02A8A"]),
}

PLAT = [("YouTube", "#FF2D55"), ("TikTok", "#111114"), ("Instagram", "#D62976"),
        ("Facebook", "#1877F2"), ("Spotify", "#1DB954")]


# ---------------------------------------------------------------- escena: anuncios
random.seed(4)
POPUPS = [
    ("#FF3B30", "¡FELICIDADES!", "Ganaste un celular nuevo", "RECLAMAR"),
    ("#FFCC00", "ALERTA", "Tu teléfono tiene 13 virus", "LIMPIAR YA"),
    ("#34C759", "DESCARGAR", "(este no es el botón real)", "DESCARGAR"),
    ("#AF52DE", "Espera…", "Tu descarga empieza en 30 s", "SALTAR"),
    ("#FF2D55", "¡OFERTA!", "Limpiador de RAM gratis", "INSTALAR"),
    ("#007AFF", "¿Ya te vas?", "Mira estos 10 trucos", "VER"),
    ("#FF9500", "¡CLIC AQUÍ!", "Premio sorpresa", "ABRIR"),
    ("#5856D6", "Notificaciones", "Permitir para continuar", "PERMITIR"),
    ("#FF3B30", "¡ÚLTIMA OPORTUNIDAD!", "Solo hoy", "SÍ, QUIERO"),
]


def make_popup(color, title, body, btn):
    w, h = 640, 330
    im = Image.new("RGBA", (w + 40, h + 40), (0, 0, 0, 0))
    sh = Image.new("RGBA", im.size, (0, 0, 0, 0))
    ImageDraw.Draw(sh).rounded_rectangle((20, 26, w + 20, h + 26), 26, fill=(0, 0, 0, 130))
    im.alpha_composite(sh.filter(ImageFilter.GaussianBlur(12)))
    d = ImageDraw.Draw(im)
    d.rounded_rectangle((20, 20, w + 20, h + 20), 26, fill=(255, 255, 255, 255))
    d.rounded_rectangle((20, 20, w + 20, 120), 26, fill=hexc(color))
    d.rectangle((20, 90, w + 20, 120), fill=hexc(color))
    dark = color in ("#FFCC00",)
    d.text((48, 42), title, font=fnt("bold", 46), fill=(30, 30, 30, 255) if dark else (255, 255, 255, 255))
    # X de cerrar
    d.ellipse((w - 50, 40, w + 2, 92), fill=(0, 0, 0, 60))
    d.line((w - 36, 54, w - 12, 78), fill=(255, 255, 255, 255), width=5)
    d.line((w - 12, 54, w - 36, 78), fill=(255, 255, 255, 255), width=5)
    d.text((48, 150), body, font=fnt("semibold", 36), fill=(40, 40, 46, 255))
    d.rounded_rectangle((48, 230, w - 8, 310), 40, fill=hexc(color))
    bw, _ = text_size(d, btn, fnt("bold", 36))
    d.text((48 + (w - 56 - bw) / 2, 248), btn, font=fnt("bold", 36), fill=(30, 30, 30, 255) if dark else (255, 255, 255, 255))
    return im


POPIMG = [make_popup(*p) for p in POPUPS]
POS = [(340, 520), (720, 760), (400, 1010), (690, 1260), (360, 1440), (690, 600), (420, 860), (700, 1110), (380, 1300)]


def ads_page():
    im = BG["ads"].copy()
    d = ImageDraw.Draw(im)
    d.rounded_rectangle((40, 70, W - 40, 170), 30, fill=(255, 255, 255, 255))
    d.text((80, 98), "descargas-gratis-ya.ejemplo/mp3", font=fnt("medium", 38), fill=(120, 120, 130, 255))
    d.rounded_rectangle((40, 210, W - 40, 420), 24, fill=hexc("#FFE600"))
    draw_center(im, "DESCARGAR MP3", 240, fnt("bold", 80), fill=(220, 0, 60, 255), shadow=False)
    draw_center(im, "100% GRATIS  ·  SIN VIRUS  ·  CREO", 345, fnt("bold", 36), fill=(40, 40, 40, 255), shadow=False)
    for i, y in enumerate(range(470, 1700, 170)):
        x0 = 70 + (i % 2) * 40
        d.rounded_rectangle((x0, y, W - 70, y + 120), 60, fill=hexc(["#34C759", "#007AFF", "#FF9500"][i % 3]))
        draw_center(im, ["DESCARGAR", "DESCARGA AQUÍ", "DESCARGAR AHORA"][i % 3] + " ⬇" if False else
                    ["DESCARGAR", "DESCARGA AQUÍ", "DESCARGAR AHORA"][i % 3], y + 32, fnt("bold", 50), shadow=False)
    return im


ADS_PAGE = ads_page()


def draw_cursor(img, x, y, press):
    d = ImageDraw.Draw(img)
    r = 30 if not press else 24
    d.ellipse((x - r, y - r, x + r, y + r), fill=(0, 0, 0, 90))
    d.ellipse((x - r + 6, y - r + 6, x + r - 6, y + r - 6), fill=(255, 255, 255, 230))


def scene_ads(img, lt, phase2):
    img.alpha_composite(ADS_PAGE)
    # escena 1: aparecen 1 cada 0.6 s; escena 2: se cierra uno y salen tres
    if not phase2:
        shown = [(i, lt - 0.6 - i * 0.62) for i in range(5)]
    else:
        base = [(i, 99) for i in range(5)]
        closing = 1  # el "virus"
        extra = [(5 + j, lt - 3.15 - j * 0.22) for j in range(4)]
        shown = base + extra
    for idx, age in shown:
        if age < 0:
            continue
        x, y = POS[idx]
        k = ease_back(age / 0.35)
        sc = 0.8 * k
        jitter = math.sin((age + idx) * 31) * 6 if phase2 and age < 99 else 0
        if phase2 and idx == 1:
            # el usuario toca la X: se encoge
            ck = clamp((lt - 2.35) / 0.25)
            sc *= (1 - ck)
            if sc <= 0.01:
                continue
        paste_scaled(img, POPIMG[idx], x + jitter, y, sc)
    if phase2 and 1.5 < lt < 2.7:
        cx, cy = POS[1][0] + 280, POS[1][1] - 120
        draw_cursor(img, lerp(W / 2, cx, ease_out((lt - 1.5) / 0.7)), lerp(1600, cy, ease_out((lt - 1.5) / 0.7)), lt > 2.25)


def scene_basta(img, lt):
    img.alpha_composite(ADS_PAGE)
    k = ease_out(lt / 0.5)
    for idx in range(9):
        x, y = POS[idx]
        dx, dy = (x - W / 2) * 2.4 * k, (y - H / 2) * 2.4 * k
        paste_scaled(img, POPIMG[idx], x + dx, y + dy, 0.92 * (1 - 0.5 * k), alpha=1 - k, rot=(idx - 4) * 25 * k)
    # destello rojo
    fl = clamp(1 - lt / 0.35)
    if fl > 0:
        img.alpha_composite(Image.new("RGBA", (W, H), (255, 40, 60, int(200 * fl))))
    img.alpha_composite(Image.new("RGBA", (W, H), (10, 10, 20, int(190 * k))))
    sc = ease_back((lt - 0.05) / 0.45, 2.6)
    shake = math.sin(lt * 70) * 14 * clamp(1 - lt / 0.9)
    t1 = Image.new("RGBA", (W, 400), (0, 0, 0, 0))
    draw_center(t1, "¡YA BASTA!", 80, fnt("bold", 150), fill=(255, 255, 255, 255), stroke=6, stroke_fill=(255, 45, 85, 255))
    paste_scaled(img, t1, W / 2 + shake, H / 2 - 120, sc)


def scene_intro(img, lt, tt):
    img.alpha_composite(BG["brand"])
    notes(img, tt, (255, 255, 255))
    k = ease_back(lt / 0.6)
    paste_scaled(img, LOGO, W / 2, 620, 1.9 * k)
    a = clamp((lt - 0.35) / 0.4)
    t1 = Image.new("RGBA", (W, 520), (0, 0, 0, 0))
    draw_center(t1, "YT MP3", 40, fnt("bold", 170))
    draw_center(t1, "Descarga música y videos", 260, fnt("semibold", 56))
    draw_center(t1, "sin anuncios", 340, fnt("bold", 72), fill=(255, 241, 118, 255))
    paste_scaled(img, t1, W / 2, 1120 + (1 - ease_out(a)) * 80, 1.0, alpha=a)


def chip(text, color, active=True, size=40):
    d0 = ImageDraw.Draw(Image.new("RGBA", (1, 1)))
    tw, th = text_size(d0, text, fnt("bold", size))
    im = Image.new("RGBA", (tw + 64, int(size * 1.9)), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    d.rounded_rectangle((0, 0, im.width - 1, im.height - 1), im.height // 2,
                        fill=hexc(color) if active else (255, 255, 255, 60))
    d.text((32, (im.height - th) / 2 - size * 0.18), text, font=fnt("bold", size), fill=(255, 255, 255, 255))
    return im


def scene_pasos(img, lt, tt):
    img.alpha_composite(BG["dark"])
    notes(img, tt, (180, 160, 255))
    k = ease_out(lt / 0.6)
    paste_scaled(img, PH["yt_top"], W / 2, 1160 + (1 - k) * 700, 0.92)
    steps = [("1  Pega el enlace", "#007AFF", 0.2), ("2  Elige MP3 o MP4", "#AF52DE", 1.35), ("3  ¡Listo!", "#34C759", 2.9)]
    for i, (txt, col, at) in enumerate(steps):
        a = clamp((lt - at) / 0.3)
        if a > 0:
            paste_scaled(img, chip(txt, col, size=50), W / 2, 150 + i * 120, ease_back(a))


_HALOS = {}


def halo_for(color):
    """Halo de color difuminado (se calcula una vez por plataforma: el desenfoque es costoso)."""
    if color not in _HALOS:
        halo = Image.new("RGBA", (W, H), (0, 0, 0, 0))
        ImageDraw.Draw(halo).ellipse((W / 2 - 520, 520, W / 2 + 520, 1560),
                                     fill=hexc(color if color != "#111114" else "#00B8C4", 120))
        _HALOS[color] = halo.filter(ImageFilter.GaussianBlur(120))
    return _HALOS[color]


PLAT_SHOT = {"YouTube": "yt_list", "TikTok": "tiktok_card", "Instagram": "ig_tab", "Facebook": "fb", "Spotify": "spotify_list"}
PLAT_TIMES = [0.0, 0.75, 2.35, 3.15, 3.95]   # segundos dentro de la frase


def scene_plats(img, lt, tt):
    cur = max(i for i, s in enumerate(PLAT_TIMES) if lt >= s)
    name, color = PLAT[cur]
    img.alpha_composite(gradient(W, H, ["#0B0B1A", color if color != "#111114" else "#00B8C4", "#0B0B1A"]) if False else BG["dark"])
    # halo del color de la plataforma
    img.alpha_composite(halo_for(color))
    seg = lt - PLAT_TIMES[cur]
    k = ease_out(seg / 0.35)
    paste_scaled(img, PH[PLAT_SHOT[name]], W / 2 + (1 - k) * 260, 1170, 0.9, alpha=k)
    # fila de chips
    xs = [130, 330, 540, 750, 950]
    for i, (n, c) in enumerate(PLAT):
        active = i <= cur
        sc = 1.12 if i == cur else 0.9
        if i == cur:
            sc = lerp(0.9, 1.12, ease_back(seg / 0.3))
        paste_scaled(img, chip(n, c, active, size=30), xs[i], 150, sc, alpha=1.0 if active else 0.55)
    label = {"TikTok": "Sin marca de agua", "Spotify": "Tus playlists en MP3", "YouTube": "Videos y playlists",
             "Instagram": "Reels en MP4 o MP3", "Facebook": "Videos y reels"}[name]
    draw_center(img, label, 240, fnt("bold", 58), fill=(255, 255, 255, int(255 * k)))


ULTRA_CARD = rounded(SH["ultra_run4"].crop((30, 1560, 1050, 2020)), 36)


def scene_ultra(img, lt, tt):
    img.alpha_composite(BG["dark"])
    notes(img, tt, (255, 200, 120))
    k = ease_out(lt / 0.6)
    z = ease_out((lt - 1.6) / 0.6)          # 0 → celular completo, 1 → tarjeta de progreso
    if z < 1:
        paste_scaled(img, PH["ultra_run4"], W / 2, 1150 + (1 - k) * 600 + z * 300, 0.86 * (1 - 0.3 * z), alpha=1 - z)
    if z > 0:
        paste_scaled(img, ULTRA_CARD, W / 2, 820, lerp(0.7, 1.0, z) * (1 + 0.015 * math.sin(tt * 4)), alpha=z)
    a = clamp((lt - 0.3) / 0.3)
    paste_scaled(img, chip("MODO ULTRA", "#FF9500", size=70), W / 2, 200, ease_back(a))
    b = clamp((lt - 3.6) / 0.35)
    if b > 0:
        paste_scaled(img, chip("Varias descargas a la vez", "#AF52DE", size=48), W / 2, 1170, ease_back(b))
    c = clamp((lt - 5.2) / 0.35)
    if c > 0:
        paste_scaled(img, chip("Con la pantalla bloqueada", "#007AFF", size=48), W / 2, 1300, ease_back(c))


CONF = None


def confetti(img, lt):
    global CONF
    if CONF is None:
        r = random.Random(9)
        cols = ["#FF3B30", "#FF9500", "#FFCC00", "#34C759", "#007AFF", "#AF52DE", "#FF2D55"]
        CONF = [(r.uniform(-1, 1), r.uniform(-1.6, -0.6), r.choice(cols), r.uniform(10, 22), r.uniform(0, 360), r.uniform(-400, 400)) for _ in range(170)]
    d = ImageDraw.Draw(img)
    for vx, vy, col, size, rot, vr in CONF:
        x = W / 2 + vx * 900 * lt
        y = H * 0.55 + vy * 1500 * lt + 1300 * lt * lt
        if y > H + 40:
            continue
        a = int(255 * clamp(1 - (lt - 2.2) / 0.8))
        ang = math.radians(rot + vr * lt)
        dx, dy = math.cos(ang) * size, math.sin(ang) * size * 0.5
        d.polygon([(x - dx, y - dy), (x + dy, y - dx), (x + dx, y + dy), (x - dy, y + dx)], fill=hexc(col, a))


def scene_cero(img, lt, tt):
    img.alpha_composite(BG["dark"])
    items = [("0 ANUNCIOS", 0.05), ("0 VENTANAS RARAS", 1.35), ("0 TRUCOS", 2.65)]
    for i, (txt, at) in enumerate(items):
        a = clamp((lt - at) / 0.25)
        if a <= 0:
            continue
        t1 = Image.new("RGBA", (W, 200), (0, 0, 0, 0))
        draw_center(t1, txt, 30, fnt("bold", 104), stroke=4, stroke_fill=(255, 45, 85, 255))
        paste_scaled(img, t1, W / 2, 380 + i * 230, ease_back(a, 2.4) * (0.9 if lt > 4.0 else 1), alpha=0.45 if lt > 4.0 else 1)
    g = clamp((lt - 4.0) / 0.35)
    if g > 0:
        t2 = Image.new("RGBA", (W, 360), (0, 0, 0, 0))
        draw_center(t2, "¡GRATIS!", 60, fnt("bold", 180), fill=(52, 199, 89, 255), stroke=8, stroke_fill=(255, 255, 255, 255))
        paste_scaled(img, t2, W / 2, 1220, ease_back(g, 3.0))
        confetti(img, lt - 4.0)


def scene_end(img, lt, tt, closing):
    img.alpha_composite(BG["end"])
    notes(img, tt, (255, 255, 255))
    k = ease_out(lt / 0.5) if not closing else 1
    paste_scaled(img, HERO, W / 2, 470 - (1 - k) * 200, 1.0 + 0.02 * math.sin(tt * 3), alpha=k)
    draw_center(img, "Descárgala GRATIS", 860, fnt("bold", 84))
    pill = Image.new("RGBA", (900, 120), (0, 0, 0, 0))
    ImageDraw.Draw(pill).rounded_rectangle((0, 0, 899, 119), 60, fill=(255, 255, 255, 255))
    draw_center(pill, URL_TEXT, 30, fnt("bold", 50), fill=(30, 30, 60, 255), shadow=False, x=450)
    paste_scaled(img, pill, W / 2, 1040, 1 + 0.03 * math.sin(tt * 5))
    draw_center(img, "Android · YouTube · TikTok · Instagram · Facebook · Spotify", 1140, fnt("semibold", 34), fill=(255, 255, 255, 220))
    draw_center(img, "Desarrollado por", 1260, fnt("medium", 40), fill=(255, 255, 255, 200))
    draw_center(img, "Gabo Matica  ·  @GabLuciani", 1315, fnt("bold", 56))
    if closing:
        a = clamp(lt / 0.4)
        t1 = Image.new("RGBA", (W, 260), (0, 0, 0, 0))
        draw_center(t1, "Deja de sufrir…", 10, fnt("bold", 80), fill=(255, 241, 118, 255))
        draw_center(t1, "¡y empieza a descargar!", 120, fnt("bold", 80))
        paste_scaled(img, t1, W / 2, 1530, ease_back(a))


NOTE_P = [(random.uniform(0, W), random.uniform(0, H), random.uniform(30, 70), random.uniform(40, 110), random.choice("♪♫")) for _ in range(14)]


def notes(img, tt, rgb):
    d = ImageDraw.Draw(img)
    for x, y, sp, size, g in NOTE_P:
        yy = (y - tt * sp) % (H + 200) - 100
        d.text((x + math.sin(tt + x) * 20, yy), g, font=fnt("bold", int(size)), fill=(*rgb, 40))


def caption(img, tt):
    s = scene_at(tt)
    if tt < s["start"] - 0.1 or tt > s["start"] + s["dur"] + 0.25:
        return
    a = clamp((tt - s["start"] + 0.1) / 0.2) * clamp((s["start"] + s["dur"] + 0.25 - tt) / 0.2)
    d0 = ImageDraw.Draw(img)
    f = fnt("semibold", 44)
    lines = wrap(d0, CAPTIONS[s["key"]], f, W - 160)
    lh = 58
    box_h = lh * len(lines) + 44
    y0 = H - 140 - box_h
    ov = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    od = ImageDraw.Draw(ov)
    od.rounded_rectangle((50, y0, W - 50, y0 + box_h), 30, fill=(0, 0, 0, int(165 * a)))
    for i, ln in enumerate(lines):
        tw, _ = text_size(od, ln, f)
        od.text(((W - tw) / 2, y0 + 20 + i * lh), ln, font=f, fill=(255, 255, 255, int(255 * a)))
    img.alpha_composite(ov)


def render(tt):
    img = Image.new("RGBA", (W, H), (0, 0, 0, 255))
    s = scene_at(tt)
    lt = tt - s["start"]
    key = s["key"]
    if tt < SCENES[0]["start"]:
        key, lt = "ads", 0.0
    if key == "ads":
        scene_ads(img, lt, False)
    elif key == "popups":
        scene_ads(img, lt, True)
    elif key == "basta":
        scene_basta(img, lt)
    elif key == "intro":
        scene_intro(img, lt, tt)
    elif key == "pasos":
        scene_pasos(img, lt, tt)
    elif key == "plats":
        scene_plats(img, lt, tt)
    elif key == "ultra":
        scene_ultra(img, lt, tt)
    elif key == "cero":
        scene_cero(img, lt, tt)
    elif key == "cta":
        scene_end(img, lt, tt, False)
    else:
        scene_end(img, lt, tt, True)
    # transición: destello blanco corto al empezar cada escena (menos las de anuncios)
    if key not in ("ads", "popups", "basta") and lt < 0.12:
        img.alpha_composite(Image.new("RGBA", (W, H), (255, 255, 255, int(160 * (1 - lt / 0.12)))))
    caption(img, tt)
    return img.convert("RGB")


# ---------------------------------------------------------------- audio
def load_wav(path):
    with wave.open(str(path)) as w:
        sr, n = w.getframerate(), w.getnframes()
        data = np.frombuffer(w.readframes(n), dtype=np.int16).astype(np.float32) / 32768
        if w.getnchannels() == 2:
            data = data.reshape(-1, 2).mean(axis=1)
    if sr != SR:
        x_old = np.linspace(0, 1, len(data))
        x_new = np.linspace(0, 1, int(len(data) * SR / sr))
        data = np.interp(x_new, x_old, data).astype(np.float32)
    return data


def env(n, attack=0.005, decay=0.2):
    t_ = np.arange(n) / SR
    return np.minimum(1, t_ / max(attack, 1e-4)) * np.exp(-t_ / decay)


def music(total):
    n = int(total * SR)
    out = np.zeros(n, np.float32)
    bpm = 116
    beat = 60 / bpm
    rng = np.random.default_rng(1)
    prog = [110.0, 87.31, 130.81, 98.0]  # La m, Fa, Do, Sol
    chords = [[220, 261.6, 329.6], [174.6, 220, 261.6], [261.6, 329.6, 392], [196, 246.9, 293.7]]
    nb = int(total / beat) + 1
    for b in range(nb):
        t0 = int(b * beat * SR)
        bar = (b // 4) % 4
        # bombo
        L = int(0.25 * SR)
        tt_ = np.arange(L) / SR
        kick = np.sin(2 * np.pi * (50 + 90 * np.exp(-tt_ * 25)) * tt_) * np.exp(-tt_ * 12)
        out[t0:t0 + L] += 0.9 * kick[:max(0, min(L, n - t0))]
        # palmada en 2 y 4
        if b % 2 == 1:
            L2 = int(0.18 * SR)
            clap = rng.normal(0, 1, L2) * env(L2, 0.001, 0.05)
            out[t0:t0 + L2] += 0.35 * clap[:max(0, min(L2, n - t0))]
        # charles en corcheas
        for half in (0, 0.5):
            th = int((b + half) * beat * SR)
            L3 = int(0.05 * SR)
            hh = rng.normal(0, 1, L3)
            hh = hh - np.convolve(hh, np.ones(6) / 6, "same")
            out[th:th + L3] += 0.12 * (hh * env(L3, 0.001, 0.015))[:max(0, min(L3, n - th))]
        # bajo en corcheas
        for half in (0, 0.5):
            tb = int((b + half) * beat * SR)
            L4 = int(beat * 0.45 * SR)
            tt4 = np.arange(L4) / SR
            f0 = prog[bar] * (2 if half else 1)
            bass = (np.sin(2 * np.pi * f0 * tt4) + 0.4 * np.sin(4 * np.pi * f0 * tt4)) * env(L4, 0.005, 0.18)
            out[tb:tb + L4] += 0.32 * bass[:max(0, min(L4, n - tb))]
        # acorde suave al empezar cada compás
        if b % 4 == 0:
            L5 = int(beat * 4 * SR)
            tt5 = np.arange(L5) / SR
            pad = sum(np.sin(2 * np.pi * f * tt5) for f in chords[bar]) / 3
            pad *= np.minimum(1, tt5 / 0.3) * np.exp(-tt5 / 2.5)
            out[t0:t0 + L5] += 0.18 * pad[:max(0, min(L5, n - t0))]
    return out


def sfx_pop():
    L = int(0.09 * SR)
    t_ = np.arange(L) / SR
    return np.sin(2 * np.pi * (500 + 2500 * t_ / 0.09) * t_) * env(L, 0.002, 0.04)


def sfx_whoosh():
    L = int(0.45 * SR)
    rng = np.random.default_rng(3)
    nz = rng.normal(0, 1, L)
    nz = np.convolve(nz, np.ones(30) / 30, "same")
    t_ = np.arange(L) / SR
    return nz * np.sin(np.pi * t_ / 0.45) * 1.6


def sfx_scratch():
    L = int(0.55 * SR)
    t_ = np.arange(L) / SR
    rng = np.random.default_rng(5)
    f = 300 + 900 * np.abs(np.sin(2 * np.pi * 3.5 * t_))
    tone = np.sin(2 * np.pi * np.cumsum(f) / SR)
    return (0.6 * tone + 0.4 * rng.normal(0, 1, L)) * env(L, 0.005, 0.3)


def sfx_ding():
    L = int(1.2 * SR)
    t_ = np.arange(L) / SR
    return (np.sin(2 * np.pi * 1318.5 * t_) + 0.6 * np.sin(2 * np.pi * 1975.5 * t_)) * env(L, 0.002, 0.35) * 0.6


def add(buf, clip, at, gain=1.0):
    i = int(at * SR)
    if i >= len(buf):
        return
    j = min(len(buf), i + len(clip))
    buf[i:j] += gain * clip[:j - i]


def build_audio(path):
    n = int(TOTAL * SR)
    mus = music(TOTAL)
    voice = np.zeros(n, np.float32)
    for s in SCENES:
        add(voice, load_wav(s["file"]), s["start"], 1.0)
    # ducking: bajar la música cuando habla la voz
    envv = np.abs(voice)
    win = int(0.25 * SR)
    envv = np.convolve(envv, np.ones(win) / win, "same")
    duck = 1 - 0.55 * np.clip(envv / 0.03, 0, 1)
    mix = 0.30 * mus * duck + voice * 1.15
    fx = np.zeros(n, np.float32)
    sc = {s["key"]: s for s in SCENES}
    for i in range(5):
        add(fx, sfx_pop(), sc["ads"]["start"] + 0.6 + i * 0.62, 0.5)
    add(fx, sfx_pop()[::-1], sc["popups"]["start"] + 2.35, 0.5)
    for j in range(4):
        add(fx, sfx_pop(), sc["popups"]["start"] + 3.15 + j * 0.22, 0.55)
    add(fx, sfx_scratch(), sc["basta"]["start"] - 0.05, 0.55)
    for k in ("intro", "pasos", "plats", "ultra", "cero", "cta"):
        add(fx, sfx_whoosh(), sc[k]["start"] - 0.2, 0.35)
    add(fx, sfx_ding(), sc["cero"]["start"] + 4.0, 0.8)
    mix += fx
    mix /= max(1.0, np.max(np.abs(mix)) / 0.95)
    pcm = (mix * 32767).astype(np.int16)
    with wave.open(str(path), "w") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(pcm.tobytes())


def main():
    BUILD.mkdir(exist_ok=True)
    audio = BUILD / "audio.wav"
    print("audio…")
    build_audio(audio)
    out = BUILD / "YT-MP3-promo.mp4"
    nframes = int(TOTAL * FPS)
    print(f"video: {nframes} cuadros ({TOTAL:.1f} s)…")
    cmd = [str(FFMPEG), "-y", "-loglevel", "error",
           "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{W}x{H}", "-r", str(FPS), "-i", "-",
           "-i", str(audio), "-c:v", "libx264", "-preset", "medium", "-crf", "20", "-pix_fmt", "yuv420p",
           "-c:a", "aac", "-b:a", "160k", "-movflags", "+faststart", "-shortest", str(out)]
    p = subprocess.Popen(cmd, stdin=subprocess.PIPE)
    for f in range(nframes):
        p.stdin.write(render(f / FPS).tobytes())
        if f % 150 == 0:
            print(f"  {f}/{nframes}")
    p.stdin.close()
    p.wait()
    print("listo:", out)


if __name__ == "__main__":
    import sys
    if len(sys.argv) > 1 and sys.argv[1] == "frames":
        # vista previa: un cuadro por escena
        for s in SCENES:
            for off in (0.4, s["dur"] * 0.8):
                render(s["start"] + off).resize((360, 640)).save(BUILD / f"prev_{s['key']}_{off:.1f}.png")
        print("previas listas")
    else:
        main()
