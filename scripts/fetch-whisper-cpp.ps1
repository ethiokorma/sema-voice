# Windows equivalent of fetch-whisper-cpp.sh
$Tag = if ($env:WHISPER_CPP_TAG) { $env:WHISPER_CPP_TAG } else { "v1.9.5" }
$Root = Split-Path -Parent $PSScriptRoot
$Dest = Join-Path $Root "core\speech\src\main\cpp\whisper.cpp"
if (Test-Path (Join-Path $Dest "include\whisper.h")) { Write-Host "whisper.cpp already present"; exit 0 }
$Tmp = Join-Path $env:TEMP ("wcpp_" + [guid]::NewGuid())
New-Item -ItemType Directory -Force $Tmp | Out-Null
Invoke-WebRequest -Uri "https://github.com/ggml-org/whisper.cpp/archive/refs/tags/$Tag.zip" -OutFile "$Tmp\whisper.zip"
Expand-Archive "$Tmp\whisper.zip" -DestinationPath "$Tmp\x"
$Src = Get-ChildItem "$Tmp\x" | Select-Object -First 1
New-Item -ItemType Directory -Force $Dest | Out-Null
foreach ($d in @("include","src","ggml")) { Copy-Item -Recurse -Force (Join-Path $Src.FullName $d) $Dest }
Copy-Item (Join-Path $Src.FullName "LICENSE") $Dest
Set-Content (Join-Path $Dest "WHISPER_VERSION.txt") $Tag
Remove-Item -Recurse -Force $Tmp
Write-Host "whisper.cpp $Tag ready at $Dest"
