param(
  [Parameter(Mandatory = $true)][string]$BuildDirectory,
  [Parameter(Mandatory = $true)][string]$OutputDirectory
)
$ErrorActionPreference = 'Stop'
$stage = Join-Path $OutputDirectory 'portable'
New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
if (Test-Path $stage) { Remove-Item -Recurse -Force $stage }
cmake --install $BuildDirectory --prefix $stage
Copy-Item (Join-Path $PSScriptRoot '../../../COPYING') (Join-Path $stage 'COPYING.txt')
Copy-Item (Join-Path $PSScriptRoot '../../../NOTICE') (Join-Path $stage 'NOTICE.txt')
$portable = Join-Path $OutputDirectory 'ShackCQ-Windows-x64-portable-v0.1.0-rc.1.zip'
if (Test-Path $portable) { Remove-Item -Force $portable }
Compress-Archive -Path (Join-Path $stage '*') -DestinationPath $portable -CompressionLevel Optimal
cpack --config (Join-Path $BuildDirectory 'CPackConfig.cmake') -G NSIS -B $OutputDirectory
$installer = Join-Path $OutputDirectory 'ShackCQ-Windows-x64-setup-v0.1.0-rc.1.exe'
if (-not (Test-Path $installer)) { throw 'Expected NSIS installer was not produced' }
$portableHash = (Get-FileHash $portable -Algorithm SHA256).Hash.ToLowerInvariant()
$installerHash = (Get-FileHash $installer -Algorithm SHA256).Hash.ToLowerInvariant()
$measure = @{
  portable = @{ path = $portable; bytes = (Get-Item $portable).Length; sha256 = $portableHash }
  installer = @{ path = $installer; bytes = (Get-Item $installer).Length; sha256 = $installerHash }
  unpackedBytes = (Get-ChildItem $stage -Recurse -File | Measure-Object Length -Sum).Sum
}
$measure | ConvertTo-Json -Depth 4 | Set-Content (Join-Path $OutputDirectory 'artifact-measurements.json') -Encoding utf8
Get-Content (Join-Path $OutputDirectory 'artifact-measurements.json')
