"""Locución del anuncio de MegaDown (tono burlón). Piper, voz masculina es_MX, offline.
Texto escrito "como suena" para que la voz pronuncie bien marcas y siglas."""
import json
import subprocess
import wave
from pathlib import Path

ROOT = Path(__file__).resolve().parent
PIPER = ROOT / "tools" / "piper" / "piper" / "piper.exe"
VOICE = ROOT / "tools" / "voices" / "es_MX-claude-high.onnx"
OUT = ROOT / "build" / "voz2"
OUT.mkdir(parents=True, exist_ok=True)

LINES = [
    ("piratas", "¿Sigues bajando música de páginas piratas?"),
    ("virus",   "Descargas una canción... y te llevas cinco virus, un ransomwer... "
                "¡y dos testigos de Jehová tocándote la puerta!"),
    ("tranqui", "¡Tranquilo, compadre! Llegó Mega Daun."),
    ("plats",   "Aquí descargas de cualquier plataforma: Yutub, Tiktok, Instagram, Feisbuk... ¡y Espotifai!"),
    ("spotify", "¿Aburrido de bajar canción por canción de Espotifai? Usa el modo playlist, "
                "y bájate toda tu música de un jalón."),
    ("ultra",   "En velocidad Ultra: ¡hasta ocho canciones a la vez! Ahorras un montón de tiempo."),
    ("gratis",  "Y lo mejor... ¡es gratis! Ni tu ex se había regalado tanto."),
    ("qr",      "Escanea el código, y descarga la app de una vez."),
    ("cierre",  "Deja de depender del internet, o de pagar Espotifai, para escuchar las mismas diez canciones "
                "aburridas de siempre. Mega Daun: ¡bájalo todo!"),
]

meta = []
for key, text in LINES:
    wav = OUT / f"{key}.wav"
    subprocess.run([str(PIPER), "-m", str(VOICE), "-f", str(wav),
                    "--length_scale", "0.86", "--noise_scale", "0.9", "--noise_w", "1.0",
                    "--sentence_silence", "0.12"],
                   input=text.encode("utf-8"), check=True, capture_output=True)
    with wave.open(str(wav)) as w:
        dur = w.getnframes() / w.getframerate()
    meta.append({"key": key, "text": text, "file": str(wav), "dur": round(dur, 3)})
    print(f"{key:8s} {dur:5.2f}s  {text}")

(OUT / "voz.json").write_text(json.dumps(meta, ensure_ascii=False, indent=1), encoding="utf-8")
print("total", round(sum(m["dur"] for m in meta), 2), "s")
