Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Import-MedicalRagEnvironment {
    param([Parameter(Mandatory = $true)][string]$RepositoryRoot)
    $configPath = Join-Path $RepositoryRoot '.env'
    if (-not (Test-Path $configPath)) {
        throw '缺少 .env。请先运行 Initialize-Database.ps1，或复制 .env.example 并填写本机数据库配置。'
    }
    $requiredKeys = @('DB_HOST', 'DB_PORT', 'DB_NAME', 'DB_USERNAME', 'DB_PASSWORD', 'SERVER_PORT', 'FRONTEND_PORT')
    $allowedKeys = $requiredKeys + @('SESSION_COOKIE_SECURE', 'APP_STORAGE_ROOT', 'APP_STORAGE_MAX_PDF_BYTES', 'APP_STORAGE_MAX_IMAGE_BYTES', 'APP_IMAGE_MAX_WIDTH', 'APP_IMAGE_MAX_HEIGHT', 'APP_IMAGE_MAX_PIXELS', 'APP_MODEL_KEY_FILE', 'APP_MODEL_ALLOW_LOOPBACK_HTTP')
    foreach ($line in Get-Content -LiteralPath $configPath -Encoding UTF8) {
        $entry = $line.Trim().TrimStart([char]0xFEFF)
        if ($entry.Length -eq 0 -or $entry.StartsWith('#')) { continue }
        $separator = $entry.IndexOf('=')
        if ($separator -lt 1) { throw '.env 格式错误；请使用 KEY=value，每行一项。' }
        $key = $entry.Substring(0, $separator).Trim()
        $value = $entry.Substring($separator + 1).Trim()
        if ($allowedKeys -notcontains $key) { throw ".env 中存在未支持的配置项：$key" }
        [Environment]::SetEnvironmentVariable($key, $value, 'Process')
    }
    foreach ($key in $requiredKeys) {
        if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($key, 'Process'))) {
            throw "请在 .env 填写 $key；配置值不会输出到终端。"
        }
    }
    $secureCookie = [Environment]::GetEnvironmentVariable('SESSION_COOKIE_SECURE', 'Process')
    if (-not [string]::IsNullOrEmpty($secureCookie) -and $secureCookie -notmatch '^(true|false)$') {
        throw 'SESSION_COOKIE_SECURE 只能填写 true 或 false；本机 HTTP 应使用 false。'
    }
    $localModel = [Environment]::GetEnvironmentVariable('APP_MODEL_ALLOW_LOOPBACK_HTTP', 'Process')
    if (-not [string]::IsNullOrEmpty($localModel) -and $localModel -notmatch '^(true|false)$') {
        throw 'APP_MODEL_ALLOW_LOOPBACK_HTTP 只能填写 true 或 false。'
    }
    foreach ($key in @('APP_STORAGE_MAX_PDF_BYTES', 'APP_STORAGE_MAX_IMAGE_BYTES', 'APP_IMAGE_MAX_WIDTH', 'APP_IMAGE_MAX_HEIGHT', 'APP_IMAGE_MAX_PIXELS')) {
        $limitValue = [Environment]::GetEnvironmentVariable($key, 'Process')
        if (-not [string]::IsNullOrEmpty($limitValue)) {
            $limitBytes = [long]0
            if (-not [long]::TryParse($limitValue, [ref]$limitBytes) -or $limitBytes -le 0) {
                throw "$key 必须为正整数；配置值不会输出到终端。"
            }
        }
    }
    foreach ($key in @('DB_PORT', 'SERVER_PORT', 'FRONTEND_PORT')) {
        $port = 0
        if (-not [int]::TryParse([Environment]::GetEnvironmentVariable($key, 'Process'), [ref]$port) -or $port -lt 1 -or $port -gt 65535) {
            throw "$key 必须为 1～65535 的端口号。"
        }
    }
    if ($env:DB_NAME -notmatch '^[a-z][a-z0-9_]*$' -or $env:DB_USERNAME -notmatch '^[a-z][a-z0-9_]*$') {
        throw 'DB_NAME 和 DB_USERNAME 请使用小写字母、数字及下划线，并以字母开头。'
    }
}

function Assert-NativeSuccess {
    param([Parameter(Mandatory = $true)][string]$Message)
    if ($LASTEXITCODE -ne 0) { throw $Message }
}
