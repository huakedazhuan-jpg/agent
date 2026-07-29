param(
    [ValidateRange(1, 65535)]
    [int]$Port = 8080,
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location $projectRoot

try {
    $listener = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
    if ($listener) {
        $ownerPid = $listener[0].OwningProcess
        $owner = Get-Process -Id $ownerPid -ErrorAction SilentlyContinue
        $ownerName = if ($owner) { $owner.ProcessName } else { "unknown" }
        throw "Port $Port is already used by PID $ownerPid ($ownerName)."
    }

    docker info *> $null
    if ($LASTEXITCODE -ne 0) {
        throw "Docker Engine is unavailable. Start Docker Desktop and retry."
    }

    docker compose up -d postgres redis
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to start PostgreSQL and Redis."
    }

    $deadline = (Get-Date).AddSeconds(90)
    do {
        $postgresHealth = docker inspect --format "{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}" xingclaw-postgres 2>$null
        $redisHealth = docker inspect --format "{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}" xingclaw-redis 2>$null
        if ($postgresHealth -eq "healthy" -and $redisHealth -eq "healthy") {
            break
        }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)

    if ($postgresHealth -ne "healthy" -or $redisHealth -ne "healthy") {
        throw "Infrastructure did not become healthy. PostgreSQL=$postgresHealth Redis=$redisHealth"
    }

    if (-not $SkipBuild) {
        & .\mvnw.cmd -DskipTests package
        if ($LASTEXITCODE -ne 0) {
            throw "Maven package failed."
        }
    }

    $jar = Join-Path $projectRoot "target\agent-0.0.1-SNAPSHOT.jar"
    if (-not (Test-Path $jar)) {
        throw "Executable JAR not found: $jar"
    }

    Write-Host "Starting XingClaw Agent on http://localhost:$Port"
    Write-Host "Keep this window open. Press Ctrl+C to stop the application."

    & java -jar $jar `
        "--server.port=$Port" `
        "--spring.flyway.enabled=true" `
        "--agent.runtime.repository=jdbc" `
        "--agent.memory.repository=jdbc" `
        "--spring.devtools.restart.enabled=false" `
        "--spring.devtools.livereload.enabled=false"
} finally {
    Pop-Location
}
