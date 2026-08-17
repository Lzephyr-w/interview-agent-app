param(
    [switch]$NoCheck
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$agent = Join-Path $root "agent"
$server = Join-Path $root "server"
$web = Join-Path $root "web"

foreach ($path in @($agent, $server, $web)) {
    if (-not (Test-Path -LiteralPath $path -PathType Container)) {
        throw "Missing directory: $path"
    }
}

if (-not $NoCheck -and -not (Test-Path -LiteralPath (Join-Path $agent ".env.local"))) {
    Write-Warning "Missing agent/.env.local. Configure the Agent key and model variables first."
}

function Start-DevTerminal([string]$directory, [string]$command) {
    Start-Process -FilePath "powershell.exe" -WorkingDirectory $directory -ArgumentList @(
        "-NoExit", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", $command
    ) | Out-Null
}

$agentSource = Join-Path $agent "src"
$env:PYTHONPATH = $agentSource
Start-DevTerminal $agent "python -m interview_agent.server"
Start-DevTerminal $server "mvn -B -ntp -s .mvn/settings.xml spring-boot:run"
Start-DevTerminal $web "pnpm dev"

Write-Host "Started Python Agent, Java server, and Next.js web." -ForegroundColor Green
Write-Host "Web: http://localhost:3000  Java: http://localhost:8080  Agent: http://localhost:8090"
