# DownSong (antes MegaDown): diseño acordado

> Decisiones cerradas con Gabo. Fuente de verdad para las siguientes fases.

## Identidad
- Nombre: **DownSong**. Logo: "ondas de sonido" (fondo negro, monograma DG contorneado en rojo,
  la cola de la G es una flecha hacia abajo, ondas rojas concéntricas y brillo rojo).
- Se mantiene applicationId `com.gabo.ytmp3` (para actualizar encima). Las releases suben también
  `MegaDown-*.apk` para que el actualizador de la v1.7 encuentre la versión nueva.

## Bienvenida (solo la primera vez)
1. Pantalla de inicio negra con el logo al centro (tipo Spotify).
2. Bienvenida: fondo negro con notas musicales blancas flotando + "Empezar".
3. Perfil: apodo obligatorio (máx. 20 caracteres, emojis permitidos); foto opcional.
   Sin foto → avatar caleidoscopio generado. Un solo perfil.
4. Permisos explicados uno por uno (notificaciones, música del celular, batería) con "Ahora no".
5. "¿Importamos la música que ya tienes?".
6. Entra a la Biblioteca (sin saludo).
- Sin sensores, sin copia de seguridad, sin pregunta de gustos.

## Biblioteca y reproductor (al estilo Spotify)
- Música guardada en privado dentro de la app (no visible para el usuario).
- Biblioteca central sin duplicados; las playlists apuntan a las canciones.
- Botón + en la playlist para agregar canciones del celular (se leen en su lugar, sin copiarlas).
- Al bajar un MP4 se guarda también el MP3 en silencio: con el video apagado suena el audio.
- Mantener presionada una canción: cambiar nombre o portada.
- Tocar una canción abre el reproductor; atrás vuelve a la playlist. Deslizar abajo cierra.
  Deslizar la portada a los lados cambia de canción.
- Fondo: degradado del color de la portada, como Spotify.
- Aleatorio: mezcla solo la playlist actual. Al terminar: se detiene salvo repetir o aleatorio.
- "Mostrar video" por playlist: las canciones con video lo muestran.
- Sin portada → efecto caleidoscopio animado que reacciona al sonido (activado por defecto;
  opción para aplicarlo a todas las canciones).

## Ajustes (estilo iPhone, una pantalla por categoría)
Reproducción · Apariencia · Pantalla de bloqueo · Descargas · Biblioteca y almacenamiento ·
Cuentas · Perfil · Actualizaciones · Apoya DownSong · Acerca de.
- Crossfade activado, 5 s por defecto (1–12 s, se puede apagar).
- Nivelar volumen activado por defecto.
- Tema: Claro / Oscuro / Sistema / Dinámico. Por defecto **Oscuro**.
- Almacenamiento: peso por playlist y borrar los videos para liberar espacio.
- Pantalla de bloqueo: canción, ⏮ ⏯ ⏭, aleatorio y repetir, activado por defecto.

## Descargar (fase 2 en adelante)
- Pestañas: YouTube, Spotify, TikTok, Instagram, Facebook.
- Réplicas nativas propias y livianas que muestran solo contenido descargable; ícono de usuario
  arriba a la derecha para iniciar sesión. Analizar bloqueos de Spotify por volumen de descargas.
- Panel que se esconde con snap, hoja "Guardar en playlist", vista previa ▶ en la portada.

## Fases
0. Nombre, logo, bienvenida y perfil. 1. Biblioteca + reproductor + ajustes.
2. Descargar rediseñado + réplica de YouTube. 3. Réplicas de Spotify/TikTok/IG/FB. 4. Compartir con QR.

## v2.2 (publicada)
Pantalla completa con cristal, réplicas con recuadro que se esconde y barritas, pantalla de bloqueo propia,
cierre real al quitar la app de recientes, hoja de actualización ("Más tarde" = mañana), nueva pantalla para
agregar música, ecualizador con perfiles, aleatorio que prioriza lo menos escuchado, gestos en el mini-reproductor,
estadísticas del mes, modos Máximo/Equilibrado/Ahorro, saltar silencios, visualizador OpenGL, Modo DJ
(análisis BPM/coro/pausas, 11 efectos, Saltar animado, 12 estados de ánimo, IA YAMNet + diccionario + aprendizaje,
DJ desde playlist y desde canción).

## v2.3
- **Modo DJ infinito:** usa toda la playlist; cada canción se divide en partes (intro, versos, coros, final) y cada vez
  suena una parte distinta (memoria por canción). Duración variable (30 s a casi completa), nunca termina.
- **Mezclas de DJ en vivo (sin sonidos agregados):** consola por plato con ecualizador de 3 bandas (cruce Linkwitz-Riley),
  la que entra llega sin bajos y con voces bajas, cambio de bajos en el compás; barrido de filtro; eco a tempo con cola sin
  graves; loop que crece (1 compás → ½ tiempo) hasta el drop; corte en el golpe solo si sube la energía; frenado (raro).
  Reglas: mezclas largas solo si tono (Camelot) y velocidad combinan; si no, eco o corte. Fraseo de 2/4/8 compases según
  género, pulso que mantiene alineados los golpes, olas de energía. "Creatividad de las mezclas" en Ajustes.
- **Análisis en segundo plano** (hilo de prioridad baja): se prepara la playlist que suena; el DJ arranca al instante y suma
  canciones en vivo. Ahorro: solo con el cargador.
- **Arreglos de cortes:** foco de audio propio, rescate si un archivo termina o falla, el corte nunca pasa del final real,
  servicio que no se reabre solo (notificación pegada), "caja negra" en Ajustes → Acerca de.
- **Disco B:** vinilo DownSong (ondas rojas del logo) con la portada de la playlist; salta con los bajos.
- **Visualizador del DJ:** efectos al azar y reacción fuerte al bombo, destellos en los drops y en cada mezcla.
- **Descargar:** buscador único estilo YouTube (filtros, voz, menú), flecha atrás, descargas de TikTok/IG/FB/Spotify sin
  salir de la red (hoja encima con formato), y "Guardar también en el celular" eligiendo la carpeta con la pantalla de carpetas.

## v2.4
- Pantalla del DJ: un solo plato abajo (25 % más chico), estilo jog wheel con la portada en todo el disco y aro del color
  que domina en el fondo; scratch (horario adelanta, antihorario retrocede); toque = saltar.
- Crossfader debajo del plato: deslizar hacia B mezcla a mano (ecualizador de 3 bandas, golpes alineados); en las mezclas
  automáticas se mueve solo. Sin botón AUTO.
- Nombre y datos más chicos (−25 %), semitransparentes y junto al disco.
- Inicio: las tarjetas del DJ usan el visualizador nuevo.
- Descargar: "Buscar en <red> o pegar enlace" que se desplaza para leerse completo.
- Spotify: cada canción baja con su propia portada (no la de la playlist).

## v2.5
- Visualizador nuevo en toda la app (DJ, ánimo, Inicio, reproductor, bloqueo): túnel 3D de fractales de órbitas
  (fórmula Hopalong generalizada) que se regeneran sin fin, luces con 6 formas, la música pinta la profundidad
  (cerca bajos, lejos agudos), estela, bloom, espejos de caleidoscopio y filtros; escenas al azar con los golpes y
  las mezclas. Técnicas inspiradas en AudiOrbits, código propio. Calidad según el modo de uso.
- Modo DJ: solo el disco (sin barra) y partes de canción más largas (50 s a 3 min 20 s).
- Spotify en Descargar: se concede "contenido protegido" (DRM) a la réplica: ya no sale "Reproductor deshabilitado".

## v2.6
- Descargar: lupa en lugar del micrófono (busca, o abre el teclado si está vacío).
- Las réplicas web terminan arriba de la barrita inferior: su menú (p. ej. "Tu biblioteca" de Spotify) ya no queda tapado.
- Spotify en modo escritorio (agente de navegador de PC + zoom): su web móvil bloquea la biblioteca y pide la app.
- Enlace copiado (Compartir → Copiar enlace) → aviso "Descargar" al abrir la pestaña.

## v2.7
- Descargar: atrás siempre visible arriba a la izquierda (retrocede en la página o vuelve a la Biblioteca); sin el logo repetido.
- YouTube ahora es su web móvil dentro de la app (explorar, tus playlists, buscar con miniaturas, iniciar sesión);
  botón "Descargar" en videos, shorts y playlists. Al salir de la pestaña se pausa el video de la web.

## v2.8
- Spotify sin límite de 100: la playlist/álbum se lee completa desde la réplica (la página baja sola y se toman
  todas las filas). Si se pega un enlace con 100+, se abre en la pestaña Spotify y se lee entera. Portada por canción.
- Cada red recuerda solo direcciones de su propio sitio (ya no aparece TikTok al cambiar a YouTube/Spotify).
- La tarjeta "¡Listo!" de descargas se oculta sola (6 s; 15 s si hubo errores) o al tocarla.
- Enlaces pegados en cualquier red: hoja de guardar encima, sin salir de la red.

## v2.9 (arreglo de cierre al abrir)
- Causa: Coil cargaba portadas como bitmaps de hardware y la barra de cristal (BlurView) dibuja por software →
  "Software rendering doesn't support hardware bitmaps". Con portadas reales (descargas de Spotify) la app se cerraba
  al abrir Inicio. Arreglo: ImageLoader global con allowHardware(false).
- Analizador: máximo 12 minutos por archivo (los mixes de horas llenaban la memoria), canción que cierra la app se salta,
  modo seguro tras 2 cierres seguidos al arrancar.
- Registro de errores: detalle del cierre guardado y aviso para enviarlo en el siguiente inicio.

## v2.10
- Playlists: botón Repetir (no → toda la playlist → una canción) y botón Ordenar (personalizado, recién añadidas,
  título, artista, duración corta/larga, más escuchadas; se recuerda por playlist y la cola sigue ese orden).
- Fin de la lista sin repetir: vuelve a la primera canción y queda en pausa (antes con aleatorio seguía sin fin).
- Si 4 canciones seguidas no se pueden reproducir, se detiene en vez de saltar sin parar. Saltar silencios solo
  recorta si el resultado es razonable (si no, la canción "terminaba" al instante y pasaba a la siguiente).
- Agregar música del celular: miniaturas con tope de memoria (algunos celulares dan carátulas de 1000+ px) y errores
  al agregar avisan en vez de cerrar la app.
- Descargas: "Cancelar descarga" por encima de la barrita inferior. YouTube: un video abierto desde un Mix/lista
  baja solo ese video. Spotify: resultados a todo el ancho y aviso de cookies cerrado.

## v2.11 (duraciones y cortes)
- Causa: MP3 de bitrate variable sin cabecera Xing/VBRI. ExoPlayer estimaba la duración con el bitrate del primer cuadro
  (3:00 → 13:40). Con esa duración falsa el fundido y los recortes caían en cualquier parte y la barra no servía.
- Arreglo: Mp3Extractor con FLAG_ENABLE_INDEX_SEEKING (tiempos exactos) y duración de respaldo medida por el analizador
  (`durMs` del servicio, usada por la pantalla, la mini barra, el DJ, la pantalla de bloqueo y el fundido).
- Analizador v3: compara las muestras contadas con el reloj del archivo y corrige la escala si no coinciden; todas las
  canciones se vuelven a analizar. "Saltar silencios" solo usa análisis v3.

## v2.12 (asistente "Descargar todas mis playlists")
- Descargar › ⋯ (YouTube o Spotify, con sesión iniciada en la réplica): asistente en 3 pasos.
  1) busca tus playlists (YouTube: `yt-dlp --flat-playlist` de /feed/playlists con tus cookies + "Videos que me gustan";
     Spotify: barra lateral "Tu biblioteca" leída en la réplica, incluye "Tus me gusta");
  2) lee cada una COMPLETA y cuenta sus canciones (sin el tope de 100);
  3) muestra "N playlists · M canciones" con el conteo de cada una para marcar cuáles bajar.
- Cada playlist se guarda en una playlist de DownSong con el mismo nombre (VideoItem.extraTargets); las canciones
  repetidas se bajan una sola vez. La descarga sigue en segundo plano con su notificación de avance.
- Spotify: portada de cada canción en alta resolución (640 px, ab67616d0000b273).
- Sin sesión de Spotify detectada se puede seguir con "Ya inicié sesión". La app solo mira si existe la cookie de
  sesión, nunca lee ni usa su valor.
