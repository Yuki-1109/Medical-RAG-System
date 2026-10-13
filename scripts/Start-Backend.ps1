#Requires -Version 5.1
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repositoryRoot = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'Common.ps1')
Import-MedicalRagEnvironment -RepositoryRoot $repositoryRoot
Get-Command java -ErrorAction Stop | Out-Null
Push-Location (Join-Path $repositoryRoot 'backend')
try {
    & .\mvnw.cmd spring-boot:run
    Assert-NativeSuccess '后端未能运行，请检查上方日志以及 README 的故障排查。'
} finally { Pop-Location }
