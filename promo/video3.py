"""Anuncio de DownSong para TikTok (vertical 1080x1920, 30 fps, tono burlón).

Fondo siempre en movimiento: grabaciones reales del Modo DJ (difuminadas), y un tramo a pantalla
completa mostrando la app. Encima: escenas animadas, subtítulos tipo karaoke palabra por palabra.
Uso:  python voz3.py  &&  python video3.py   ("python video3.py frames" = solo vistas previas)
"""
import json
import math
import subprocess
import sys
import wave
from pathlib import Path

import numpy as np
import qrcode
from PIL import Image, ImageDraw, ImageFilter

sys.path.insert(0, str(Path(__file__).resolve().parent))
import video as V
from video import W, H, FPS, SR, BUILD, fnt, clamp, lerp, ease_out, ease_back, hexc, draw_center, text_size, \
    wrap, paste_scaled, rounded, phone, chip, load_wav, add, music, sfx_pop, sfx_whoosh, sfx_scratch, sfx_ding

FFMPEG = str(V.FFMPEG)
SRC = Path(r"C:\Users\GABO\Desktop\carpeta para video de tiktok")
VID_A = SRC / "WhatsApp Video 2026-11-20 at 6.17.37 PM.mp4"
VID_B = SRC / "WhatsApp Video 2026-11-20 at 6.29.59 PM.mp4"
# grabación con sonido: el DJ cambia de canción cerca del segundo 25
VID_C = Path(r"C:\Users\GABO\Downloads\WhatsApp Video 2026-11-20 at 7.28.04 PM (1).mp4")
SHOW_SS, SHOW_LEN = 16.0, 11.0
IMG_DL = SRC / "WhatsApp Image 2026-11-20 at 6.28.38 PM (1).jpeg"
IMG_LIB = SRC / "WhatsApp Image 2026-11-20 at 6.28.38 PM.jpeg"
OUT_DIR = BUILD / "tiktok"
OUT_DIR.mkdir(parents=True, exist_ok=True)
FINAL = Path(r"C:\Users\GABO\Desktop\carpeta para video de tiktok\DownSong-TikTok.mp4")
URL = "https://github.com/superprinx2/YT-MP3/releases/latest"
URL_TEXT = "github.com/superprinx2/YT-MP3"

# ---------------------------------------------------------------- tiempos (según la voz)
voz = json.loads((BUILD / "voz3" / "voz.json").read_text(encoding="utf-8"))
GAP = 0.22
SHOW_EXTRA = SHOW_LEN + 0.4   # la voz se calla: se ve y se oye la app mezclando
t = 0.25
SCENES = []
for v in voz:
    SCENES.append({"key": v["key"], "start": t, "dur": v["dur"], "file": v["file"], "words": v["words"], "text": v["text"]})
    t += v["dur"] + GAP
    if v["key"] == "mezclas":
        t += SHOW_EXTRA
TOTAL = t + 2.6
for i, s in enumerate(SCENES):
    s["end"] = SCENES[i + 1]["start"] if i + 1 < len(SCENES) else TOTAL
SC = {s["key"]: s for s in SCENES}
SHOW_T0 = SC["mezclas"]["start"] + SC["mezclas"]["dur"] + 0.15
SHOW_T1 = SHOW_T0 + SHOW_LEN


def word_t(key, *needles):
    """Momento en que la voz dice una palabra (para sincronizar animaciones)."""
    s = SC[key]
    for w in s["words"]:
        if any(n.lower() in w["w"].lower() for n in needles):
            return s["start"] + w["t"]
    return s["start"]


# Subtítulos: palabras con la ortografía correcta
FIX = {"plei": "PLAY", "stor": "STORE", "spotifái": "SPOTIFY", "yutub": "YOUTUBE", "prémium": "PREMIUM", "daun": "DOWN", "song": "SONG",
       "guasap": "WHATSAPP", "feisbuk": "FACEBOOK", "tiktok": "TIKTOK"}
import re
WORDS = []
for s in SCENES:
    # dónde hay comas/puntos en el guion: ahí se corta el grupo de palabras
    toks = re.findall(r"[\wáéíóúñüÁÉÍÓÚÑ']+[^\w\s]*", s["text"] if "text" in s else "")
    for j, w in enumerate(s["words"]):
        txt = w["w"].strip(" ,.¡!¿?…:;\"")
        if not txt:
            continue
        brk = j < len(toks) and bool(re.search(r"[,.!?…:;]$", toks[j]))
        WORDS.append({"w": FIX.get(txt.lower(), txt.upper()), "t": s["start"] + w["t"], "e": s["start"] + w["t"] + w["d"],
                      "key": s["key"], "brk": brk})
# grupos de hasta 3 palabras (estilo TikTok)
CHUNKS = []
i = 0
while i < len(WORDS):
    grp = [WORDS[i]]
    while len(grp) < 3 and i + len(grp) < len(WORDS):
        nxt = WORDS[i + len(grp)]
        if nxt["key"] != grp[0]["key"] or nxt["t"] - grp[-1]["e"] > 0.35 or grp[-1]["brk"]:
            break
        grp.append(nxt)
    CHUNKS.append(grp)
    i += len(grp)
for k, g in enumerate(CHUNKS):
    nxt_t = CHUNKS[k + 1][0]["t"] if k + 1 < len(CHUNKS) else g[-1]["e"] + 0.6
    g[0]["show_end"] = min(nxt_t, g[-1]["e"] + 0.5)


def scene_at(tt):
    for s in SCENES:
        if tt < s["end"]:
            return s
    return SCENES[-1]


# ---------------------------------------------------------------- recursos
def jpg(p):
    return Image.open(p).convert("RGBA")


SHOT_DL = jpg(IMG_DL)
SHOT_LIB = jpg(IMG_LIB)
PH_DL = phone(SHOT_DL, 520)
PH_LIB = phone(SHOT_LIB, 520)
def _dj_still():
    out = OUT_DIR / "dj_still.png"
    if not out.exists():
        subprocess.run([FFMPEG, "-v", "error", "-y", "-ss", "5", "-i", str(VID_C), "-frames:v", "1", str(out)], check=True)
    return Image.open(out).convert("RGBA")
PH_DJ = phone(_dj_still(), 520)
LOGO = Image.open(BUILD / "logo_ds.png").convert("RGBA")

qr = qrcode.QRCode(border=2, box_size=10, error_correction=qrcode.constants.ERROR_CORRECT_M)
qr.add_data(URL); qr.make(fit=True)
QR_IMG = qr.make_image(fill_color="black", back_color="white").convert("RGBA")
QR_CARD = Image.new("RGBA", (720, 880), (0, 0, 0, 0))
_d = ImageDraw.Draw(QR_CARD)
_d.rounded_rectangle((0, 0, 719, 879), 48, fill=(255, 255, 255, 255))
QR_CARD.alpha_composite(QR_IMG.resize((600, 600), Image.NEAREST), (60, 50))
draw_center(QR_CARD, "Escanea y descarga GRATIS", 680, fnt("bold", 46), fill=(20, 20, 30, 255), shadow=False, x=360)
draw_center(QR_CARD, URL_TEXT, 760, fnt("semibold", 34), fill=(110, 110, 130, 255), shadow=False, x=360)

RED = (225, 29, 42, 255)
YEL = (255, 230, 0, 255)
WHITE = (255, 255, 255, 255)


def glow_text(img, txt, cy, size, fill=WHITE, glow=RED, rot=0.0, scale=1.0, alpha=1.0, cx=W / 2, stroke=10):
    """Texto grande con borde negro y resplandor de color (rotable y escalable)."""
    f = fnt("bold", size)
    d0 = ImageDraw.Draw(Image.new("RGBA", (1, 1)))
    tw, th = text_size(d0, txt, f)
    pad = 60
    im = Image.new("RGBA", (tw + pad * 2, th + pad * 2), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    if glow:
        g = Image.new("RGBA", im.size, (0, 0, 0, 0))
        ImageDraw.Draw(g).text((pad, pad - th * 0.2), txt, font=f, fill=glow, stroke_width=stroke + 6, stroke_fill=glow)
        im.alpha_composite(g.filter(ImageFilter.GaussianBlur(18)))
    d.text((pad, pad - th * 0.2), txt, font=f, fill=fill, stroke_width=stroke, stroke_fill=(0, 0, 0, 255))
    paste_scaled(img, im, cx, cy, scale, alpha, rot)


def card(w, h, color=(28, 28, 32, 235), r=36):
    im = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    ImageDraw.Draw(im).rounded_rectangle((0, 0, w - 1, h - 1), r, fill=color)
    return im


def heart(size, color):
    im = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    pts = []
    for k in range(200):
        a = 2 * math.pi * k / 200
        x = 16 * math.sin(a) ** 3
        y = 13 * math.cos(a) - 5 * math.cos(2 * a) - 2 * math.cos(3 * a) - math.cos(4 * a)
        pts.append((size / 2 + x * size / 36, size / 2 - y * size / 36))
    d.polygon(pts, fill=color)
    return im


HEART = heart(420, (235, 40, 70, 255))


def broken_halves():
    """El corazón partido en dos (línea en zigzag)."""
    w, h = HEART.size
    zig = [(w / 2, 0)]
    for k in range(1, 8):
        zig.append((w / 2 + (28 if k % 2 else -28), h * k / 8))
    zig.append((w / 2, h))
    m1 = Image.new("L", (w, h), 0); ImageDraw.Draw(m1).polygon([(0, 0)] + zig + [(0, h)], fill=255)
    m2 = Image.new("L", (w, h), 0); ImageDraw.Draw(m2).polygon([(w, 0)] + zig + [(w, h)], fill=255)
    a = HEART.copy(); a.putalpha(Image.composite(HEART.getchannel("A"), Image.new("L", (w, h), 0), m1))
    b = HEART.copy(); b.putalpha(Image.composite(HEART.getchannel("A"), Image.new("L", (w, h), 0), m2))
    return a, b


HALF_L, HALF_R = broken_halves()


def bubble(text, mine, width=640):
    """Burbuja de chat genérica."""
    f = fnt("semibold", 44)
    d0 = ImageDraw.Draw(Image.new("RGBA", (1, 1)))
    lines = wrap(d0, text, f, width - 80)
    lh = 58
    h = lh * len(lines) + 46
    w = min(width, max(text_size(d0, ln, f)[0] for ln in lines) + 70)
    im = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    d.rounded_rectangle((0, 0, w - 1, h - 1), 30, fill=(0, 150, 95, 255) if mine else (58, 58, 64, 255))
    for k, ln in enumerate(lines):
        d.text((35, 20 + k * lh), ln, font=f, fill=WHITE)
    return im


def pop_in(lt, t0, dur=0.28):
    return ease_back(clamp((lt - t0) / dur))


# ---------------------------------------------------------------- escenas
def sc_hey(img, tt):
    s = SC["hey"]; lt = tt - s["start"]
    t2 = word_t("hey", "sí") - s["start"]
    sh = math.sin(tt * 60) * 14 * max(0, 1 - lt * 1.5)
    glow_text(img, "¡HEY, TÚ!", 640 + sh, 190, YEL, RED, rot=math.sin(tt * 7) * 3, scale=pop_in(lt, 0.0))
    if lt > t2:
        glow_text(img, "¡SÍ, TÚ!", 900, 150, WHITE, RED, rot=-4, scale=pop_in(lt, t2))
        # flecha que apunta al que mira
        k = clamp((lt - t2) / 0.3)
        d = ImageDraw.Draw(img)
        y = 1060 + math.sin(tt * 10) * 10
        d.polygon([(540, y + 120 * k), (470, y + 40 * k), (610, y + 40 * k)], fill=RED)


def sc_harto(img, tt):
    s = SC["harto"]; lt = tt - s["start"]
    ts = [word_t("harto", "caras") - s["start"], word_t("harto", "internet") - s["start"]]
    names = [("SUSCRIPCIÓN", (120, 120, 255, 255)), ("SIN SEÑAL = SIN MÚSICA", (255, 150, 0, 255))]
    for k, (nm, col) in enumerate(names):
        t0 = ts[k] - 0.5
        sc = pop_in(lt, t0)
        if sc <= 0:
            continue
        c = card(820, 260)
        d = ImageDraw.Draw(c)
        d.rounded_rectangle((30, 40, 70, 220), 14, fill=col)
        d.text((100, 52), nm, font=fnt("bold", 52), fill=WHITE)
        d.text((100, 140), "$ $ $  cada mes" if k == 0 else "sin internet no suena nada", font=fnt("semibold", 44), fill=(200, 200, 210, 255))
        paste_scaled(img, c, 540, 520 + k * 320, sc)
        # sello "NO MÁS"
        if lt > ts[k] + 0.25:
            st = Image.new("RGBA", (420, 150), (0, 0, 0, 0))
            dd = ImageDraw.Draw(st)
            dd.rounded_rectangle((4, 4, 415, 145), 18, outline=RED, width=10)
            dd.text((40, 20), "NO MÁS", font=fnt("bold", 92), fill=RED)
            k2 = clamp((lt - ts[k] - 0.25) / 0.18)
            paste_scaled(img, st, 700, 540 + k * 320, lerp(2.2, 1.0, k2), k2, rot=-14)


def sc_playlist(img, tt):
    s = SC["playlist"]; lt = tt - s["start"]
    tb = word_t("playlist", "dejó") - s["start"]
    c = card(760, 330)
    d = ImageDraw.Draw(c)
    d.text((40, 30), "Playlist «Para ella»", font=fnt("bold", 54), fill=WHITE)
    for k, ln in enumerate(["1. Nuestra canción", "2. Te amo (versión triste)", "3. Para siempre..."]):
        d.text((40, 120 + k * 64), ln, font=fnt("semibold", 42), fill=(190, 190, 200, 255))
    paste_scaled(img, c, 540, 420, pop_in(lt, 0.1))
    beat = 1 + 0.06 * abs(math.sin(tt * 6))
    if lt < tb:
        paste_scaled(img, HEART, 540, 860, pop_in(lt, 0.6) * beat * 0.9)
    else:
        k = ease_out(clamp((lt - tb) / 0.6))
        paste_scaled(img, HALF_L, 540 - 60 * k, 860 + 40 * k, 0.9, 1 - 0.3 * k, rot=12 * k)
        paste_scaled(img, HALF_R, 540 + 60 * k, 860 + 40 * k, 0.9, 1 - 0.3 * k, rot=-12 * k)


def horns(img, cx, cy, sc):
    d = ImageDraw.Draw(img)
    for sgn in (-1, 1):
        bx = cx + sgn * 230 * sc
        d.polygon([(bx - 45 * sc, cy), (bx + 45 * sc, cy), (bx + sgn * 70 * sc, cy - 170 * sc)], fill=(200, 20, 30, 255))


def sc_despierta(img, tt):
    s = SC["despierta"]; lt = tt - s["start"]
    sh = math.sin(tt * 50) * 8 * max(0, 1 - lt)
    sc = pop_in(lt, 0)
    glow_text(img, "DESPIERTA,", 380 + sh, 130, WHITE, RED, scale=sc)
    glow_text(img, "COMPADRE", 530 + sh, 150, YEL, RED, rot=-3, scale=pop_in(lt, 0.35))
    items = [("ocupada", "No está ocupada"), ("confundida", "No está confundida"), ("tiempo", "No necesita tiempo")]
    for k, (needle, txt) in enumerate(items):
        t0 = word_t("despierta", needle) - s["start"] - 0.2
        scc = pop_in(lt, t0)
        if scc <= 0:
            continue
        c = card(760, 110, (20, 20, 24, 225))
        d = ImageDraw.Draw(c)
        d.line((45, 32, 90, 77), fill=RED, width=10); d.line((90, 32, 45, 77), fill=RED, width=10)
        d.text((120, 26), txt, font=fnt("semibold", 50), fill=WHITE)
        paste_scaled(img, c, 540, 720 + k * 130, scc)
    t_otro = word_t("despierta", "otro") - s["start"]
    if lt > t_otro - 0.3:
        glow_text(img, "YA TIENE A OTRO", 1110, 96, YEL, RED, rot=-6, scale=lerp(2.0, 1.0, clamp((lt - t_otro + 0.3) / 0.2)))


def sc_amigo(img, tt):
    s = SC["amigo"]; lt = tt - s["start"]
    d = ImageDraw.Draw(img)
    d.rounded_rectangle((110, 300, 970, 1150), 44, fill=(15, 15, 18, 215))
    d.ellipse((150, 330, 230, 410), fill=(235, 40, 70, 255))
    d.text((250, 345), "Ella", font=fnt("bold", 50), fill=WHITE)
    msgs = [("nada", "nada que ver 🙄".replace("🙄", "...")), ("amigo", "es solo un amigo"), ("desconfiado", "¡qué desconfiado eres!")]
    for k, (needle, txt) in enumerate(msgs):
        t0 = word_t("amigo", needle) - s["start"] - 0.15
        scc = pop_in(lt, t0, 0.22)
        if scc > 0:
            b = bubble(txt, False)
            paste_scaled(img, b, 150 + b.width / 2, 520 + k * 190, scc)
    if lt > 0.2:
        glow_text(img, "\"SOLO UN AMIGO\"", 1050, 70, WHITE, None, rot=-5, scale=pop_in(lt, word_t("amigo", "amigo") - s["start"] + 0.3), stroke=6)


def sc_fotos(img, tt):
    s = SC["fotos"]; lt = tt - s["start"]
    d = ImageDraw.Draw(img)
    d.rounded_rectangle((110, 300, 970, 1150), 44, fill=(15, 15, 18, 215))
    d.text((150, 340), "Ella  ·  en línea hace 3 días", font=fnt("semibold", 40), fill=(170, 170, 180, 255))
    msgs = ["buenos días mi amor", "¿estás?", "te extraño", "hola?? :("]
    for k, txt in enumerate(msgs):
        t0 = 0.4 + k * 1.1
        scc = pop_in(lt, t0, 0.22)
        if scc > 0:
            b = bubble(txt, True, 560)
            paste_scaled(img, b, 930 - b.width / 2, 470 + k * 140, scc)
    tv = word_t("fotos", "visto") - s["start"]
    if lt > tv - 0.1:
        k = clamp((lt - tv + 0.1) / 0.2)
        d.text((640, 1040), "Visto ✓✓", font=fnt("bold", 50), fill=(70, 160, 255, int(255 * k)))
        glow_text(img, "EN VISTO", 1250, 110, YEL, RED, rot=-7, scale=lerp(2.2, 1.0, k))


def rays(img, cx, cy, tt, alpha, color=(225, 29, 42)):
    ov = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(ov)
    for k in range(16):
        a = tt * 0.6 + k * math.pi / 8
        a2 = a + 0.09
        d.polygon([(cx, cy), (cx + math.cos(a) * 1600, cy + math.sin(a) * 1600), (cx + math.cos(a2) * 1600, cy + math.sin(a2) * 1600)],
                  fill=color + (int(70 * alpha),))
    img.alpha_composite(ov)


def sc_llego(img, tt):
    s = SC["llego"]; lt = tt - s["start"]
    tl = word_t("llego", "llegó") - s["start"]
    if lt < tl:
        glow_text(img, "NO ME CAMBIES", 560, 110, WHITE, None, scale=pop_in(lt, 0), stroke=8)
        glow_text(img, "DE TEMA", 700, 120, YEL, None, scale=pop_in(lt, 0.3), stroke=8)
        return
    k = clamp((lt - tl) / 0.35)
    rays(img, 540, 700, tt, k)
    paste_scaled(img, LOGO, 540, 700, lerp(0.2, 0.95, ease_back(k)) * (1 + 0.03 * math.sin(tt * 8)))
    td = word_t("llego", "daun") - s["start"]
    if lt > td:
        glow_text(img, "DOWN SONG", 1110, 150, WHITE, RED, scale=pop_in(lt, td))


def sc_descarga(img, tt):
    s = SC["descarga"]; lt = tt - s["start"]
    k = ease_out(clamp(lt / 0.5))
    paste_scaled(img, PH_LIB, 540 + (1 - k) * 700, 760, 0.95)
    tn = word_t("descarga", "internet") - s["start"]
    if lt > tn - 0.4:
        c = chip("SIN INTERNET", "#E11D2A", True, 56)
        paste_scaled(img, c, 540, 300, pop_in(lt, tn - 0.4), rot=-5)


def sc_plats(img, tt):
    s = SC["plats"]; lt = tt - s["start"]
    k = ease_out(clamp(lt / 0.5))
    paste_scaled(img, PH_DJ, 540, 820 + (1 - k) * 900, 0.85, 0.55)
    items = [("dj", "MODO DJ  ✓", "#E11D2A"), ("luces", "LUCES QUE BAILAN  ✓", "#5B2BE0"), ("ecualizador", "ECUALIZADOR  ✓", "#0A84FF"),
             ("anuncios", "CERO ANUNCIOS  ✓", "#30B050"), ("supuesto", "SIN INTERNET  ✓", "#FF9500")]
    for i_, (needle, txt, col) in enumerate(items):
        t0 = word_t("plats", needle) - s["start"] - 0.1
        scc = pop_in(lt, t0)
        if scc <= 0:
            continue
        c = chip(txt, col, True, 62)
        side = -1 if i_ % 2 == 0 else 1
        paste_scaled(img, c, 540 + side * 150, 330 + i_ * 150, scc, rot=side * 4)
    tl = word_t("plats", "supuesto") - s["start"]
    if lt > tl + 0.4:
        glow_text(img, "¡POR SUPUESTO!", 1150, 110, YEL, RED, rot=-5, scale=pop_in(lt, tl + 0.4))


def sc_mezclas(img, tt):
    s = SC["mezclas"]; lt = tt - s["start"]
    if tt < SHOW_T0:
        glow_text(img, "LAS MEJORES", 600, 120, WHITE, RED, scale=pop_in(lt, 0))
        glow_text(img, "MEZCLAS", 760, 160, YEL, RED, scale=pop_in(lt, 0.25))
        return
    # sobre el video de la app a pantalla completa
    k = clamp((tt - SHOW_T0) / 0.3)
    c = chip("ASÍ SUENA  ·  MODO DJ EN VIVO", "#E11D2A", True, 48)
    paste_scaled(img, c, 540, 300, ease_back(k))
    if SHOW_T0 + 1.2 < tt < SHOW_T0 + 6.2:
        c2 = chip("LUCES QUE BAILAN CON TU MÚSICA", "#5B2BE0", True, 40)
        paste_scaled(img, c2, 540, 420, pop_in(tt, SHOW_T0 + 1.2))
    if tt > SHOW_T0 + 6.4:
        c3 = chip("MEZCLA SOLO A LA SIGUIENTE", "#FF9500", True, 44)
        paste_scaled(img, c3, 540, 420, pop_in(tt, SHOW_T0 + 6.4), rot=math.sin(tt * 6) * 2)


def link_perfil(img, tt, lt, y=760):
    """Avatar + flecha hacia el perfil (sin QR ni enlaces escritos)."""
    sc = pop_in(lt, 0.0)
    d = ImageDraw.Draw(img)
    paste_scaled(img, LOGO, 540, y - 260, 0.42 * sc)
    bob = math.sin(tt * 8) * 18
    for k in range(3):
        yy = y - 40 + k * 70 + bob
        d.polygon([(540, yy - 50), (470, yy + 10), (610, yy + 10)], fill=(255, 255, 255, 220 - k * 60))
    glow_text(img, "LINK EN MI PERFIL", y + 230, 96, YEL, RED, rot=-3, scale=pop_in(lt, 0.25))


def sc_qr(img, tt):
    s = SC["qr"]; lt = tt - s["start"]
    link_perfil(img, tt, lt)


def sc_cierre(img, tt):
    s = SC["cierre"]; lt = tt - s["start"]
    tf = word_t("cierre", "feliz") - s["start"]
    td = word_t("cierre", "daun") - s["start"]
    if lt < td - 0.2:
        glow_text(img, "Y OLVÍDALA,", 520, 120, WHITE, RED, scale=pop_in(lt, 0))
        glow_text(img, "PAPITO", 680, 170, YEL, RED, rot=-4, scale=pop_in(lt, 0.3))
        if lt > tf - 0.8:
            glow_text(img, "DÉJALA SER FELIZ", 880, 84, WHITE, None, scale=pop_in(lt, tf - 0.8), stroke=7)
        return
    k = clamp((lt - td + 0.2) / 0.35)
    rays(img, 540, 640, tt, k)
    paste_scaled(img, LOGO, 540, 640, lerp(0.3, 0.9, ease_back(k)) * (1 + 0.03 * math.sin(tt * 8)))
    glow_text(img, "DOWN SONG", 1030, 150, WHITE, RED, scale=pop_in(lt, td))
    glow_text(img, "Toda tu música, gratis y sin internet", 1170, 50, WHITE, None, scale=pop_in(lt, td + 0.4), stroke=5)


def store_bag(size):
    """Bolsa de tienda genérica (sin marcas)."""
    im = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    d.rounded_rectangle((size * 0.12, size * 0.3, size * 0.88, size * 0.95), int(size * 0.08), fill=(60, 170, 90, 255))
    d.arc((size * 0.32, size * 0.08, size * 0.68, size * 0.48), 180, 360, fill=(60, 170, 90, 255), width=int(size * 0.06))
    d.polygon([(size * 0.42, size * 0.5), (size * 0.42, size * 0.8), (size * 0.66, size * 0.65)], fill=WHITE)
    return im


BAG = store_bag(340)


def sc_playstore(img, tt):
    s = SC["playstore"]; lt = tt - s["start"]
    glow_text(img, "GRATIS", 520, 200, YEL, RED, rot=-4, scale=pop_in(lt, 0.0) * (1 + 0.03 * math.sin(tt * 7)))
    link_perfil(img, tt, max(0.0, lt - 0.6), y=1000)


SCENE_FN = {"playstore": sc_playstore, "hey": sc_hey, "harto": sc_harto, "playlist": sc_playlist, "despierta": sc_despierta, "amigo": sc_amigo,
            "fotos": sc_fotos, "llego": sc_llego, "descarga": sc_descarga, "plats": sc_plats, "mezclas": sc_mezclas,
            "qr": sc_qr, "cierre": sc_cierre}


# ---------------------------------------------------------------- subtítulos tipo karaoke
def captions(img, tt):
    cur = None
    for g in CHUNKS:
        if g[0]["t"] - 0.05 <= tt < g[0]["show_end"]:
            cur = g
    if cur is None:
        return
    f = fnt("bold", 84)
    d0 = ImageDraw.Draw(Image.new("RGBA", (1, 1)))
    space = 22
    widths = [text_size(d0, w["w"], f)[0] for w in cur]
    lines, line, lw = [], [], 0
    for w, wd in zip(cur, widths):
        if line and lw + space + wd > 860:
            lines.append(line); line, lw = [], 0
        line.append((w, wd)); lw += (space if lw else 0) + wd
    if line:
        lines.append(line)
    scl = ease_back(clamp((tt - cur[0]["t"] + 0.05) / 0.16))
    lay = Image.new("RGBA", (W, 300), (0, 0, 0, 0))
    d = ImageDraw.Draw(lay)
    y = 30
    for ln in lines:
        tot = sum(wd for _, wd in ln) + space * (len(ln) - 1)
        x = (W - tot) / 2 - 40
        for w, wd in ln:
            active = w["t"] <= tt < w["e"] + 0.08
            d.text((x, y), w["w"], font=f, fill=YEL if active else WHITE, stroke_width=9, stroke_fill=(0, 0, 0, 255))
            x += wd + space
        y += 104
    y_cap = 1540 if cur[0]["key"] != "playstore" else 1620
    paste_scaled(img, lay, W / 2, y_cap, scl)


# ---------------------------------------------------------------- cuadro completo (capa de arriba, transparente)
def render(tt):
    img = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    s = scene_at(tt)
    # destello blanco en los cambios de escena y al aparecer el logo
    SCENE_FN[s["key"]](img, tt)
    for key in ("llego",):
        tl = word_t(key, "llegó")
        if 0 <= tt - tl < 0.25:
            img.alpha_composite(Image.new("RGBA", (W, H), (255, 255, 255, int(200 * (1 - (tt - tl) / 0.25)))))
    captions(img, tt)
    # marca pequeña arriba (debajo de la barra de TikTok)
    if not (SHOW_T0 <= tt < SHOW_T1) and s["key"] not in ("llego", "cierre", "playstore"):
        paste_scaled(img, LOGO, 120, 250, 0.13)
        d = ImageDraw.Draw(img)
        d.text((175, 228), "DOWN SONG", font=fnt("bold", 38), fill=(255, 255, 255, 220), stroke_width=4, stroke_fill=(0, 0, 0, 200))
    return img


# ---------------------------------------------------------------- fondo (video real de la app)
def build_background():
    """Fondo difuminado continuo y el tramo nítido de la app."""
    segs = [(VID_B, 0, 12), (VID_A, 36, 20), (VID_B, 24, 22), (VID_A, 84, 13), (VID_B, 55, 14), (VID_A, 0, 18)]
    parts = []
    for k, (src, ss, du) in enumerate(segs):
        p = OUT_DIR / f"bg_{k}.mp4"
        subprocess.run([FFMPEG, "-v", "error", "-y", "-ss", str(ss), "-t", str(du), "-i", str(src),
                        "-vf", f"scale={W}:-2,crop={W}:{H}:0:(ih-{H})/2,fps={FPS},gblur=sigma=22,eq=brightness=-0.18:saturation=1.35",
                        "-an", "-c:v", "libx264", "-preset", "veryfast", "-crf", "20", str(p)], check=True)
        parts.append(p)
    lst = OUT_DIR / "bg_list.txt"
    lst.write_text("".join(f"file '{p.as_posix()}'\n" for p in parts), encoding="utf-8")
    bg = OUT_DIR / "bg.mp4"
    subprocess.run([FFMPEG, "-v", "error", "-y", "-f", "concat", "-safe", "0", "-i", str(lst), "-t", f"{TOTAL:.2f}",
                    "-c:v", "libx264", "-preset", "veryfast", "-crf", "20", str(bg)], check=True)
    # tramo nítido: la app tal cual (a lo alto), con el mismo video difuminado a los lados
    show = OUT_DIR / "show.mp4"
    dur = SHOW_T1 - SHOW_T0
    subprocess.run([FFMPEG, "-v", "error", "-y", "-ss", str(SHOW_SS), "-t", f"{dur:.2f}", "-i", str(VID_C), "-filter_complex",
                    f"[0:v]fps={FPS},split[a][b];[a]scale={W}:-2,crop={W}:{H}:0:(ih-{H})/2,gblur=sigma=30,eq=brightness=-0.3[bg];"
                    f"[b]scale=-2:{H}[fg];[bg][fg]overlay=(W-w)/2:0", "-an", "-c:v", "libx264", "-preset", "veryfast", "-crf", "18", str(show)],
                   check=True)
    # su sonido real (la música de la app y el cambio de canción)
    subprocess.run([FFMPEG, "-v", "error", "-y", "-ss", str(SHOW_SS), "-t", f"{dur:.2f}", "-i", str(VID_C), "-vn",
                    "-ac", "1", "-ar", str(SR), str(OUT_DIR / "show.wav")], check=True)
    return bg, show


# ---------------------------------------------------------------- audio
def show_mix(total):
    """Dos temas propios y el DJ mezclando del uno al otro (cambio de bajos y barrido), sin música con derechos."""
    a = music(total + 2)
    # segundo tema: otro tono y más rápido (remuestreado)
    base = music(total * 1.2 + 2)
    idx = np.arange(0, len(base) - 1, 1.12)
    b = np.interp(idx, np.arange(len(base)), base).astype(np.float32)
    n = int(total * SR)
    a = a[:n]; b = np.pad(b, (0, max(0, n - len(b))))[:n]
    t0, t1 = int(5.4 * SR), int(7.4 * SR)
    xf = np.zeros(n, np.float32); xf[t1:] = 1.0
    xf[t0:t1] = np.linspace(0, 1, t1 - t0)
    # graves fuera de la canción que sale (filtro sencillo) y entrada de la nueva
    lp = np.convolve(a, np.ones(60) / 60, "same")
    a_thin = a - lp * np.clip((np.arange(n) - t0) / (t1 - t0), 0, 1)
    mix = a_thin * np.cos(xf * np.pi / 2) + b * np.sin(xf * np.pi / 2)
    rise = np.zeros(n, np.float32)
    L = t1 - int(4.0 * SR)
    rng = np.random.default_rng(9)
    nz = rng.normal(0, 1, L).astype(np.float32)
    nz -= np.convolve(nz, np.ones(8) / 8, "same")
    rise[int(4.0 * SR):t1] = nz * np.linspace(0, 0.25, L)
    mix = mix + rise
    return (mix / max(1e-3, float(np.max(np.abs(mix)))) * 0.85).astype(np.float32)


def build_audio(path):
    n = int(TOTAL * SR)
    mus = music(TOTAL)
    voice = np.zeros(n, np.float32)
    for s in SCENES:
        add(voice, load_wav(s["file"]), s["start"], 1.0)
    envv = np.convolve(np.abs(voice), np.ones(int(0.25 * SR)) / int(0.25 * SR), "same")
    duck = 1 - 0.6 * np.clip(envv / 0.03, 0, 1)
    gain = np.full(n, 0.30, np.float32)
    a, b = int(SHOW_T0 * SR), int(SHOW_T1 * SR)
    fade = int(0.35 * SR)
    gain[a:b] = 0.0           # en el tramo de la app se apaga la música de fondo
    gain[max(0, a - fade):a] = np.linspace(0.30, 0.0, a - max(0, a - fade))
    gain[b:b + fade] = np.linspace(0.0, 0.30, len(gain[b:b + fade]))
    mix = gain * mus * duck + voice * 1.2
    app = show_mix(SHOW_LEN)
    ramp = np.ones(len(app), np.float32)
    f2 = min(fade, len(app) // 4)
    ramp[:f2] = np.linspace(0, 1, f2); ramp[-f2:] = np.linspace(1, 0, f2)
    add(mix, app * ramp, SHOW_T0, 1.0)
    fx = np.zeros(n, np.float32)
    for s in SCENES[1:]:
        add(fx, sfx_whoosh(), s["start"] - 0.18, 0.3)
    add(fx, sfx_scratch(), SC["despierta"]["start"] - 0.05, 0.5)
    for nd in ("caras", "internet"):
        add(fx, sfx_pop(), word_t("harto", nd) + 0.25, 0.6)
    for nd in ("dj", "luces", "ecualizador", "anuncios", "supuesto"):
        add(fx, sfx_pop(), word_t("plats", nd) - 0.1, 0.5)
    for nd in ("nada", "amigo", "desconfiado"):
        add(fx, sfx_pop(), word_t("amigo", nd) - 0.15, 0.45)
    add(fx, sfx_ding(), word_t("llego", "llegó"), 0.9)
    add(fx, sfx_ding(), word_t("cierre", "daun"), 0.7)
    mix += fx
    mix /= max(1.0, np.max(np.abs(mix)) / 0.95)
    pcm = (mix * 32767).astype(np.int16)
    with wave.open(str(path), "w") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR); w.writeframes(pcm.tobytes())


def main():
    if len(sys.argv) > 1 and sys.argv[1] == "frames":
        for key in SC:
            s = SC[key]
            for f_ in (0.35, 0.75):
                tt = s["start"] + (s["end"] - s["start"]) * f_
                bgc = Image.new("RGBA", (W, H), (30, 10, 40, 255))
                bgc.alpha_composite(render(tt))
                bgc.convert("RGB").resize((360, 640)).save(OUT_DIR / f"f_{key}_{f_}.png")
        print("vistas previas listas"); return
    print(f"duración total {TOTAL:.1f} s")
    bg, show = build_background()
    wav = OUT_DIR / "audio.wav"
    build_audio(wav)
    cmd = [FFMPEG, "-v", "error", "-y", "-i", str(bg), "-i", str(show),
           "-f", "rawvideo", "-pix_fmt", "rgba", "-s", f"{W}x{H}", "-r", str(FPS), "-i", "-", "-i", str(wav),
           "-filter_complex",
           f"[1:v]setpts=PTS-STARTPTS+{SHOW_T0:.3f}/TB[s];[0:v][s]overlay=enable='between(t,{SHOW_T0:.3f},{SHOW_T1:.3f})'[b];[b][2:v]overlay=format=auto[v]",
           "-map", "[v]", "-map", "3:a", "-c:v", "libx264", "-preset", "medium", "-crf", "19", "-pix_fmt", "yuv420p",
           "-c:a", "aac", "-b:a", "192k", "-movflags", "+faststart", "-t", f"{TOTAL:.2f}", str(FINAL)]
    p = subprocess.Popen(cmd, stdin=subprocess.PIPE)
    nf = int(TOTAL * FPS)
    for fi in range(nf):
        p.stdin.write(render(fi / FPS).tobytes())
        if fi % 150 == 0:
            print(f"cuadro {fi}/{nf}")
    p.stdin.close(); p.wait()
    print("listo:", FINAL)


if __name__ == "__main__":
    main()
