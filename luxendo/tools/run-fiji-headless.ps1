param(
    [Parameter(Mandatory = $true, Position = 0)]
    [string] $Macro,

    [Parameter(Position = 1)]
    [string] $MacroArgs = ""
)

$ErrorActionPreference = "Stop"

$toolsDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$fiji = Join-Path $toolsDir "fiji\Fiji.app\ImageJ-win64.exe"

if (-not (Test-Path $fiji)) {
    throw "Fiji launcher not found at $fiji"
}

& $fiji --headless --ij2 --console --macro $Macro $MacroArgs

exit $LASTEXITCODE
