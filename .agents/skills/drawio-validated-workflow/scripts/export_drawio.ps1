param(
    [Parameter(Mandatory = $true)]
    [string]$InputPath,

    [Parameter(Mandatory = $true)]
    [string]$OutputPath,

    [string]$DrawioExe = $env:DRAWIO_CLI,

    [int]$Scale = 2,

    [int]$Border = 20
)

$ErrorActionPreference = 'Stop'

if (-not $DrawioExe) {
    $DrawioExe = 'D:\tongminghu\charlib\727\hermes_drawio_tools\drawio-30.0.4-windows\draw.io.exe'
}

$resolvedInput = (Resolve-Path -LiteralPath $InputPath).Path
$resolvedExe = (Resolve-Path -LiteralPath $DrawioExe).Path
$resolvedOutput = [System.IO.Path]::GetFullPath($OutputPath)
$outputDirectory = [System.IO.Path]::GetDirectoryName($resolvedOutput)

if (-not (Test-Path -LiteralPath $outputDirectory -PathType Container)) {
    throw "Output directory does not exist: $outputDirectory"
}

if (Test-Path -LiteralPath $resolvedOutput) {
    throw "Refusing to overwrite existing preview: $resolvedOutput"
}

$arguments = @(
    '--disable-update',
    '--export',
    '--format', 'svg',
    '--border', [string]$Border,
    '--output', $resolvedOutput,
    $resolvedInput
)

$process = Start-Process `
    -FilePath $resolvedExe `
    -ArgumentList $arguments `
    -WindowStyle Hidden `
    -Wait `
    -PassThru

if ($process.ExitCode -ne 0) {
    throw "draw.io CLI failed with exit code $($process.ExitCode)."
}

if (-not (Test-Path -LiteralPath $resolvedOutput -PathType Leaf)) {
    throw "draw.io CLI reported success but output is missing: $resolvedOutput"
}

$outputFile = Get-Item -LiteralPath $resolvedOutput
if ($outputFile.Length -eq 0) {
    throw "draw.io CLI produced an empty output file: $resolvedOutput"
}

[pscustomobject]@{
    input = $resolvedInput
    output = $resolvedOutput
    output_bytes = $outputFile.Length
    drawio_exe = $resolvedExe
    exit_code = $process.ExitCode
    format = 'svg'
    border = $Border
} | ConvertTo-Json
