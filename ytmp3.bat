@echo off
title YouTube a MP3
:loop
set "URL="
set /p "URL=URL de YouTube (Enter vacio para salir): "
if "%URL%"=="" exit /b
yt-dlp -U >nul 2>&1
yt-dlp -x --audio-format mp3 --audio-quality 0 --embed-thumbnail --add-metadata -o "%USERPROFILE%\Music\%%(title)s.%%(ext)s" "%URL%"
echo.
goto loop
