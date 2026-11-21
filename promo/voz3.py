"""Locución del anuncio de DownSong para TikTok (tono burlón, voz neuronal es-MX con edge-tts).
Guarda cada frase en WAV y el tiempo exacto de cada palabra (para los subtítulos tipo karaoke).
El texto va escrito "como suena" para que la voz pronuncie bien las marcas."""
import asyncio
import json
import subprocess
from pathlib import Path

import edge_tts

ROOT = Path(__file__).resolve().parent
OUT = ROOT / "build" / "voz3"
OUT.mkdir(parents=True, exist_ok=True)
FFMPEG = r"C:\Users\GABO\AppData\Local\Microsoft\WinGet\Packages\yt-dlp.FFmpeg_Microsoft.Winget.Source_8wekyb3d8bbwe\ffmpeg-N-127043-g5a54fcf75e-win64-gpl\bin\ffmpeg.exe"
VOICE = "es-MX-JorgeNeural"
RATE = "+28%"
PITCH = "+4Hz"

LINES = [
    ("hey",       "¡Hey, tú! ¡Sí, tú!"),
    ("harto",     "¿Harto de pagar suscripciones caras? ¿Cansado de que tu música dependa del internet?"),
    ("playlist",  "¿Todavía escuchas esa playlist que hiciste pensando en ella? ¿Te juró amor eterno... y un día, simplemente, dejó de hablarte?"),
    ("despierta", "Despierta, compadre. Ella no está ocupada, no está confundida, y tampoco necesita tiempo. Simplemente... ya tiene a otro."),
    ("amigo",     "Sí, ese que decía: nada que ver, es solo un amigo. ¡Qué desconfiado eres!"),
    ("fotos",     "Todavía guardas sus fotos, la piensas en las noches, le escribes todos los días... y te deja en visto."),
    ("llego",     "Bien, no me cambies de tema. ¡Llegó... Daun Song!"),
    ("descarga",  "Guarda tus playlists favoritas, y disfruta de tu música sin depender de internet."),
    ("plats",     "¿Mezclar como un DJ? ¡Lo tenemos! ¿Luces que bailan con tu música? ¡Lo tenemos! ¿Ecualizador y cero anuncios? ¡Por supuesto!"),
    ("mezclas",   "Y nuestra app hace las mejores mezclas."),
    ("qr",        "Encuéntrala en el link de mi perfil."),
    ("cierre",    "Y olvídala papito, déjala ser feliz. ¡Daun Song!"),
    ("playstore", "Pruébala gratis. El link está en mi perfil."),
]


async def say(key, text):
    mp3 = OUT / f"{key}.mp3"
    words = []
    com = edge_tts.Communicate(text, VOICE, rate=RATE, pitch=PITCH, boundary="WordBoundary")
    with open(mp3, "wb") as f:
        async for ch in com.stream():
            if ch["type"] == "audio":
                f.write(ch["data"])
            elif ch["type"] == "WordBoundary":
                words.append({"w": ch["text"], "t": ch["offset"] / 1e7, "d": ch["duration"] / 1e7})
    wav = OUT / f"{key}.wav"
    subprocess.run([FFMPEG, "-v", "error", "-y", "-i", str(mp3), "-ar", "44100", "-ac", "1", str(wav)], check=True)
    out = subprocess.run([FFMPEG.replace("ffmpeg.exe", "ffprobe.exe"), "-v", "error", "-show_entries", "format=duration",
                          "-of", "default=nw=1:nk=1", str(wav)], capture_output=True, text=True, check=True)
    return {"key": key, "text": text, "file": str(wav), "dur": round(float(out.stdout.strip()), 3), "words": words}


async def main():
    meta = []
    for k, t in LINES:
        m = await say(k, t)
        meta.append(m)
        print(f"{k:10s} {m['dur']:5.2f}s  {len(m['words'])} palabras")
    (OUT / "voz.json").write_text(json.dumps(meta, ensure_ascii=False, indent=1), encoding="utf-8")
    print("total", round(sum(m["dur"] for m in meta), 2), "s")


asyncio.run(main())
