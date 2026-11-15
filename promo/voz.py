"""Genera la locución del video con Piper (voz neuronal es_MX, offline).
Cada frase se guarda aparte para sincronizar las escenas con su duración."""
import json
import subprocess
import wave
from pathlib import Path

ROOT = Path(__file__).resolve().parent
PIPER = ROOT / "tools" / "piper" / "piper" / "piper.exe"
VOICE = ROOT / "tools" / "voices" / "es_MX-claude-high.onnx"
OUT = ROOT / "build" / "voz"
OUT.mkdir(parents=True, exist_ok=True)

# Texto escrito "como suena" para que la voz pronuncie bien marcas y siglas.
LINES = [
    ("ads",     "¿En serio? ¿Todavía bajas tu música de páginas que te llenan de anuncios?"),
    ("popups",  "Tocas descargar... y te sale que ganaste un celular. Cierras uno... ¡y aparecen tres más!"),
    ("basta",   "¡Ya basta, compadre!"),
    ("intro",   "Te presento: Ye Te, Eme Pe Tres."),
    ("pasos",   "Pegas el enlace, eliges eme pe tres o eme pe cuatro, ¡y listo!"),
    ("plats",   "Yutub, Tiktok sin marca de agua, Instagram, Feisbuk... ¡y hasta tus playlists de Espotifai!"),
    ("ultra",   "¿Ciento cincuenta canciones? Activa el modo Ultra, y bájalas varias a la vez, incluso con la pantalla bloqueada."),
    ("cero",    "Cero anuncios. Cero ventanas raras. Cero trucos. Y lo mejor: ¡es gratis!"),
    ("cta",     "Descárgala ya, desde Git Jab. Desarrollado por Gabo Matica."),
    ("cierre",  "Deja de sufrir... y empieza a descargar."),
]

meta = []
for key, text in LINES:
    wav = OUT / f"{key}.wav"
    subprocess.run([str(PIPER), "-m", str(VOICE), "-f", str(wav),
                    "--length_scale", "0.9", "--noise_scale", "0.8", "--noise_w", "0.9",
                    "--sentence_silence", "0.15"],
                   input=text.encode("utf-8"), check=True, capture_output=True)
    with wave.open(str(wav)) as w:
        dur = w.getnframes() / w.getframerate()
        rate = w.getframerate()
    meta.append({"key": key, "text": text, "file": str(wav), "dur": round(dur, 3), "rate": rate})
    print(f"{key:7s} {dur:5.2f}s  {text}")

(OUT / "voz.json").write_text(json.dumps(meta, ensure_ascii=False, indent=1), encoding="utf-8")
print("total", round(sum(m["dur"] for m in meta), 2), "s")
