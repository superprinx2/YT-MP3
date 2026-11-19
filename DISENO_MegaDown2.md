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
