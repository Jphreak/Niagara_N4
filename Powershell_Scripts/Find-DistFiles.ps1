# Find-DistFiles.ps1
# Lists backup_IO_B*.dist files under <Root>\provisioningNiagara\stationData\<station>\backups
# with created/modified dates to a CSV.
# Usage: .\Find-DistFiles.ps1 -Root "C:\Some\Folder" -OutFile "C:\temp\dist_dates.csv"
param(
    [string]$Root    = "X:\Niagara_N4",   # folder that contains provisioningNiagara
    [string]$OutFile = "$PSScriptRoot\DistFileDates.csv"
)

$pattern = Join-Path $Root "provisioningNiagara\stationData\*\backups\backup_IO_B*.dist"

Get-ChildItem -Path $pattern -File -ErrorAction SilentlyContinue |
    Select-Object @{n='Station';  e={ $_.Directory.Parent.Name }},
                  Name,
                  @{n='Created';  e={$_.CreationTime.ToString('yyyy-MM-dd HH:mm:ss')}},
                  @{n='Modified'; e={$_.LastWriteTime.ToString('yyyy-MM-dd HH:mm:ss')}},
                  @{n='Folder';   e={$_.DirectoryName}} |
    Export-Csv -Path $OutFile -NoTypeInformation

Write-Host "Wrote $OutFile"
