$ErrorActionPreference = 'Stop'
$tokens = $null
$parseErrors = $null
$null = [Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot 'desktop.ps1'), [ref]$tokens, [ref]$parseErrors)
if ($parseErrors.Count) { throw ($parseErrors -join "`n") }
. (Join-Path $PSScriptRoot 'desktop.ps1')
function Assert($Condition, [string]$Message) { if (-not $Condition) { throw $Message } }
function Assert-Fails([scriptblock]$Check, [string]$Expected) {
    try { & $Check } catch {
        Assert ($_.Exception.Message -like "*$Expected*") "Unexpected failure: $($_.Exception.Message)"
        return
    }
    throw "Expected failure containing $Expected"
}

# Exercise the actual Windows argument parser, without loading app configuration.
$argumentLog = Join-Path ([IO.Path]::GetTempPath()) ('desktop-args-' + [guid]::NewGuid() + '.json')
$argumentValues = @('', 'two words', '中文目录 with spaces', 'embedded"quote', 'C:\path with space\', 'slashes\\"quote')
try {
    $arguments = @('-e', 'process.stdout.write(JSON.stringify(process.argv.slice(1)))') + $argumentValues
    $argumentProcess = Start-Process -FilePath (Resolve-DesktopCommand 'node.exe') `
        -ArgumentList (($arguments | ForEach-Object { ConvertTo-DesktopArgument $_ }) -join ' ') `
        -WindowStyle Hidden -PassThru -RedirectStandardOutput $argumentLog
    $argumentProcess.WaitForExit()
    $decoded = ConvertFrom-Json -InputObject (Get-Content -LiteralPath $argumentLog -Raw -Encoding UTF8)
    Assert ($decoded.Count -eq $argumentValues.Count) ("Argument count changed: " + ($decoded | ConvertTo-Json -Compress))
    for ($index = 0; $index -lt $argumentValues.Count; $index++) {
        Assert ($decoded[$index] -ceq $argumentValues[$index]) "Argument $index was corrupted."
    }
} finally { if (Test-Path -LiteralPath $argumentLog) { Remove-Item -LiteralPath $argumentLog } }

$originalLogs = $desktopLogs
$desktopLogs = Join-Path ([IO.Path]::GetTempPath()) ('desktop-command-' + [guid]::NewGuid())
$null = New-Item -ItemType Directory -Path $desktopLogs
try {
    $nodePath = Resolve-DesktopCommand 'node.exe'
    Invoke-DesktopCommand $nodePath @('-e', 'process.exit(0)') $desktopRoot 'command'
    Assert-Fails { Invoke-DesktopCommand $nodePath @('-e', 'process.exit(7)') $desktopRoot 'command' } 'exit 7'
    $moduleCheck = '$env:PSModulePath="C:\unavailable-module-path"; . ''' + (Join-Path $desktopRoot 'desktop.ps1').Replace("'", "''") + '''; foreach($n in @("Get-FileHash","Get-CimInstance","Get-NetTCPConnection")){ if(-not (Get-Command $n -ErrorAction SilentlyContinue)){throw "Missing native module command"} }'
    Invoke-DesktopCommand $desktopPowerShell @('-NoProfile', '-EncodedCommand', [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($moduleCheck))) $desktopRoot 'command'
    $fingerprint = Get-DesktopFingerprint
    $fingerprintCheck = '. ''' + (Join-Path $desktopRoot 'desktop.ps1').Replace("'", "''") + '''; Get-DesktopFingerprint'
    Invoke-DesktopCommand $desktopPowerShell @('-NoProfile', '-EncodedCommand', [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($fingerprintCheck))) $desktopRoot 'command'
    Assert ((Get-Content -LiteralPath (Join-Path $desktopLogs 'command.log') -Raw).Trim() -eq $fingerprint) 'Source fingerprint differs between PowerShell versions.'
    $fixtureCode = 'const http=require("http");const s=http.createServer((q,r)=>{if(q.url==="/html")r.end("<html>fixture</html>");else if(q.url==="/health")r.end(JSON.stringify({status:"ok"}));else{r.statusCode=503;r.end("down")}});s.listen(0,"127.0.0.1",()=>console.log(s.address().port));'
    $fixture = Start-Process -FilePath $nodePath -ArgumentList ('-e ' + (ConvertTo-DesktopArgument $fixtureCode)) `
        -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $desktopLogs 'http.log')
    $null = $fixture.Handle
    $previousProxy = [Net.WebRequest]::DefaultWebProxy
    try {
        $fixturePort = ''
        for ($attempt = 0; $attempt -lt 30 -and -not $fixturePort; $attempt++) {
            Start-Sleep -Milliseconds 100
            $fixturePort = Get-Content -LiteralPath (Join-Path $desktopLogs 'http.log') -ErrorAction SilentlyContinue
        }
        Assert ($fixturePort -match '^\d+$') 'HTTP fixture did not start.'
        [Net.WebRequest]::DefaultWebProxy = New-Object Net.WebProxy('http://127.0.0.1:9', $false)
        Assert (Test-DesktopHealth @{ Name = 'agent'; Url = "http://127.0.0.1:$fixturePort/health" }) 'Local health did not bypass the proxy.'
        Assert (Test-DesktopHealth @{ Name = 'web'; Url = "http://127.0.0.1:$fixturePort/html" }) 'HTML readiness failed.'
        Assert (-not (Test-DesktopHealth @{ Name = 'server'; Url = "http://127.0.0.1:$fixturePort/down" })) 'Unhealthy service was accepted.'
    } finally {
        [Net.WebRequest]::DefaultWebProxy = $previousProxy
        if (-not $fixture.HasExited) { $fixture.Kill(); $fixture.WaitForExit() }
    }
} finally {
    Remove-Item -LiteralPath (Join-Path $desktopLogs 'command.log'), (Join-Path $desktopLogs 'command.err.log'), (Join-Path $desktopLogs 'http.log') -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $desktopLogs
    $desktopLogs = $originalLogs
}

$created = [DateTime]::UtcNow
$identity = [pscustomobject]@{ ProcessId = 12345; CreationDate = $created; ExecutablePath = 'C:\test\node.exe'; CommandLine = 'node owned-app' }
$record = [pscustomobject]@{ Name = 'web'; Id = 12345; Created = $created.ToUniversalTime().ToString('o'); File = 'C:\test\node.exe'; CommandLine = 'node owned-app' }
Assert (Test-DesktopIdentity $record $identity) 'Matching process was rejected.'
$record.Created = $created
Assert (Test-DesktopIdentity $record $identity) 'PowerShell 7 JSON timestamp was rejected.'
$record.Created = $created.ToUniversalTime().ToString('o')
$identity.CreationDate = $created.AddSeconds(1)
Assert (-not (Test-DesktopIdentity $record $identity)) 'Reused PID was trusted.'
$identity.CreationDate = $created
$identity.CommandLine = 'node another-app'
Assert (-not (Test-DesktopIdentity $record $identity)) 'Another command was trusted.'
$identity.CommandLine = $record.CommandLine
$identity.ExecutablePath = 'C:\another\node.exe'
Assert (-not (Test-DesktopIdentity $record $identity)) 'Another executable was trusted.'

# In-memory fakes: no secrets, real service starts, SQL or taskkill calls.
$fakeConfig = @{
    web = @{ NEXT_PUBLIC_SUPABASE_URL = 'https://example.supabase.co'; NEXT_PUBLIC_SUPABASE_ANON_KEY = 'public-test-key' }
    server = @{ SUPABASE_URL = 'https://example.supabase.co'; SPRING_DATASOURCE_URL = 'jdbc:postgresql://test/db';
        SPRING_DATASOURCE_USERNAME = 'test'; SPRING_DATASOURCE_PASSWORD = 'test'; AGENT_INTERNAL_KEY = 'internal-test' }
    agent = @{ AGENT_INTERNAL_KEY = 'internal-test'; AGENT_MODEL_API_URL = 'https://example.invalid'; AGENT_MODEL_API_KEY = 'test'; AGENT_MODEL = 'test' }
}
function Read-DesktopEnv([string]$Directory) { return $fakeConfig[$Directory] }
function Get-DesktopValue($Values, [string]$Name, [string]$Default = '') {
    if ($Values.ContainsKey($Name)) { return $Values[$Name] }
    return $Default
}
Assert-DesktopConfig
$fakeConfig.server.SPRING_DATASOURCE_URL = 'jdbc:h2:mem:test'
Assert-Fails { Assert-DesktopConfig } 'H2 is not allowed'
$fakeConfig.server.SPRING_DATASOURCE_URL = 'jdbc:postgresql://test/db'
$fakeConfig.server.SPRING_DATASOURCE_PASSWORD = ''
Assert-Fails { Assert-DesktopConfig } 'SPRING_DATASOURCE_PASSWORD'
$fakeConfig.server.SPRING_DATASOURCE_PASSWORD = 'test'
$fakeConfig.agent.AGENT_INTERNAL_KEY = 'different'
Assert-Fails { Assert-DesktopConfig } 'must match'
$fakeConfig.agent.AGENT_INTERNAL_KEY = 'internal-test'
$fakeConfig.web.NEXT_PUBLIC_SUPABASE_URL = 'https://another.supabase.co'
Assert-Fails { Assert-DesktopConfig } 'same Supabase project'
$fakeConfig.web.NEXT_PUBLIC_SUPABASE_URL = 'https://example.supabase.co'

$fakeServices = @(@{ Name = 'agent'; Port = 8090 }, @{ Name = 'server'; Port = 8080 }, @{ Name = 'web'; Port = 3000 })
$script:fakeState = @{ Root = $desktopRoot; ConfigHash = 'config'; Processes = @() }
$script:fakeRunning = @{}
$script:fakeStarts = @()
$script:fakeStops = @()
$script:fakeListeners = @()
$script:failStart = ''
$script:unhealthy = ''
function Read-DesktopState { return $script:fakeState }
function Save-DesktopState($Processes, [string]$ConfigHash) { $script:fakeState = @{ Processes = @($Processes); ConfigHash = $ConfigHash } }
function Get-DesktopConfigHash { return 'config' }
function Get-DesktopOwnedProcess($Record) {
    if ($Record -and $script:fakeRunning[$Record.Name]) { return $Record }
    return $null
}
function Get-NetTCPConnection { return $script:fakeListeners }
function Start-DesktopService($Service) {
    if ($Service.Name -eq $script:failStart) { throw 'fake launch failure' }
    $script:fakeStarts += $Service.Name
    $script:fakeRunning[$Service.Name] = $true
    return [pscustomobject]@{ Name = $Service.Name; Id = $Service.Port }
}
function Stop-DesktopOwnedProcess($Record) {
    $script:fakeStops += $Record.Name
    $script:fakeRunning[$Record.Name] = $false
}
function Test-DesktopHealth($Service) { return $Service.Name -ne $script:unhealthy }

Start-DesktopApplication $fakeServices 1
Assert ($script:fakeStarts.Count -eq 3) 'Cold launch did not start three services.'
Start-DesktopApplication $fakeServices 1
Assert ($script:fakeStarts.Count -eq 3) 'Repeated launch duplicated services.'
$script:fakeRunning.server = $false
Start-DesktopApplication $fakeServices 1
Assert ($script:fakeStarts.Count -eq 4 -and $script:fakeStarts[-1] -eq 'server') 'Missing service was not restarted independently.'
$script:fakeState.ConfigHash = 'changed'
Assert-Fails { Start-DesktopApplication $fakeServices 1 } 'Configuration changed'
$script:fakeState.ConfigHash = 'config'
$script:fakeListeners = @([pscustomobject]@{ OwningProcess = 999; LocalAddress = '127.0.0.1' })
Assert-Fails { Start-DesktopApplication $fakeServices 1 } 'occupied by another process'
Assert ($script:fakeStops.Count -eq 0) 'Foreign port caused a stop.'
$script:fakeListeners = @([pscustomobject]@{ OwningProcess = 8090; LocalAddress = '0.0.0.0' })
Assert-Fails { Assert-DesktopPort $fakeServices[0] $script:fakeState.Processes[0] } 'only on loopback'
$script:fakeListeners = @()
$script:unhealthy = 'web'
Assert-Fails { Wait-DesktopReady $fakeServices $script:fakeState.Processes 0 } 'Timed out'
$script:unhealthy = ''
$script:fakeRunning.web = $false
Assert-Fails { Wait-DesktopReady $fakeServices $script:fakeState.Processes 1 } 'web exited'

$script:fakeState.Processes = @($script:fakeState.Processes | Where-Object { $_.Name -eq 'agent' })
$script:fakeRunning.server = $false
$script:failStart = 'web'
Assert-Fails { Start-DesktopApplication $fakeServices 1 } 'fake launch failure'
Assert ($script:fakeStops.Count -eq 1 -and $script:fakeStops[0] -eq 'server') 'Rollback touched a reused service or missed a newly created one.'
Assert ($script:fakeRunning.agent -and $script:fakeState.Processes.Count -eq 1) 'Reused service was lost during rollback.'

# A closing window cancels readiness; cleanup still attempts every owned service after one failure.
$cancellationPath = Join-Path ([IO.Path]::GetTempPath()) ('desktop-cancel-check-' + [guid]::NewGuid())
try {
    $CancelFile = $cancellationPath
    Set-Content -LiteralPath $cancellationPath -Value ''
    Assert-Fails { Wait-DesktopReady $fakeServices $script:fakeState.Processes 1 } 'startup cancelled'
} finally {
    $CancelFile = ''
    Remove-Item -LiteralPath $cancellationPath -ErrorAction SilentlyContinue
}
$script:fakeState.Processes = @([pscustomobject]@{ Name = 'web'; Id = 3000 }, [pscustomobject]@{ Name = 'server'; Id = 8080 }, [pscustomobject]@{ Name = 'agent'; Id = 8090 })
$script:fakeRunning = @{ web = $true; server = $true; agent = $true }
$script:fakeStops = @()
function Stop-DesktopOwnedProcess($Record) {
    $script:fakeStops += $Record.Name
    if ($Record.Name -eq 'web') { throw 'fake stop failure' }
    $script:fakeRunning[$Record.Name] = $false
}
Assert-Fails { Stop-DesktopApplication } 'fake stop failure'
Assert (($script:fakeStops -join ',') -eq 'web,server,agent') 'Cleanup stopped after the first failure.'
Assert ($script:fakeState.Processes.Count -eq 1 -and $script:fakeState.Processes[0].Name -eq 'web') 'Failed stop did not retain only the remaining process.'

$script:fakeBuildExists = $true
$script:fakeFingerprint = 'source'
$script:fakeJarHash = 'jar'
$script:fakeBuildId = 'build'
function Test-Path { return $script:fakeBuildExists }
function Get-Content {
    param([string]$LiteralPath, [switch]$Raw, [string]$Encoding)
    if ($LiteralPath -eq $desktopBuildPath) { return '{"Fingerprint":"source","JarHash":"jar","BuildId":"build"}' }
    return $script:fakeBuildId
}
function Get-DesktopFingerprint { return $script:fakeFingerprint }
function Get-FileHash { return [pscustomobject]@{ Hash = $script:fakeJarHash } }
Assert-DesktopBuild
$script:fakeFingerprint = 'changed-source-or-config'
Assert-Fails { Assert-DesktopBuild } 'build changed'
$script:fakeFingerprint = 'source'
$script:fakeJarHash = 'changed-jar'
Assert-Fails { Assert-DesktopBuild } 'build changed'
$script:fakeJarHash = 'jar'
$script:fakeBuildId = 'changed-frontend'
Assert-Fails { Assert-DesktopBuild } 'build changed'
$script:fakeBuildExists = $false
Assert-Fails { Assert-DesktopBuild } 'Prepare once'
Write-Output 'PASS: Windows quoting/native modules, command exits, proxy-free readiness, config/build guards, PID reuse, port conflicts, launches/rollback, cancellation and cleanup after stop failure.'
