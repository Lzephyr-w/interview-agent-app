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
    ) -WindowStyle Hidden | Out-Null
}

function Resolve-CommandPath([string[]]$names) {
    foreach ($name in $names) {
        $command = Get-Command -Name $name -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($null -ne $command) {
            if (-not [string]::IsNullOrWhiteSpace($command.Source)) {
                return $command.Source
            }
            return $command.Path
        }
    }
    return $null
}

function Find-PortableExecutable([string[]]$roots, [string]$fileName) {
    foreach ($rootPath in $roots) {
        if (-not (Test-Path -LiteralPath $rootPath -PathType Container)) {
            continue
        }

        $candidate = Get-ChildItem -LiteralPath $rootPath -Filter $fileName -File -Recurse -ErrorAction SilentlyContinue |
            Sort-Object -Property FullName |
            Select-Object -First 1
        if ($null -ne $candidate) {
            return $candidate.FullName
        }
    }
    return $null
}

function ConvertTo-PowerShellLiteral([string]$value) {
    return "'" + $value.Replace("'", "''") + "'"
}

$agentSource = Join-Path $agent "src"
$env:PYTHONPATH = $agentSource

$agentPython = Join-Path $agent ".venv\Scripts\python.exe"
$agentPythonArguments = @()
if (Test-Path -LiteralPath (Join-Path $agent ".venv") -PathType Container) {
    if (-not (Test-Path -LiteralPath $agentPython -PathType Leaf)) {
        throw "agent/.venv is invalid: recreate it with Python 3.10+."
    }
} else {
    $agentPython = Resolve-CommandPath @("py.exe", "py")
    $agentPythonArguments = @("-3.10")
}
if ([string]::IsNullOrWhiteSpace($agentPython)) {
    throw "Python launcher was not found. Install Python 3.10 with py -3.10 support, or create agent/.venv."
}
& $agentPython @agentPythonArguments -c "import sys; sys.exit(0 if sys.version_info >= (3, 10) else 1)"
if ($LASTEXITCODE -ne 0) { throw "Python 3.10+ is required. Fix agent/.venv or install the py -3.10 runtime before starting." }

$portableRoots = @(
    (Join-Path ([System.IO.Path]::GetTempPath()) "interview-agent-tools"),
    (Join-Path $root "tools"),
    (Join-Path $root ".tools")
)

$mavenCommand = Resolve-CommandPath @("mvn.cmd", "mvn")
if ([string]::IsNullOrWhiteSpace($mavenCommand)) {
    $mavenCommand = Find-PortableExecutable $portableRoots "mvn.cmd"
}
if ([string]::IsNullOrWhiteSpace($mavenCommand)) {
    throw "Maven was not found. Add Maven to PATH or extract it to $($portableRoots[0]), $($portableRoots[1]), or $($portableRoots[2])."
}

$javaHome = $env:JAVA_HOME
$javaCommand = $null
$javaCompilerCommand = $null
if (-not [string]::IsNullOrWhiteSpace($javaHome)) {
    $javaCandidate = Join-Path $javaHome "bin\java.exe"
    $javaCompilerCandidate = Join-Path $javaHome "bin\javac.exe"
    if ((Test-Path -LiteralPath $javaCandidate -PathType Leaf) -and (Test-Path -LiteralPath $javaCompilerCandidate -PathType Leaf)) {
        $javaCommand = $javaCandidate
        $javaCompilerCommand = $javaCompilerCandidate
    }
}
if ([string]::IsNullOrWhiteSpace($javaCommand)) {
    $javaCompilerCommand = Resolve-CommandPath @("javac.exe", "javac")
    if (-not [string]::IsNullOrWhiteSpace($javaCompilerCommand)) {
        $javaHome = Split-Path (Split-Path $javaCompilerCommand -Parent) -Parent
        $javaCommand = Join-Path $javaHome "bin\java.exe"
    }
}
if ([string]::IsNullOrWhiteSpace($javaCommand)) {
    $javaCompilerCommand = Find-PortableExecutable $portableRoots "javac.exe"
    if (-not [string]::IsNullOrWhiteSpace($javaCompilerCommand)) {
        $javaHome = Split-Path (Split-Path $javaCompilerCommand -Parent) -Parent
        $javaCommand = Join-Path $javaHome "bin\java.exe"
    }
}
if ([string]::IsNullOrWhiteSpace($javaCommand) -or -not (Test-Path -LiteralPath $javaCommand -PathType Leaf)) {
    throw "JDK was not found. Install JDK 17+, add it to PATH, or extract it to $($portableRoots[0]), $($portableRoots[1]), or $($portableRoots[2])."
}

$agentCommand = "& $(ConvertTo-PowerShellLiteral $agentPython) $($agentPythonArguments -join ' ') -m interview_agent.server"
$serverCommand = "& $(ConvertTo-PowerShellLiteral $mavenCommand) -B -ntp -s .mvn/settings.xml spring-boot:run"
if (-not [string]::IsNullOrWhiteSpace($javaHome) -and (Test-Path -LiteralPath (Join-Path $javaHome "bin\java.exe") -PathType Leaf)) {
    $javaHomeLiteral = ConvertTo-PowerShellLiteral $javaHome
    $serverCommand = "`$env:JAVA_HOME=$javaHomeLiteral; `$env:Path=($(ConvertTo-PowerShellLiteral (Join-Path $javaHome 'bin')) + ';' + `$env:Path); $serverCommand"
}

Start-DevTerminal $agent $agentCommand
Start-DevTerminal $server $serverCommand
Start-DevTerminal $web "pnpm dev"

Write-Host "Python: $agentPython"
Write-Host "Maven: $mavenCommand"
Write-Host "JDK: $javaCommand"
Write-Host "Started Python Agent, Java server, and Next.js web." -ForegroundColor Green
Write-Host "Web: http://localhost:3000  Java: http://localhost:8080  Agent: http://localhost:8090"
