param([Parameter(Mandatory=$true)][string]$Archive, [Parameter(Mandatory=$true)][string]$AppDirectory)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$appPath = (Resolve-Path -LiteralPath $AppDirectory).Path
$zipPath = (Resolve-Path -LiteralPath $Archive).Path
$root = [IO.Path]::GetFileName($appPath) + '/'
$files = @{}
Get-ChildItem -LiteralPath $appPath -File -Recurse | ForEach-Object {
    $relative = $_.FullName.Substring($appPath.Length + 1).Replace('\','/')
    $files[$relative] = $_.FullName
}
function Stream-Hash($Stream) {
    $hasher = [Security.Cryptography.SHA256]::Create()
    try { return [BitConverter]::ToString($hasher.ComputeHash($Stream)).Replace('-','').ToLowerInvariant() }
    finally { $hasher.Dispose(); $Stream.Dispose() }
}
$zip = [IO.Compression.ZipFile]::OpenRead($zipPath)
try {
    $seen = @{}
    foreach ($entry in $zip.Entries) {
        $name = $entry.FullName.Replace('\','/')
        if ($name.EndsWith('/')) { continue }
        if (-not $name.StartsWith($root,[StringComparison]::Ordinal) -or $name.Contains('../')) { throw "Unexpected ZIP path: $name" }
        $relative = $name.Substring($root.Length)
        if (-not $files.ContainsKey($relative) -or $seen.ContainsKey($relative)) { throw "Unexpected/duplicate ZIP file: $relative" }
        $expected = (Get-FileHash -LiteralPath $files[$relative] -Algorithm SHA256).Hash.ToLowerInvariant()
        if ((Stream-Hash $entry.Open()) -ne $expected) { throw "ZIP checksum mismatch: $relative" }
        $seen[$relative] = $true
    }
    if ($seen.Count -ne $files.Count) { throw 'ZIP omitted release files' }
} finally { $zip.Dispose() }

function Verify-Tiles([string]$Path,[string]$Prefix) {
    $inner = [IO.Compression.ZipFile]::OpenRead($Path)
    try {
        $entries = @{}
        foreach ($entry in $inner.Entries) { $entries[$entry.FullName.Replace('\','/')] = $entry }
        $pngs = @($inner.Entries | Where-Object { $_.FullName.Replace('\','/').StartsWith($Prefix) -and $_.Name.EndsWith('.png') })
        if ($pngs.Count -ne 35) { throw "Expected 35 tile PNGs in $Path" }
        foreach ($png in $pngs) {
            if ($png.Name -notmatch '^([1-9][mps]|[1-7]z|back)\.png$') { throw "Non-KouDianDian tile in package: $($png.FullName)" }
        }
        $manifestEntry = $entries[$Prefix + 'manifest.json']
        if (-not $manifestEntry) { throw 'Missing artwork provenance manifest' }
        $reader = [IO.StreamReader]::new($manifestEntry.Open(),[Text.Encoding]::UTF8)
        try { $manifest = $reader.ReadToEnd() | ConvertFrom-Json } finally { $reader.Dispose() }
        foreach ($asset in $manifest.files) {
            $entry = $entries[$Prefix + $asset.file]
            if (-not $entry -or (Stream-Hash $entry.Open()) -ne $asset.sha256) { throw "Packaged asset mismatch: $($asset.file)" }
        }
        if (@($inner.Entries | Where-Object { $_.FullName -match 'baseline-v[12]|tile-atlas\.png|\.exe$' -and $_.FullName -notmatch '^runtime/' }).Count -gt 0) {
            throw 'An obsolete asset, baseline or executable was included in the source/JAR'
        }
    } finally { $inner.Dispose() }
}
Verify-Tiles (Join-Path $appPath 'app/qingzhu-mahjong.jar') 'assets/tiles/'
Verify-Tiles (Join-Path $appPath 'source.zip') 'src/main/resources/assets/tiles/'
Write-Output "Verified ZIP: $($files.Count) files match SHA-256; JAR and source.zip each contain only 34 faces + one back."
