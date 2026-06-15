# Backwards-compatibility shim.
#
# The pipeline is now unified in run_pipeline.ps1, which auto-detects tiled vs multiview
# acquisitions and exposes -Sampling anisotropic|isotropic|auto. This shim preserves the old
# entry point by forwarding all arguments and forcing isotropic output (the historical behavior of
# run_isotropic_pipeline.ps1), unless the caller already specified -Sampling.

$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$target = Join-Path $scriptDir "run_pipeline.ps1"

$forwarded = @($args)
$hasSampling = $false
foreach ($a in $forwarded) {
    if ($a -is [string] -and $a -ieq "-Sampling") { $hasSampling = $true; break }
}
if (-not $hasSampling) {
    $forwarded += @("-Sampling", "isotropic")
}

& $target @forwarded
exit $LASTEXITCODE
