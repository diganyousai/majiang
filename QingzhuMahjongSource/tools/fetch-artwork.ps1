# Optional, reproducible asset refresh. The normal build uses the checked-in PNGs.
$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$projectRoot = Split-Path -Parent $PSScriptRoot
$commit = '3e275804ff58325306710bef3a7406860444bc6a'
$assetDir = Join-Path $projectRoot 'src/main/resources/assets/tiles'
New-Item -ItemType Directory -Path $assetDir -Force | Out-Null
$paths = @()
foreach ($suit in @('m','p','s')) {
    foreach ($rank in 1..9) { $paths += "PNG 位图/默认风格 default style/竖 vertical/$rank$suit.png" }
}
foreach ($rank in 1..7) { $paths += "PNG 位图/默认风格 default style/竖 vertical/${rank}z.png" }
$paths += 'PNG 位图/默认风格 default style/背面 back/b_02.png'
$manifest = @()
foreach ($path in $paths) {
    $name = [IO.Path]::GetFileName($path)
    if ($name -eq 'b_02.png') { $name = 'back.png' }
    $encoded = ($path.Split('/') | ForEach-Object { [Uri]::EscapeDataString($_) }) -join '/'
    $target = Join-Path $assetDir $name
    Invoke-WebRequest -UseBasicParsing -Uri "https://raw.githubusercontent.com/lietxia/mahjong_graphic/$commit/$encoded" -OutFile $target
    $manifest += [ordered]@{ file = $name; upstream = $path; sha256 = (Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash.ToLowerInvariant() }
}
$license = Join-Path $projectRoot 'licenses/mahjong-graphic-LICENSE.txt'
Invoke-WebRequest -UseBasicParsing -Uri "https://raw.githubusercontent.com/lietxia/mahjong_graphic/$commit/LICENSE" -OutFile $license
[ordered]@{
    project = 'https://github.com/lietxia/mahjong_graphic'
    commit = $commit
    style = 'default style / vertical; back b_02'
    notes = 'Only 34 standard faces and one back. Upstream 5z=white, 6z=green, 7z=red; engine 31=red, 32=green, 33=white.'
    files = $manifest
} | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $assetDir 'manifest.json') -Encoding UTF8
Write-Output 'Downloaded exactly 34 standard faces and one back.'
