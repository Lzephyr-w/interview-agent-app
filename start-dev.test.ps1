$ErrorActionPreference = 'Stop'
$tokens = $null
$parseErrors = $null
$scriptAst = [System.Management.Automation.Language.Parser]::ParseFile(
    (Join-Path $PSScriptRoot 'start-dev.ps1'), [ref]$tokens, [ref]$parseErrors)
if ($parseErrors.Count) { throw ($parseErrors -join "`n") }
$stopFunction = $scriptAst.Find({
    param($node)
    $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and
    $node.Name -eq 'Stop-PreviousDevTerminals'
}, $true).Extent.Text
# Substitute the native command so this check never stops real project processes.
$stopFunction = $stopFunction.Replace('& "$env:SystemRoot\System32\taskkill.exe"', 'Invoke-TestTaskkill')
Invoke-Expression $stopFunction

$root = $PSScriptRoot
$runtimeLogs = Join-Path $root 'runtime-logs'
function Get-CimInstance {
    [pscustomobject]@{ ProcessId = 123; CommandLine = "$root $runtimeLogs Tee-Object" }
}
function Invoke-TestTaskkill { $global:LASTEXITCODE = 0; 'SUCCESS' }
function Wait-Process { }
function Get-Process { $script:remainingProcess }

foreach ($remainingProcess in @($null, [pscustomobject]@{ HasExited = $true })) {
    if ((Stop-PreviousDevTerminals) -ne 1) { throw 'Stopped terminal was not counted.' }
}
$remainingProcess = [pscustomobject]@{ HasExited = $false }
$blocked = $false
try { Stop-PreviousDevTerminals | Out-Null } catch {
    if ($_.Exception.Message -notlike '*Refusing to start a second process*') { throw }
    $blocked = $true
}
if (-not $blocked) { throw 'A running terminal must block the next launch.' }
Write-Output 'PASS: absent/exited processes allow restart; running processes block it.'
