param(
    [ValidateSet('Start', 'Stop', 'Install', 'Prepare', 'Status')]
    [string]$Action = 'Start',
    [switch]$NoWindow,
    [switch]$NoDialog,
    [string]$CancelFile = '',
    [ValidateRange(5, 600)]
    [int]$StartupTimeoutSeconds = 120
)

$ErrorActionPreference = 'Stop'
if ($PSVersionTable.PSEdition -eq 'Desktop') {
    # Explorer/ShellExecute can inherit a PowerShell 7 module path; prefer Windows' own modules.
    $env:PSModulePath = (Join-Path $PSHOME 'Modules') + ';' + $env:PSModulePath
}
$desktopRoot = [IO.Path]::GetFullPath($PSScriptRoot)
$desktopLogs = Join-Path $desktopRoot 'runtime-logs'
$desktopStatePath = Join-Path $desktopLogs 'desktop-state.json'
$desktopBuildPath = Join-Path $desktopLogs 'desktop-build.json'
$desktopPowerShell = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'

function ConvertTo-DesktopArgument([string]$Value) {
    # Windows CommandLineToArgvW quoting, including trailing backslashes and embedded quotes.
    if ($Value -and $Value -notmatch '[\s"]') { return $Value }
    return '"' + ([regex]::Replace($Value, '(\\*)"', '$1$1\"') -replace '(\\+)$', '$1$1') + '"'
}

function Read-DesktopEnv([string]$Directory) {
    $path = Join-Path $desktopRoot "$Directory\.env.local"
    if (-not (Test-Path -LiteralPath $path)) { throw "Missing $Directory/.env.local. Configure it before starting." }
    $values = @{}
    foreach ($line in Get-Content -LiteralPath $path -Encoding UTF8) {
        if ($line -match '^\s*([A-Z_][A-Z0-9_]*)\s*=(.*)$') {
            $values[$Matches[1]] = $Matches[2].Trim().Trim([char]34, [char]39)
        }
    }
    return $values
}

function Get-DesktopValue($Values, [string]$Name, [string]$Default = '') {
    $inherited = [Environment]::GetEnvironmentVariable($Name)
    if ($null -ne $inherited) { return $inherited }
    if ($Values.ContainsKey($Name)) { return $Values[$Name] }
    return $Default
}

function Assert-DesktopConfig {
    $webConfig = Read-DesktopEnv 'web'
    $serverConfig = Read-DesktopEnv 'server'
    $agentConfig = Read-DesktopEnv 'agent'
    foreach ($group in @(
        @{ Values = $webConfig; Names = @('NEXT_PUBLIC_SUPABASE_URL', 'NEXT_PUBLIC_SUPABASE_ANON_KEY') },
        @{ Values = $serverConfig; Names = @('SUPABASE_URL', 'SPRING_DATASOURCE_URL', 'SPRING_DATASOURCE_USERNAME', 'SPRING_DATASOURCE_PASSWORD', 'AGENT_INTERNAL_KEY') },
        @{ Values = $agentConfig; Names = @('AGENT_INTERNAL_KEY', 'AGENT_MODEL_API_URL', 'AGENT_MODEL_API_KEY', 'AGENT_MODEL') }
    )) {
        foreach ($name in $group.Names) {
            $value = Get-DesktopValue $group.Values $name
            if ([string]::IsNullOrWhiteSpace($value) -or $value -match '^(replace-with-|your-|use-the-same)') {
                throw "Configure $name in the corresponding .env.local."
            }
        }
    }
    $supabaseUrl = Get-DesktopValue $serverConfig 'SUPABASE_URL'
    if ($supabaseUrl -notmatch '^https://[a-z0-9]+\.supabase\.co/?$' -or
        $supabaseUrl.TrimEnd('/') -cne (Get-DesktopValue $webConfig 'NEXT_PUBLIC_SUPABASE_URL').TrimEnd('/')) {
        throw 'Frontend and backend must use the same Supabase project URL.'
    }
    if ((Get-DesktopValue $serverConfig 'SPRING_DATASOURCE_URL') -notlike 'jdbc:postgresql://*') {
        throw 'Desktop mode requires PostgreSQL JDBC configuration; H2 is not allowed.'
    }
    if ((Get-DesktopValue $serverConfig 'AGENT_INTERNAL_KEY') -cne (Get-DesktopValue $agentConfig 'AGENT_INTERNAL_KEY')) {
        throw 'Java and Python AGENT_INTERNAL_KEY values must match.'
    }
    foreach ($setting in @(
        @{ Values = $webConfig; Name = 'NEXT_PUBLIC_API_BASE_URL'; Expected = 'http://localhost:8080' },
        @{ Values = $serverConfig; Name = 'APP_CORS_ALLOWED_ORIGIN'; Expected = 'http://localhost:3000' },
        @{ Values = $serverConfig; Name = 'AGENT_SERVICE_URL'; Expected = 'http://localhost:8090' },
        @{ Values = $agentConfig; Name = 'JAVA_AGENT_TOOL_URL'; Expected = 'http://localhost:8080/internal/agent/tools' },
        @{ Values = $agentConfig; Name = 'AGENT_HOST'; Expected = '127.0.0.1' },
        @{ Values = $agentConfig; Name = 'AGENT_PORT'; Expected = '8090' }
    )) {
        if ((Get-DesktopValue $setting.Values $setting.Name $setting.Expected).TrimEnd('/') -ne $setting.Expected) {
            throw "Desktop mode expects $($setting.Name)=$($setting.Expected)."
        }
    }
}

function Get-DesktopHash([string]$Text) {
    $hash = [Security.Cryptography.SHA256]::Create()
    try { return [BitConverter]::ToString($hash.ComputeHash([Text.Encoding]::UTF8.GetBytes($Text))).Replace('-', '') }
    finally { $hash.Dispose() }
}

function Get-DesktopFingerprint {
    $files = foreach ($directory in @('web/app', 'web/components', 'web/lib', 'web/public', 'server/src', 'agent/src')) {
        Get-ChildItem -LiteralPath (Join-Path $desktopRoot $directory) -File -Recurse |
            Where-Object { $_.FullName -notmatch '[\\/]__pycache__[\\/]' }
    }
    $files += foreach ($file in @('web/package.json', 'web/pnpm-lock.yaml', 'web/next.config.mjs', 'web/tsconfig.json',
        'web/middleware.ts', 'web/.env.local', 'server/pom.xml', 'agent/pyproject.toml')) {
        Get-Item -LiteralPath (Join-Path $desktopRoot $file)
    }
    $parts = @($files | Sort-Object FullName | ForEach-Object {
        $_.FullName.Substring($desktopRoot.Length) + ':' + (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash
    })
    foreach ($name in @('NEXT_PUBLIC_API_BASE_URL', 'NEXT_PUBLIC_SUPABASE_URL', 'NEXT_PUBLIC_SUPABASE_ANON_KEY')) {
        $parts += $name + ':' + [Environment]::GetEnvironmentVariable($name)
    }
    return Get-DesktopHash ($parts -join "`n")
}

function Get-DesktopConfigHash {
    $parts = foreach ($directory in @('web', 'server', 'agent')) {
        $values = Read-DesktopEnv $directory
        foreach ($name in @($values.Keys | Sort-Object)) {
            "$directory/$name=" + (Get-DesktopValue $values $name)
        }
    }
    return Get-DesktopHash ($parts -join "`n")
}

function Resolve-DesktopCommand([string]$Name) {
    $command = Get-Command $Name -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $command) { throw "Missing $Name. Install the runtime and add it to PATH." }
    return $command.Source
}

function Invoke-DesktopCommand([string]$File, [string[]]$Arguments, [string]$Directory, [string]$Name) {
    $process = Start-Process -FilePath $File -ArgumentList (($Arguments | ForEach-Object { ConvertTo-DesktopArgument $_ }) -join ' ') `
        -WorkingDirectory $Directory -WindowStyle Hidden -PassThru -Wait `
        -RedirectStandardOutput (Join-Path $desktopLogs "$Name.log") -RedirectStandardError (Join-Path $desktopLogs "$Name.err.log")
    if ($process.ExitCode -ne 0) { throw "$Name failed (exit $($process.ExitCode)). See $desktopLogs\$Name.err.log and $Name.log." }
}

function Get-DesktopRuntime {
    $node = Resolve-DesktopCommand 'node.exe'
    $java = if ($env:JAVA_HOME -and (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
        Join-Path $env:JAVA_HOME 'bin\java.exe'
    } else { Resolve-DesktopCommand 'java.exe' }
    Invoke-DesktopCommand $node @('--version') $desktopRoot 'desktop-node-check'
    if ([version](Get-Content -LiteralPath (Join-Path $desktopLogs 'desktop-node-check.log') -Raw).Trim().TrimStart('v') -lt [version]'20.0') {
        throw 'Node.js 20+ is required.'
    }
    Invoke-DesktopCommand $java @('-version') $desktopRoot 'desktop-java-check'
    $javaVersion = Get-Content -LiteralPath (Join-Path $desktopLogs 'desktop-java-check.err.log') -Raw
    if ($javaVersion -notmatch 'version "(\d+)' -or [int]$Matches[1] -lt 21) { throw 'Java 21+ is required.' }
    $agentDirectory = Join-Path $desktopRoot 'agent'
    $python = Join-Path $agentDirectory '.venv\Scripts\python.exe'
    $pythonArguments = @()
    if (Test-Path -LiteralPath (Join-Path $agentDirectory '.venv')) {
        if (-not (Test-Path -LiteralPath $python)) { throw 'agent/.venv is invalid. Recreate it with Python 3.10+.' }
    } else {
        $python = Resolve-DesktopCommand 'py.exe'
        $pythonArguments = @('-3.10')
    }
    Invoke-DesktopCommand $python ($pythonArguments + @('-X', 'utf8', '-c', 'import sys,langchain_openai,langchain; assert sys.version_info >= (3,10); print(sys.executable)')) $agentDirectory 'desktop-python-check'
    $python = (Get-Content -LiteralPath (Join-Path $desktopLogs 'desktop-python-check.log') -Encoding UTF8 -Tail 1).Trim()
    $ffmpeg = Get-DesktopValue (Read-DesktopEnv 'server') 'FFMPEG_PATH' 'ffmpeg.exe'
    if (-not (Test-Path -LiteralPath $ffmpeg)) { $ffmpeg = Resolve-DesktopCommand $ffmpeg }
    return @{ Node = $node; Java = $java; Python = $python; FFmpeg = $ffmpeg }
}

function Get-DesktopServices($Runtime) {
    return @(
        @{ Name = 'agent'; File = $Runtime.Python; Arguments = @('-m', 'interview_agent.server'); Directory = 'agent'; Port = 8090; Url = 'http://127.0.0.1:8090/health' },
        @{ Name = 'server'; File = $Runtime.Java; Arguments = @('-jar', (Join-Path $desktopRoot 'server\target\interview-agent-server-0.0.1-SNAPSHOT.jar'), '--server.address=127.0.0.1', '--server.port=8080'); Directory = 'server'; Port = 8080; Url = 'http://127.0.0.1:8080/actuator/health' },
        @{ Name = 'web'; File = $Runtime.Node; Arguments = @((Join-Path $desktopRoot 'web\node_modules\next\dist\bin\next'), 'start', '--hostname', '127.0.0.1', '--port', '3000'); Directory = 'web'; Port = 3000; Url = 'http://127.0.0.1:3000/login' }
    )
}

function Read-DesktopState {
    if (-not (Test-Path -LiteralPath $desktopStatePath)) { return @{ Root = $desktopRoot; ConfigHash = ''; Processes = @() } }
    $state = Get-Content -LiteralPath $desktopStatePath -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($state.Root -ne $desktopRoot) { throw 'Desktop state belongs to another checkout. No process was changed.' }
    return $state
}

function Save-DesktopState($Processes, [string]$ConfigHash) {
    @{ Root = $desktopRoot; ConfigHash = $ConfigHash; Processes = @($Processes) } | ConvertTo-Json -Depth 4 |
        Set-Content -LiteralPath "$desktopStatePath.tmp" -Encoding UTF8
    Move-Item -LiteralPath "$desktopStatePath.tmp" -Destination $desktopStatePath -Force
}

function Test-DesktopIdentity($Record, $Process) {
    if (-not $Record -or -not $Process -or $Record.Name -notin @('agent', 'server', 'web')) { return $false }
    try {
        # PowerShell 7 parses JSON timestamps as DateTime; 5.1 leaves them as strings.
        return $Process.ProcessId -eq $Record.Id -and
            $Process.CreationDate.ToUniversalTime().Ticks -eq ([DateTime]$Record.Created).ToUniversalTime().Ticks -and
            $Process.ExecutablePath -eq $Record.File -and $Process.CommandLine -ceq $Record.CommandLine
    } catch { return $false }
}

function Get-DesktopOwnedProcess($Record) {
    if (-not $Record) { return $null }
    $process = Get-CimInstance Win32_Process -Filter "ProcessId = $([int]$Record.Id)" -ErrorAction SilentlyContinue
    if (Test-DesktopIdentity $Record $process) { return $process }
    return $null
}

function Assert-DesktopPort($Service, $Record) {
    foreach ($listener in @(Get-NetTCPConnection -LocalPort $Service.Port -State Listen -ErrorAction SilentlyContinue)) {
        if (-not (Get-DesktopOwnedProcess $Record) -or $listener.OwningProcess -ne $Record.Id) {
            throw "Port $($Service.Port) is occupied by another process. Stop it yourself; desktop mode will not terminate it."
        }
        if ($listener.LocalAddress -notin @('127.0.0.1', '::1')) { throw "$($Service.Name) must listen only on loopback." }
    }
}

function Stop-DesktopOwnedProcess($Record) {
    # ponytail: window close terminates verified owned trees; it does not finish in-flight work.
    $handle = Get-Process -Id ([int]$Record.Id) -ErrorAction SilentlyContinue
    if (-not $handle) { return }
    $null = $handle.Handle
    if (-not (Get-DesktopOwnedProcess $Record)) { return }
    $null = & "$env:SystemRoot\System32\taskkill.exe" /PID $Record.Id /T /F 2>&1
    try { Wait-Process -Id $Record.Id -Timeout 10 -ErrorAction Stop } catch { }
    if (Get-DesktopOwnedProcess $Record) { throw "Could not stop $($Record.Name); its state was retained." }
}

function Stop-DesktopApplication {
    $state = Read-DesktopState
    $failures = @()
    foreach ($record in @($state.Processes | Sort-Object @{ Expression = { @('web', 'server', 'agent').IndexOf($_.Name) } })) {
        try { Stop-DesktopOwnedProcess $record } catch { $failures += $_.Exception.Message }
    }
    Save-DesktopState @($state.Processes | Where-Object { Get-DesktopOwnedProcess $_ }) $state.ConfigHash
    if ($failures.Count) { throw ($failures -join '; ') }
}

function Assert-DesktopNotCancelled {
    if ($CancelFile -and (Test-Path -LiteralPath $CancelFile)) { throw 'Desktop window closed; startup cancelled.' }
}

function Start-DesktopService($Service) {
    $process = Start-Process -FilePath $Service.File -ArgumentList (($Service.Arguments | ForEach-Object { ConvertTo-DesktopArgument $_ }) -join ' ') `
        -WorkingDirectory (Join-Path $desktopRoot $Service.Directory) -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $desktopLogs "desktop-$($Service.Name).log") `
        -RedirectStandardError (Join-Path $desktopLogs "desktop-$($Service.Name).err.log")
    try {
        $identity = Get-CimInstance Win32_Process -Filter "ProcessId = $($process.Id)"
        if (-not $identity -or -not $identity.CommandLine -or $identity.ExecutablePath -ne $Service.File) {
            throw "$($Service.Name) exited before its identity could be recorded."
        }
    } catch {
        if (-not $process.HasExited) { $process.Kill() }
        throw
    }
    return [pscustomobject]@{ Name = $Service.Name; Id = $identity.ProcessId; Created = $identity.CreationDate.ToUniversalTime().ToString('o'); File = $identity.ExecutablePath; CommandLine = $identity.CommandLine }
}

function Test-DesktopHealth($Service) {
    $response = $null
    $reader = $null
    try {
        # Local readiness must bypass system proxies and localhost IPv6 resolution.
        $request = [Net.HttpWebRequest]::Create($Service.Url)
        $request.Proxy = $null
        $request.Timeout = 2000
        $request.ReadWriteTimeout = 2000
        $response = $request.GetResponse()
        $reader = New-Object IO.StreamReader($response.GetResponseStream(), [Text.Encoding]::UTF8)
        $content = $reader.ReadToEnd()
        if ([int]$response.StatusCode -ne 200) { return $false }
        if ($Service.Name -eq 'web') { return $content -match '<html' }
        return ($content | ConvertFrom-Json).status -in @('ok', 'UP')
    } catch { return $false }
    finally {
        if ($reader) { $reader.Dispose() }
        if ($response) { $response.Close() }
    }
}

function Wait-DesktopReady($Services, $Records, [int]$TimeoutSeconds) {
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        Assert-DesktopNotCancelled
        $pending = @()
        foreach ($service in $Services) {
            $record = $Records | Where-Object { $_.Name -eq $service.Name } | Select-Object -First 1
            if (-not (Get-DesktopOwnedProcess $record)) { throw "$($service.Name) exited. See desktop-$($service.Name).log and .err.log." }
            Assert-DesktopPort $service $record
            if (-not (Test-DesktopHealth $service)) { $pending += $service.Name }
        }
        if (-not $pending.Count) { return }
        Start-Sleep -Milliseconds 500
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Timed out waiting for $($pending -join ', '). See the service logs."
}

function Start-DesktopApplication($Services, [int]$TimeoutSeconds) {
    $state = Read-DesktopState
    $records = @($state.Processes | Where-Object { Get-DesktopOwnedProcess $_ })
    $configHash = Get-DesktopConfigHash
    if ($records.Count -and $state.ConfigHash -ne $configHash) { throw 'Configuration changed. Stop desktop services before starting again.' }
    foreach ($service in $Services) {
        $record = $records | Where-Object { $_.Name -eq $service.Name } | Select-Object -First 1
        Assert-DesktopPort $service $record
    }
    $created = @()
    try {
        foreach ($service in $Services) {
            Assert-DesktopNotCancelled
            if (-not ($records | Where-Object { $_.Name -eq $service.Name })) {
                $record = Start-DesktopService $service
                $created += $record
                $records += $record
                Save-DesktopState $records $configHash
            }
        }
        Wait-DesktopReady $Services $records $TimeoutSeconds
        Save-DesktopState $records $configHash
    } catch {
        $failure = $_
        foreach ($record in $created) {
            try { Stop-DesktopOwnedProcess $record } catch { Write-Warning "Cleanup failed for $($record.Name); check Status." }
        }
        Save-DesktopState @($records | Where-Object { Get-DesktopOwnedProcess $_ }) $configHash
        throw $failure
    }
}

function Assert-DesktopBuild {
    if (-not (Test-Path -LiteralPath $desktopBuildPath)) { throw 'Run desktop.ps1 -Action Prepare once before opening the app.' }
    $build = Get-Content -LiteralPath $desktopBuildPath -Raw -Encoding UTF8 | ConvertFrom-Json
    $jar = Join-Path $desktopRoot 'server\target\interview-agent-server-0.0.1-SNAPSHOT.jar'
    $buildId = Join-Path $desktopRoot 'web\.next\BUILD_ID'
    if (-not (Test-Path -LiteralPath $jar) -or -not (Test-Path -LiteralPath $buildId) -or
        $build.Fingerprint -ne (Get-DesktopFingerprint) -or $build.JarHash -ne (Get-FileHash -LiteralPath $jar).Hash -or
        $build.BuildId -ne (Get-Content -LiteralPath $buildId -Raw).Trim()) {
        throw 'Source, frontend configuration or build changed. Stop services and run desktop.ps1 -Action Prepare.'
    }
}

function Prepare-DesktopBuild {
    foreach ($port in @(3000, 8080, 8090)) {
        if (Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue) { throw "Stop services on port $port before Prepare." }
    }
    foreach ($record in @( (Read-DesktopState).Processes )) {
        if (Get-DesktopOwnedProcess $record) { throw 'Stop desktop services before Prepare.' }
    }
    $null = Get-DesktopRuntime
    $pnpm = Resolve-DesktopCommand 'pnpm.cmd'
    $maven = Resolve-DesktopCommand 'mvn.cmd'
    $fingerprint = Get-DesktopFingerprint
    if (Test-Path -LiteralPath $desktopBuildPath) { Remove-Item -LiteralPath $desktopBuildPath }
    foreach ($job in @(
        @{ File = $pnpm; Arguments = @('run', 'lint'); Directory = 'web'; Name = 'desktop-typecheck' },
        @{ File = $pnpm; Arguments = @('run', 'build'); Directory = 'web'; Name = 'desktop-web-build' },
        @{ File = $maven; Arguments = @('-B', '-ntp', '-s', '.mvn/settings.xml', '-DskipTests', 'package'); Directory = 'server'; Name = 'desktop-server-build' }
    )) {
        $literal = "& '" + $job.File.Replace("'", "''") + "' " + (($job.Arguments | ForEach-Object { "'" + $_.Replace("'", "''") + "'" }) -join ' ') + '; exit $LASTEXITCODE'
        $encoded = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($literal))
        Write-Host "Preparing $($job.Name)..."
        Invoke-DesktopCommand $desktopPowerShell @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-EncodedCommand', $encoded) (Join-Path $desktopRoot $job.Directory) $job.Name
    }
    if ($fingerprint -ne (Get-DesktopFingerprint)) { throw 'Source or frontend configuration changed during Prepare. Run Prepare again.' }
    @{ Fingerprint = $fingerprint; JarHash = (Get-FileHash -LiteralPath (Join-Path $desktopRoot 'server\target\interview-agent-server-0.0.1-SNAPSHOT.jar')).Hash;
        BuildId = (Get-Content -LiteralPath (Join-Path $desktopRoot 'web\.next\BUILD_ID') -Raw).Trim() } | ConvertTo-Json |
        Set-Content -LiteralPath $desktopBuildPath -Encoding UTF8
    Write-Host 'Production builds are ready. Use the desktop shortcut to open the app.'
}

function Get-DesktopElectron {
    $electron = Join-Path $desktopRoot 'desktop\node_modules\electron\dist\electron.exe'
    if (-not (Test-Path -LiteralPath $electron)) { throw 'In desktop/, run npm.cmd ci --ignore-scripts=false once to install the desktop window runtime.' }
    return $electron
}

function Open-DesktopWindow {
    $electron = Get-DesktopElectron
    $previous = $env:ELECTRON_RUN_AS_NODE
    try {
        $env:ELECTRON_RUN_AS_NODE = $null
        $null = Start-Process -FilePath $electron -ArgumentList (ConvertTo-DesktopArgument (Join-Path $desktopRoot 'desktop')) -WorkingDirectory $desktopRoot -WindowStyle Normal
    } finally { $env:ELECTRON_RUN_AS_NODE = $previous }
}

function Install-DesktopShortcuts {
    $null = Get-DesktopElectron
    $icon = Join-Path $desktopRoot 'desktop\icon.ico'
    if (-not (Test-Path -LiteralPath $icon)) { throw 'Missing desktop/icon.ico.' }
    $shell = New-Object -ComObject WScript.Shell
    $path = Join-Path ([Environment]::GetFolderPath('Desktop')) '智面.lnk'
    $shortcut = $shell.CreateShortcut($path)
    if ((Test-Path -LiteralPath $path) -and $shortcut.Arguments -notlike ('*' + (Join-Path $desktopRoot 'desktop.ps1') + '*')) {
        throw "An unrelated shortcut already exists at $path. Rename it before Install."
    }
    $shortcut.TargetPath = $desktopPowerShell
    $shortcut.Arguments = '-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File ' + (ConvertTo-DesktopArgument (Join-Path $desktopRoot 'desktop.ps1')) + ' -Action Start'
    $shortcut.WorkingDirectory = $desktopRoot
    $shortcut.IconLocation = "$icon,0"
    $shortcut.Description = '智面；关闭主窗口会停止全部本地应用服务。'
    $shortcut.Save()
    Write-Host "Created $path"
    $oldStop = Join-Path ([Environment]::GetFolderPath('Desktop')) '停止智面.lnk'
    if ((Test-Path -LiteralPath $oldStop) -and $shell.CreateShortcut($oldStop).Arguments -like ('*' + (Join-Path $desktopRoot 'desktop.ps1') + '*')) {
        Remove-Item -LiteralPath $oldStop
        Write-Host 'Removed obsolete stop shortcut.'
    }
}

# Dot-sourcing exposes the same functions to the dependency-free safety check.
if ($MyInvocation.InvocationName -eq '.') { return }

$mutex = $null
$locked = $false
try {
    $null = New-Item -ItemType Directory -Path $desktopLogs -Force
    if ($Action -eq 'Start' -and -not $NoWindow) { Open-DesktopWindow; exit 0 }
    $mutex = New-Object Threading.Mutex($false, ('Local\InterviewAgentDesktop-' + (Get-DesktopHash $desktopRoot.ToLowerInvariant()).Substring(0, 20)))
    try { $locked = $mutex.WaitOne(0) } catch [Threading.AbandonedMutexException] { $locked = $true }
    if (-not $locked) {
        throw 'Desktop launch or preparation is in progress. Try again after it completes.'
    }
    switch ($Action) {
        'Install' { Install-DesktopShortcuts }
        'Prepare' { Assert-DesktopConfig; Prepare-DesktopBuild }
        'Stop' {
            Stop-DesktopApplication
            Write-Host 'Stopped all owned desktop services.'
        }
        'Status' {
            foreach ($record in @((Read-DesktopState).Processes)) {
                [pscustomobject]@{ Service = $record.Name; ProcessId = $record.Id; Running = [bool](Get-DesktopOwnedProcess $record) }
            }
        }
        'Start' {
            Assert-DesktopConfig
            Assert-DesktopBuild
            $runtime = Get-DesktopRuntime
            $env:PYTHONPATH = Join-Path $desktopRoot 'agent\src'
            $env:PYTHONUTF8 = '1'
            $env:PYTHONIOENCODING = 'utf-8'
            Start-DesktopApplication (Get-DesktopServices $runtime) $StartupTimeoutSeconds
            Write-Host 'Ready: http://localhost:3000.'
        }
    }
} catch {
    $message = $_.Exception.Message + "`r`nLogs: $desktopLogs"
    Add-Content -LiteralPath (Join-Path $desktopLogs 'desktop-launcher.log') -Value ((Get-Date -Format 'o') + ' ' + $message) -Encoding UTF8 -ErrorAction SilentlyContinue
    if ($locked) { $mutex.ReleaseMutex(); $locked = $false }
    if (-not $NoDialog) { $null = (New-Object -ComObject WScript.Shell).Popup($message, 0, '智面启动器', 16) }
    Write-Error $message -ErrorAction Continue
    exit 1
} finally {
    if ($locked) { $mutex.ReleaseMutex() }
    if ($mutex) { $mutex.Dispose() }
}
