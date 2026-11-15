@echo off
title Instalador yt-dlp + FFmpeg
echo [1/2] Instalando yt-dlp...
winget install --id yt-dlp.yt-dlp -e --accept-source-agreements --accept-package-agreements
echo [2/2] Instalando FFmpeg...
winget install --id Gyan.FFmpeg -e --accept-source-agreements --accept-package-agreements
echo.
echo Listo. Cierra esta ventana y usa ytmp3.bat para descargar.
pause
