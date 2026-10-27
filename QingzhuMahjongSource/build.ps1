param([string]$JdkHome = $env:JAVA_HOME, [switch]$TestOnly)
$ErrorActionPreference = 'Stop'
$projectRoot = $PSScriptRoot
Set-Location -LiteralPath $projectRoot

if (-not $JdkHome -or -not (Test-Path -LiteralPath (Join-Path $JdkHome 'bin/jpackage.exe'))) {
    $localJdk = @('../.tools','../../.tools') | ForEach-Object {
        Get-ChildItem -LiteralPath (Join-Path $projectRoot $_) -Directory -Filter 'jdk-21*' -ErrorAction SilentlyContinue
    } | Select-Object -First 1
    if ($localJdk) { $JdkHome = $localJdk.FullName }
}
if (-not $JdkHome -or -not (Test-Path -LiteralPath (Join-Path $JdkHome 'bin/jpackage.exe'))) {
    throw 'A Windows x64 JDK 21+ is required to build. Pass -JdkHome C:\path\to\jdk. Players do not need a JDK.'
}
$JdkHome = (Resolve-Path -LiteralPath $JdkHome).Path
function Invoke-Jdk([string]$Tool, [string[]]$Arguments) {
    & (Join-Path $JdkHome "bin/$Tool.exe") @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Tool failed with exit code $LASTEXITCODE" }
}
function Reset-Output([string]$Relative) {
    $target = [IO.Path]::GetFullPath((Join-Path $projectRoot $Relative))
    if (-not $target.StartsWith($projectRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Output path escaped the project: $target"
    }
    if (Test-Path -LiteralPath $target) { Remove-Item -LiteralPath $target -Recurse -Force }
    New-Item -ItemType Directory -Path $target -Force | Out-Null
}
Reset-Output 'build/classes'
Reset-Output 'build/tests'
Reset-Output 'build/input'
$artDir = Join-Path $projectRoot 'src/main/resources/assets/tiles'
$manifest = Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $artDir 'manifest.json') | ConvertFrom-Json
$pngs = @(Get-ChildItem -LiteralPath $artDir -File -Filter '*.png')
if ($pngs.Count -ne 35 -or @($pngs | Where-Object Name -NotMatch '^([1-9][mps]|[1-7]z|back)\.png$').Count -gt 0) {
    throw 'Only 34 standard faces and one back may be packaged'
}
if (@($manifest.files).Count -ne 35) { throw 'Invalid artwork manifest' }
foreach ($asset in $manifest.files) {
    if ((Get-FileHash -LiteralPath (Join-Path $artDir $asset.file) -Algorithm SHA256).Hash -ne $asset.sha256) { throw "Artwork checksum mismatch: $($asset.file)" }
}
$sources = @(Get-ChildItem src/main/java -Recurse -Filter '*.java' | ForEach-Object FullName)
Invoke-Jdk javac (@('-J-Duser.language=en', '-encoding', 'UTF-8', '--release', '21', '-d', 'build/classes') + $sources)
Copy-Item -Path 'src/main/resources/*' -Destination 'build/classes' -Recurse -Force
$tests = @(Get-ChildItem src/test/java -Recurse -Filter '*.java' | ForEach-Object FullName)
Invoke-Jdk javac (@('-J-Duser.language=en', '-encoding', 'UTF-8', '--release', '21', '-cp', 'build/classes', '-d', 'build/tests') + $tests)
$testOutput = & (Join-Path $JdkHome 'bin/java.exe') '-ea' '-cp' 'build/classes;build/tests' 'cn.qingzhu.EngineTest' 'src/test/resources/oracle.tsv'
if ($LASTEXITCODE -ne 0) { throw 'Engine tests failed' }
$testOutput | Write-Output
$testOutput | Set-Content -LiteralPath 'build/test-results.txt' -Encoding UTF8
Invoke-Jdk java @('-Djava.awt.headless=true', '-cp', 'build/classes;build/tests', 'cn.qingzhu.ArtworkTest', 'build/visual')
$networkOutput = & (Join-Path $JdkHome 'bin/java.exe') '-Xmx512m' '-cp' 'build/classes' 'cn.qingzhu.Main' '--network-test' 'build/network'
if ($LASTEXITCODE -ne 0) { throw 'Four-player network tests failed' }
$networkOutput | Write-Output
if ($TestOnly) { return }
Invoke-Jdk jar @('--create', '--file', 'build/input/qingzhu-mahjong.jar', '--main-class', 'cn.qingzhu.Main', '-C', 'build/classes', '.')

$runtime = [IO.Path]::GetFullPath((Join-Path $projectRoot 'build/runtime'))
if (Test-Path -LiteralPath $runtime) {
    if (-not $runtime.StartsWith($projectRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'Invalid runtime output' }
    Remove-Item -LiteralPath $runtime -Recurse -Force
}
Invoke-Jdk jlink @('--module-path', (Join-Path $JdkHome 'jmods'), '--add-modules', 'java.desktop,java.prefs', '--output', $runtime,
    '--strip-debug', '--no-header-files', '--no-man-pages', '--compress=zip-6')

$appDir = [IO.Path]::GetFullPath((Join-Path $projectRoot 'dist/QingzhuKouDianDianOnline'))
if (Test-Path -LiteralPath $appDir) {
    if (-not $appDir.StartsWith($projectRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'Invalid app output' }
    Remove-Item -LiteralPath $appDir -Recurse -Force
}
Invoke-Jdk jpackage @('--type', 'app-image', '--name', 'QingzhuKouDianDianOnline', '--dest', 'dist', '--input', 'build/input',
    '--main-jar', 'qingzhu-mahjong.jar', '--main-class', 'cn.qingzhu.Main', '--runtime-image', $runtime,
    '--icon', 'src/main/resources/assets/icon.ico', '--app-version', '2.2.2', '--vendor', 'Qingzhu',
    '--description', 'Shanxi Kou Dian Dian Mahjong - four player online rooms', '--java-options', '-Xmx512m',
    '--java-options', '-Dfile.encoding=UTF-8')

Copy-Item -LiteralPath 'README.md','THIRD_PARTY_NOTICES.md' -Destination $appDir
Copy-Item -LiteralPath 'RULES_AUDIT.md' -Destination $appDir
Copy-Item -LiteralPath 'RELEASE_VALIDATION.md' -Destination $appDir
Copy-Item -LiteralPath 'licenses' -Destination $appDir -Recurse -Force
Copy-Item -LiteralPath 'build/test-results.txt' -Destination $appDir
Copy-Item -LiteralPath 'build/network/network-results.txt','build/network/network-report.json' -Destination $appDir
Copy-Item -LiteralPath 'MULTIPLAYER_TEST.md','ONLINE_DESIGN.md' -Destination $appDir
Copy-Item -LiteralPath 'tools/test-network.cmd' -Destination $appDir
Copy-Item -LiteralPath 'tools/play.cmd' -Destination $appDir
Copy-Item -LiteralPath 'build/visual/tile-catalog.png' -Destination $appDir
Copy-Item -LiteralPath 'ARTWORK.md' -Destination $appDir
if (Test-Path -LiteralPath 'validation/final-ui-2.2.2/online-smoke-report.json') {
    Copy-Item -LiteralPath 'validation/final-ui-2.2.2/online-smoke-report.json' -Destination $appDir
}
$sourceZip = Join-Path $appDir 'source.zip'
$sourceItems = @('src','tools','build.ps1','README.md','ARTWORK.md','RULES_AUDIT.md','MULTIPLAYER_TEST.md','ONLINE_DESIGN.md','THIRD_PARTY_NOTICES.md','licenses')
$sourceItems += 'RELEASE_VALIDATION.md'
Compress-Archive -Path $sourceItems -DestinationPath $sourceZip -Force
$hashes = @('QingzhuKouDianDianOnline.exe','app/qingzhu-mahjong.jar') | ForEach-Object {
    $hash = Get-FileHash -LiteralPath (Join-Path $appDir $_) -Algorithm SHA256
    $hash.Hash.ToLowerInvariant() + '  ' + $_
}
$hashes | Set-Content -LiteralPath (Join-Path $appDir 'SHA256SUMS.txt') -Encoding ASCII
Compress-Archive -LiteralPath $appDir -DestinationPath 'dist/QingzhuKouDianDianOnline-2.2.2-Windows-x64.zip' -Force
& (Join-Path $projectRoot 'tools/verify-package.ps1') -Archive (Join-Path $projectRoot 'dist/QingzhuKouDianDianOnline-2.2.2-Windows-x64.zip') -AppDirectory $appDir
Write-Output "Ready: $appDir\QingzhuKouDianDianOnline.exe"
