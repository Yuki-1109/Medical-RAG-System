#Requires -Version 5.1
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repositoryRoot = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'Common.ps1')
Get-Command docker -ErrorAction Stop | Out-Null
docker info --format '{{.ServerVersion}}' | Out-Null
Assert-NativeSuccess 'Docker 尚未就绪，请打开 Docker Desktop，并切换为 Linux containers。'

$configPath = Join-Path $repositoryRoot '.env'
if (-not (Test-Path $configPath)) {
    Copy-Item -LiteralPath (Join-Path $repositoryRoot '.env.example') -Destination $configPath
}
$config = [IO.File]::ReadAllText($configPath)
if ($config -match '(?m)^DB_PASSWORD=\s*$') {
    $bytes = New-Object byte[] 32
    $random = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $random.GetBytes($bytes) } finally { $random.Dispose() }
    $generatedPassword = [Convert]::ToBase64String($bytes)
    $config = [regex]::Replace($config, '(?m)^DB_PASSWORD=[ \t]*\r?$', "DB_PASSWORD=$generatedPassword")
    [IO.File]::WriteAllText($configPath, $config, (New-Object Text.UTF8Encoding $false))
    Write-Host '已在本机 .env 中生成随机数据库密码，不会输出密码。'
}
Import-MedicalRagEnvironment -RepositoryRoot $repositoryRoot
if ($env:DB_HOST -ne '127.0.0.1') { throw '此脚本只初始化本机数据库，请将 DB_HOST 设置为 127.0.0.1。' }

$containerName = 'medical-rag-postgres'
$image = 'pgvector/pgvector:0.8.2-pg17'
$existing = docker container ls --all --filter "name=^/$containerName$" --format '{{.Names}}'
Assert-NativeSuccess '无法检查 Docker 容器。'
if ($existing -eq $containerName) {
    $existingImage = docker inspect --format '{{.Config.Image}}' $containerName
    Assert-NativeSuccess '无法读取现有数据库容器。'
    $existingPort = docker inspect --format '{{(index (index .HostConfig.PortBindings "5432/tcp") 0).HostPort}}' $containerName
    Assert-NativeSuccess '无法读取现有数据库端口。'
    $existingAddress = docker inspect --format '{{(index (index .HostConfig.PortBindings "5432/tcp") 0).HostIp}}' $containerName
    Assert-NativeSuccess '无法读取现有数据库监听地址。'
    if ($existingImage -ne $image -or $existingPort -ne $env:DB_PORT -or $existingAddress -ne '127.0.0.1') {
        throw '现有数据库容器镜像或端口与配置不一致。请保留数据并核对 .env，不要直接删除数据卷。'
    }
    docker start $containerName | Out-Null
    Assert-NativeSuccess '无法启动现有数据库容器。'
} else {
    $previous = @{}
    foreach ($key in @('POSTGRES_DB', 'POSTGRES_USER', 'POSTGRES_PASSWORD')) {
        $previous[$key] = [Environment]::GetEnvironmentVariable($key, 'Process')
    }
    try {
        $env:POSTGRES_DB = $env:DB_NAME
        $env:POSTGRES_USER = $env:DB_USERNAME
        $env:POSTGRES_PASSWORD = $env:DB_PASSWORD
        docker run --detach --name $containerName --publish "127.0.0.1:$($env:DB_PORT):5432" `
            --env POSTGRES_DB --env POSTGRES_USER --env POSTGRES_PASSWORD `
            --mount 'type=volume,source=medical-rag-pgdata,destination=/var/lib/postgresql/data' $image | Out-Null
        Assert-NativeSuccess '数据库创建失败，请检查 Docker、网络或端口是否被占用。'
    } finally {
        foreach ($key in $previous.Keys) { [Environment]::SetEnvironmentVariable($key, $previous[$key], 'Process') }
    }
}

for ($attempt = 0; $attempt -lt 30; $attempt++) {
    docker exec $containerName pg_isready -U $env:DB_USERNAME -d $env:DB_NAME 2>$null | Out-Null
    if ($LASTEXITCODE -eq 0) {
        $previousPassword = [Environment]::GetEnvironmentVariable('PGPASSWORD', 'Process')
        try {
            $env:PGPASSWORD = $env:DB_PASSWORD
            docker exec --env PGPASSWORD $containerName psql --host 127.0.0.1 --username $env:DB_USERNAME --dbname $env:DB_NAME --no-password --command 'SELECT 1;' | Out-Null
            Assert-NativeSuccess '数据库密码或库名不匹配，请核对原 .env；脚本不会改写已有数据库密码。'
        } finally { [Environment]::SetEnvironmentVariable('PGPASSWORD', $previousPassword, 'Process') }
        Write-Host "PostgreSQL 已就绪，监听 127.0.0.1:$($env:DB_PORT)。后端首次启动会通过 Flyway 启用 pgvector。"
        exit 0
    }
    Start-Sleep -Seconds 1
}
throw '数据库尚未就绪，请运行 docker logs medical-rag-postgres 检查原因。'
