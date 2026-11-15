# Ingeniería inversa: y2mate.gs (14/11/2026)

## Archivos del frontend
- HTML: formulario con campo `link` y botones MP3/MP4 (el submit se intercepta con JS).
- Script inline: `apiKey` ofuscada (Base64 invertido) mediante `decodeKey()`.
- `/js/1794199711/y2mate.js` (6.7 KB): toda la lógica. Las URLs están en Base64 (`atob`).

## Endpoints decodificados
| Uso | URL |
|---|---|
| Backend real | `eta.etacloud.org/api/v1/...` |
| Respaldo si falla auth | `fancy-sea-5d3d.holy-breeze-fec5.workers.dev/?m=i` (Cloudflare Worker) |
| Plan B ante errores | `tubeapi.org/#<base64 {v,f,r}>` |
| Anuncios | `9hito.com/hid.js?z=10154960` + service worker `sw.hid.js` (push/popunder) |
| Popunder al descargar | `etacloud.org/new/` (se abre una sola vez) |

## Flujo
```
[Usuario pega URL]
   │  regex → videoId (11 chars) + formato mp3/mp4
   │  inyecta script de anuncios (9hito)
   ▼
1. GET eta.etacloud.org/api/v1/auth?api_key=K     → {key, geo}
   (si falla la red → Worker de Cloudflare)
   ▼
2. GET eta.etacloud.org/api/v1/init               → {convertURL}
   Header: Authorization: Bearer <key>
   ▼
3. GET convertURL&v=ID&f=mp3                      → {progressURL, downloadURL, title}
   (puede responder redirect=1 → repite con redirectURL)
   ▼
4. Polling a progressURL cada 3 s                 → progress 0..3
   0 checking · 1 extracting · 2 converting · 3 completed
   ▼
5. Botón Download → downloadURL&v=ID&f=mp3&r=host
   + popunder etacloud.org/new/
```

## Conclusiones
- y2mate.gs no convierte nada: es una fachada (white-label) de **etacloud.org**, que hace la extracción y conversión en sus servidores (muy probablemente con yt-dlp + ffmpeg).
- El negocio está en los anuncios: script de terceros, un service worker (puede enviar notificaciones push) y popunders.
- El backend no se puede replicar porque es privado. Usar su API con su key sería abusar de un servicio ajeno y fallaría cuando roten la key.
- **Réplica equivalente en local:** yt-dlp (pasos 1-4) + ffmpeg (conversión). Ver `ytmp3.bat`.
