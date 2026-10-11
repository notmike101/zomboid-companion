$ErrorActionPreference = 'Stop'
$gameExe = 'C:\Games\steam\steamapps\common\ProjectZomboid\ProjectZomboid64.exe'
$games = @(Get-CimInstance Win32_Process -Filter "Name = 'ProjectZomboid64.exe'" |
    Where-Object { $_.ExecutablePath -eq $gameExe })
if ($games.Count -ne 1) {
    throw "Expected one running game at $gameExe; found $($games.Count)."
}
$readerJar = Join-Path $PSScriptRoot 'inventory-reader-v2.jar'
if (-not (Test-Path -LiteralPath $readerJar -PathType Leaf)) {
    throw "Reader is not built: $readerJar. See README.md."
}
& java -jar $readerJar $games[0].ProcessId inventory
if ($LASTEXITCODE -ne 0) { throw "Inventory reader failed with exit code $LASTEXITCODE." }
