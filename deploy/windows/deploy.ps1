[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [ValidateSet('build', 'start', 'stop', 'restart', 'status', 'logs')]
    [string]$Action = 'status'
)

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$envFile = if ($env:OCEAN_ENV_FILE) { $env:OCEAN_ENV_FILE } else { Join-Path $projectRoot 'deploy\application.env' }
$runtimeDir = Join-Path $projectRoot 'logs'
$pidFile = Join-Path $runtimeDir 'ocean-admin.pid'
$stdoutLog = Join-Path $runtimeDir 'ocean-admin-console.out.log'
$stderrLog = Join-Path $runtimeDir 'ocean-admin-console.err.log'
$appJar = if ($env:OCEAN_APP_JAR) { $env:OCEAN_APP_JAR } else { Join-Path $projectRoot 'ocean-bootstrap\target\ocean-bootstrap-1.0.0-SNAPSHOT.jar' }
$defaultMagoJar = Join-Path $projectRoot 'ocean-business-geo\tools\mago\mago-3d-terrainer-1.14.2-release.jar'

function Import-ApplicationEnvironment {
    if (-not (Test-Path -LiteralPath $envFile -PathType Leaf)) {
        throw "Configuration not found: $envFile. Copy deploy/application.env.example first."
    }
    foreach ($line in Get-Content -LiteralPath $envFile) {
        $trimmed = $line.Trim()
        if (-not $trimmed -or $trimmed.StartsWith('#')) { continue }
        $parts = $trimmed.Split('=', 2)
        if ($parts.Count -ne 2) { throw "Invalid configuration line: $line" }
        $name = $parts[0].Trim()
        $value = $parts[1].Trim()
        [Environment]::SetEnvironmentVariable($name, $(if ($value) { $value } else { $null }), 'Process')
    }
    if (-not $env:SERVER_PORT) {
        $env:SERVER_PORT = if ($env:APP_PORT) { $env:APP_PORT } else { '8090' }
    }
    if (-not $env:GIS_TERRAIN_MAGO_JAR_PATH) {
        $env:GIS_TERRAIN_MAGO_JAR_PATH = $defaultMagoJar
    }
}

function Assert-Command([string]$Name) {
    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "Required command not found: $Name"
    }
}

function Assert-Runtime {
    Assert-Command 'java'
    $javaVersion = & java -version 2>&1 | Where-Object { $_ -match 'version "' } | Select-Object -First 1
    if (-not $javaVersion -or $javaVersion.ToString() -notmatch '"21(?:\.|\")') {
        throw "Java 21 is required, found: $javaVersion"
    }
    if (-not (Test-Path -LiteralPath $env:GIS_TERRAIN_MAGO_JAR_PATH -PathType Leaf)) {
        throw "Mago JAR not found: $($env:GIS_TERRAIN_MAGO_JAR_PATH). Run git lfs pull."
    }
    if ((Get-Item -LiteralPath $env:GIS_TERRAIN_MAGO_JAR_PATH).Length -le 1000000) {
        throw 'Mago JAR looks like a Git LFS pointer. Run git lfs pull.'
    }
}

function Get-RunningProcess {
    if (-not (Test-Path -LiteralPath $pidFile -PathType Leaf)) { return $null }
    $storedId = (Get-Content -LiteralPath $pidFile -Raw).Trim()
    if ($storedId -notmatch '^\d+$') { return $null }
    $process = Get-CimInstance Win32_Process -Filter "ProcessId = $storedId" -ErrorAction SilentlyContinue
    if (-not $process -or $process.CommandLine -notlike "*$([IO.Path]::GetFileName($appJar))*") { return $null }
    return $process
}

function Build-Application {
    Import-ApplicationEnvironment
    Assert-Runtime
    Assert-Command 'mvn'
    Push-Location $projectRoot
    try {
        & mvn -B -ntp -pl ocean-bootstrap -am clean package -DskipTests
        if ($LASTEXITCODE -ne 0) { throw "Maven build failed with exit code $LASTEXITCODE" }
    } finally {
        Pop-Location
    }
}

function Start-Application {
    Import-ApplicationEnvironment
    Assert-Runtime
    $running = Get-RunningProcess
    if ($running) {
        Write-Host "Ocean Admin Service is already running (PID $($running.ProcessId))."
        return
    }
    if (-not (Test-Path -LiteralPath $appJar -PathType Leaf)) {
        throw "Application JAR not found: $appJar. Run '.\deploy.ps1 build' first."
    }
    New-Item -ItemType Directory -Path $runtimeDir -Force | Out-Null
    Remove-Item -LiteralPath $pidFile -Force -ErrorAction SilentlyContinue
    $arguments = @('-jar', ('"{0}"' -f $appJar))
    $process = Start-Process -FilePath 'java' -ArgumentList $arguments -WorkingDirectory $projectRoot `
        -RedirectStandardOutput $stdoutLog -RedirectStandardError $stderrLog -PassThru
    Set-Content -LiteralPath $pidFile -Value $process.Id -NoNewline
    Start-Sleep -Seconds 2
    if ($process.HasExited) { throw "Application exited during startup. Check $stderrLog." }
    $port = if ($env:APP_PORT) { $env:APP_PORT } else { '8090' }
    Write-Host "Ocean Admin Service started (PID $($process.Id), port $port)."
}

function Stop-Application {
    $running = Get-RunningProcess
    if (-not $running) {
        Remove-Item -LiteralPath $pidFile -Force -ErrorAction SilentlyContinue
        Write-Host 'Ocean Admin Service is not running.'
        return
    }
    Stop-Process -Id $running.ProcessId
    Wait-Process -Id $running.ProcessId -Timeout 30 -ErrorAction SilentlyContinue
    if (Get-Process -Id $running.ProcessId -ErrorAction SilentlyContinue) {
        Stop-Process -Id $running.ProcessId -Force
        Write-Host 'Ocean Admin Service was force-stopped after 30 seconds.'
    } else {
        Write-Host 'Ocean Admin Service stopped.'
    }
    Remove-Item -LiteralPath $pidFile -Force -ErrorAction SilentlyContinue
}

function Show-Status {
    $running = Get-RunningProcess
    if ($running) {
        Write-Host "Ocean Admin Service is running (PID $($running.ProcessId))."
    } else {
        Write-Host 'Ocean Admin Service is not running.'
    }
}

function Show-Logs {
    if (Test-Path -LiteralPath $stdoutLog) { Get-Content -LiteralPath $stdoutLog -Tail 200 -Wait }
    else { Write-Host "Log file does not exist yet: $stdoutLog" }
}

switch ($Action) {
    'build' { Build-Application }
    'start' { Start-Application }
    'stop' { Stop-Application }
    'restart' { Stop-Application; Start-Application }
    'status' { Show-Status }
    'logs' { Show-Logs }
}

