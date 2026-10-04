param(
    [string]$Ffmpeg = 'ffmpeg',
    [string]$OutputDirectory = (Join-Path $PSScriptRoot '..\..\.tools\perf-input')
)
$ErrorActionPreference = 'Stop'
New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
& $Ffmpeg -hide_banner -loglevel error -y -f lavfi -i 'sine=frequency=440:duration=180:sample_rate=48000' -ac 2 -c:a pcm_s16le (Join-Path $OutputDirectory 'long.wav')
if ($LASTEXITCODE -ne 0) { throw 'WAV fixture generation failed' }
& $Ffmpeg -hide_banner -loglevel error -y -i (Join-Path $OutputDirectory 'long.wav') -c:a libmp3lame -b:a 128k (Join-Path $OutputDirectory 'long.mp3')
if ($LASTEXITCODE -ne 0) { throw 'MP3 fixture generation failed' }
& $Ffmpeg -hide_banner -loglevel error -y -i (Join-Path $OutputDirectory 'long.wav') -c:a libvorbis -q:a 3 (Join-Path $OutputDirectory 'long.ogg')
if ($LASTEXITCODE -ne 0) { throw 'OGG fixture generation failed' }
& $Ffmpeg -hide_banner -loglevel error -y -f lavfi -i 'sine=frequency=330:duration=10:sample_rate=44100' -ac 2 -c:a libmp3lame -q:a 4 (Join-Path $OutputDirectory 'short-vbr.mp3')
if ($LASTEXITCODE -ne 0) { throw 'VBR fixture generation failed' }
& $Ffmpeg -hide_banner -loglevel error -y -f lavfi -i 'sine=frequency=660:duration=10:sample_rate=24000' -ac 1 -c:a libmp3lame -b:a 64k (Join-Path $OutputDirectory 'short-mono.mp3')
if ($LASTEXITCODE -ne 0) { throw 'Mono fixture generation failed' }
