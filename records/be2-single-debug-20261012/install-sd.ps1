param(
    [Parameter(Mandatory=$true)][string]$PackagePath,
    [Parameter(Mandatory=$true)][string]$LogPath
)
$ErrorActionPreference = 'Stop'
$partition = Get-Partition -DriveLetter E
$disk = Get-Disk -Number $partition.DiskNumber
$volume = Get-Volume -DriveLetter E
if ($partition.DiskNumber -ne 1 -or $partition.PartitionNumber -ne 1 -or
    $partition.Size -ne 268435456 -or $partition.Offset -ne 1048576 -or
    $disk.Size -ne 63864569856 -or $disk.BusType -ne 'USB' -or
    $volume.FileSystem -ne 'FAT32' -or $volume.FileSystemLabel -ne 'BREEZEBOOT') {
    throw 'SD identity changed: expected disk1/partition1/E/BREEZEBOOT/FAT32 on the 64GB USB reader'
}
$manifest = Get-Content -Raw -LiteralPath (Join-Path $PackagePath 'manifest.json') | ConvertFrom-Json
$names = @('Image', 'fw_jump.bin', 'flow-be2-single.dtb', 'serial-handoff.bin', 'boot.json')
$manifestNames = @($manifest.PSObject.Properties.Name)
if (@(Compare-Object ($names | Sort-Object) ($manifestNames | Sort-Object)).Count -ne 0) {
    throw 'Expected exactly the five single-hart SD boot files'
}
$bytes = 0
foreach ($name in $names) {
    $file = Join-Path $PackagePath $name
    $expected = $manifest.$name
    if ((Get-Item -LiteralPath $file).Length -ne $expected.bytes -or
        (Get-FileHash -Algorithm SHA256 -LiteralPath $file).Hash.ToLower() -ne $expected.sha256) {
        throw "Source package verification failed: $name"
    }
    $bytes += $expected.bytes
}
$destinationRoot = 'E:\'
$stamp = (Get-Date).ToUniversalTime().ToString('yyyyMMdd-HHmmss')
$backup = Join-Path $destinationRoot "BE2-backup-$stamp"
$stage = Join-Path $destinationRoot "BE2-stage-$stamp"
$oldNames = @('Image', 'fw_jump.bin', 'serial-handoff.bin', 'boot.json') +
    @(Get-ChildItem -LiteralPath $destinationRoot -Filter '*.dtb' -File | Select-Object -ExpandProperty Name)
$oldBytes = 0
foreach ($name in $oldNames) {
    $path = Join-Path $destinationRoot $name
    if (Test-Path -LiteralPath $path -PathType Leaf) { $oldBytes += (Get-Item -LiteralPath $path).Length }
}
if ($volume.SizeRemaining -lt ($bytes + $oldBytes + 1048576)) { throw 'Insufficient FAT space for staged files and backup' }
if (Test-Path -LiteralPath $LogPath) { throw 'Installation log already exists' }

function Copy-Durable([string]$source, [string]$destination) {
    $sourceStream = [IO.File]::Open($source, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::Read)
    try {
        $destinationStream = [IO.FileStream]::new($destination, [IO.FileMode]::CreateNew,
            [IO.FileAccess]::Write, [IO.FileShare]::None, 1048576, [IO.FileOptions]::WriteThrough)
        try { $sourceStream.CopyTo($destinationStream); $destinationStream.Flush($true) }
        finally { $destinationStream.Dispose() }
    } finally { $sourceStream.Dispose() }
}

New-Item -ItemType Directory -Path $backup, $stage | Out-Null
$saved = @{}
foreach ($name in $oldNames) {
    $original = Join-Path $destinationRoot $name
    if (Test-Path -LiteralPath $original -PathType Leaf) {
        Copy-Durable $original (Join-Path $backup $name)
        $hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $original).Hash.ToLower()
        if ((Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $backup $name)).Hash.ToLower() -ne $hash) {
            throw "Backup changed: $name"
        }
        $saved[$name] = $hash
    }
}
foreach ($name in $names) {
    Copy-Durable (Join-Path $PackagePath $name) (Join-Path $stage $name)
    if ((Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $stage $name)).Hash.ToLower() -ne $manifest.$name.sha256) {
        throw "Staged data mismatch: $name"
    }
}
$committed = @()
try {
    # boot.json is deliberately last, after its referenced files are present.
    foreach ($name in $names) {
        $target = Join-Path $destinationRoot $name
        $committed += $name
        if (Test-Path -LiteralPath $target) { [IO.File]::Delete($target) }
        Move-Item -LiteralPath (Join-Path $stage $name) -Destination $target
    }
    foreach ($name in $names) {
        $target = Join-Path $destinationRoot $name
        if ((Get-Item -LiteralPath $target).Length -ne $manifest.$name.bytes -or
            (Get-FileHash -Algorithm SHA256 -LiteralPath $target).Hash.ToLower() -ne $manifest.$name.sha256) {
            throw "Final SD verification failed: $name"
        }
    }
} catch {
    foreach ($name in $committed) {
        $target = Join-Path $destinationRoot $name
        if (Test-Path -LiteralPath $target) { [IO.File]::Delete($target) }
        if ($saved.ContainsKey($name)) { Copy-Durable (Join-Path $backup $name) $target }
    }
    throw
}
Remove-Item -LiteralPath $stage
$proof = [ordered]@{disk=1;partition=1;drive='E:';label='BREEZEBOOT';filesystem='FAT32';
    backup=$backup;backup_sha256=$saved;files=$manifest;sha256_errors=0;block_formatting=$false;
    utc=(Get-Date).ToUniversalTime().ToString('o')}
$json = $proof | ConvertTo-Json -Depth 8
[IO.File]::WriteAllText($LogPath, $json, [Text.UTF8Encoding]::new($false))
$json
