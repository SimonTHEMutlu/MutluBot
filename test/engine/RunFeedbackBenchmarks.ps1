param(
    [Parameter(Mandatory=$true)][string]$Classes,
    [int]$Repeats = 3,
    [int[]]$Depths = @(12, 10, 12, 11, 14, 14)
)
$ErrorActionPreference = 'Stop'
if ($Repeats -lt 1 -or $Depths.Count -ne 6) { throw 'Use positive repeats and six depths.' }
function Run-One([int]$Position, [bool]$Enabled, [bool]$Stats) {
    $output = @(java -cp $Classes engine.FeedbackBenchmarkMatrix $Position $Depths[$Position] $Enabled.ToString().ToLowerInvariant() $Stats.ToString().ToLowerInvariant())
    if ($LASTEXITCODE -ne 0) { throw "Search failed: $output" }
    $result = $output | Where-Object { $_ -like 'RESULT *' }
    if ($result -notmatch '^RESULT name=(\S+) depth=(\d+) nodes=(\d+) ms=(\d+) nps=(\d+) score=(-?\d+) pv=(.*)$') { throw "Missing completed result: $output" }
    $data = [pscustomobject]@{ Name=$Matches[1]; Depth=[int]$Matches[2]; Nodes=[long]$Matches[3]; Ms=[long]$Matches[4]; Nps=[long]$Matches[5]; Score=[int]$Matches[6]; Pv=$Matches[7]; Move=($output | Where-Object { $_ -like 'MOVE *' }); Counters=($output | Where-Object { $_ -like 'PAWN *' }) }
    Write-Host "enabled=$Enabled $result $($data.Move)"
    return $data
}
function Median($Values) {
    $ordered = @($Values | Sort-Object)
    $middle = [int][Math]::Floor($ordered.Count / 2)
    if ($ordered.Count % 2) { return $ordered[$middle] }
    return ($ordered[$middle - 1] + $ordered[$middle]) / 2.0
}
$report = @()
foreach ($position in 0..5) {
    $disabled = @(); $enabled = @(); $reference = $null
    foreach ($repeat in 1..$Repeats) {
        $order = if ($repeat % 2) { @($false, $true) } else { @($true, $false) }
        foreach ($cache in $order) {
            $data = Run-One $position $cache $false
            if ($null -eq $reference) { $reference = $data }
            foreach ($field in 'Nodes','Score','Pv','Move') {
                if ($data.$field -cne $reference.$field) { throw "Semantic mismatch position=$position field=$field expected=$($reference.$field) actual=$($data.$field)" }
            }
            if ($cache) { $enabled += $data } else { $disabled += $data }
        }
    }
    $offMs = Median ($disabled | ForEach-Object { $_.Ms })
    $onMs = Median ($enabled | ForEach-Object { $_.Ms })
    $report += [pscustomobject]@{ Position=$reference.Name; Depth=$reference.Depth; Nodes=$reference.Nodes; Score=$reference.Score; Move=$reference.Move.Substring(5); DisabledMs=$offMs; EnabledMs=$onMs; TimeChangePercent=[Math]::Round(100.0 * ($onMs / $offMs - 1), 2) }
    $instrumented = Run-One $position $true $true
    foreach ($field in 'Nodes','Score','Pv','Move') {
        if ($instrumented.$field -cne $reference.$field) { throw "Instrumentation changed $field at $position" }
    }
    $instrumented.Counters | Write-Host
}
$report | Format-Table -AutoSize
$offTotal = ($report | Measure-Object DisabledMs -Sum).Sum
$onTotal = ($report | Measure-Object EnabledMs -Sum).Sum
Write-Host ('Sum of medians: disabled={0}ms enabled={1}ms change={2:N2}%' -f $offTotal, $onTotal, (100.0 * ($onTotal / $offTotal - 1)))
