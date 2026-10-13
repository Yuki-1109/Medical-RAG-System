#Requires -Version 5.1
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repositoryRoot = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'Common.ps1')
Import-MedicalRagEnvironment -RepositoryRoot $repositoryRoot
Get-Command npm.cmd -ErrorAction Stop | Out-Null
Push-Location (Join-Path $repositoryRoot 'frontend')
try {
    if (-not (Test-Path 'node_modules')) {
        & npm.cmd ci
        Assert-NativeSuccess '前端依赖安装失败，请检查 Node.js 版本和网络。'
    }
    & npm.cmd run dev
    Assert-NativeSuccess '前端未能运行，请检查上方日志。'
} finally { Pop-Location }
