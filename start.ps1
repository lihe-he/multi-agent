$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
if (-not (Test-Path -LiteralPath 'node_modules\electron')) {
    npm.cmd run setup
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}
npm.cmd start
exit $LASTEXITCODE
