<#
  opencode_repair.ps1 - pull MX-MAL Bridge incidents from the phone, turn on
  wireless debugging, and open a fresh OpenCode session to auto-fix the bug.

  Companion to com.bridge.mx's Repair.java. When the bridge fails its own exam
  (too many mistakes) it writes an incident under
      /sdcard/Android/data/com.bridge.mx/files/repair/
  and (optionally) POSTs it to a bridge URL here.

  This script:
    1. (optionally) switches the device to adb-over-Wi-Fi: `adb tcpip 5555`
       then `adb connect <ip>:5555` - i.e. turns on wireless debugging.
    2. pulls repair/incident-*.md.
    3. for each new incident, runs
           opencode run --auto --agent <agent> --prompt <incident+repo context>
       from the repo, which opens a NEW session that diagnoses and fixes it.

  Usage:
    # one-shot, wireless, general agent (recommended)
    powershell -ExecutionPolicy Bypass -File tools\opencode_repair.ps1 -Wireless

    # keep watching every 30s
    powershell -ExecutionPolicy Bypass -File tools\opencode_repair.ps1 -Wireless -Watch

    # receive the app's direct POSTs instead (needs admin for a LAN prefix)
    powershell -ExecutionPolicy Bypass -File tools\opencode_repair.ps1 -Serve -Port 8765

  Notes:
    * `-DryRun` prints the prompt but does not launch OpenCode.
    * The app can flip wireless debugging itself once granted:
          adb shell pm grant com.bridge.mx android.permission.WRITE_SECURE_SETTINGS
#>
[CmdletBinding()]
param(
    [string]$Serial = 'adb-FIYLYH69FQYTAY4P-k4GPWI._adb-tls-connect._tcp',
    [string]$Repo = 'C:\Users\pc\Documents\MxSyncBridge',
    [string]$Package = 'com.bridge.mx',
    [string]$Agent = 'general',
    [string]$Model = '',
    [switch]$Wireless,
    [switch]$Watch,
    [int]$IntervalSec = 30,
    [switch]$Serve,
    [int]$Port = 8765,
    [switch]$DryRun,
    [switch]$ProcessAll
)

$ErrorActionPreference = 'Continue'

$adb = 'C:\Users\pc\AppData\Local\Microsoft\WinGet\Packages\Google.PlatformTools_Microsoft.Winget.Source_8wekyb3d8bbwe\platform-tools\adb.exe'
$work = Join-Path $env:TEMP 'mx_repair'
$incoming = Join-Path $work 'incoming'
$stateFile = Join-Path $work 'processed.txt'
$logFile = Join-Path $work 'repair.log'

function Log([string]$m) {
    $line = "{0}  {1}" -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $m
    Write-Host $line
    try { Add-Content -LiteralPath $logFile -Value $line -Encoding UTF8 } catch { }
}

function Get-Devices {
    return (& $adb devices 2>$null) | Select-String 'device$' | ForEach-Object {
        ($_ -split '\s+')[0]
    }
}

function Resolve-Serial {
    param([string]$Want)
    $devs = Get-Devices
    if ($devs -contains $Want) { return $Want }
    # prefer a Wi-Fi device if the requested one is gone
    $wifi = $devs | Where-Object { $_ -match ':\d+$' } | Select-Object -First 1
    if ($wifi) { return $wifi }
    $usb = $devs | Where-Object { $_ -notmatch ':' } | Select-Object -First 1
    if ($usb) { return $usb }
    throw "No adb device attached (saw: $($devs -join ', '))"
}

function Enable-Wireless {
    param([string]$Device)
    try {
        Log "wireless: adb tcpip 5555 on $Device"
        & $adb -s $Device tcpip 5555 2>$null | Out-Null
        Start-Sleep -Seconds 2
        $ip = (& $adb -s $Device shell ip route 2>$null |
                Select-String 'src (\d+\.\d+\.\d+\.\d+)' |
                ForEach-Object { $_.Matches[0].Groups[1].Value } |
                Select-Object -First 1)
        if (-not $ip) {
            $ip = (& $adb -s $Device shell ip -o -4 addr show wlan0 2>$null |
                    Select-String 'inet (\d+\.\d+\.\d+\.\d+)' |
                    ForEach-Object { $_.Matches[0].Groups[1].Value } |
                    Select-Object -First 1)
        }
        if (-not $ip) { Log 'wireless: could not determine device IP'; return $null }
        $target = "$ip`:5555"
        & $adb connect $target 2>$null | Out-Null
        Log "wireless: connected $target"
        return $target
    } catch {
        Log "wireless: failed: $($_.Exception.Message)"
        return $null
    }
}

function Pull-Incidents {
    param([string]$Device)
    $remote = "/sdcard/Android/data/$Package/files/repair"
    New-Item -ItemType Directory -Force -Path $incoming | Out-Null
    & $adb -s $Device pull "$remote/." $incoming 2>$null | Out-Null
    return Get-ChildItem -Path $incoming -Filter 'incident-*.md' -File -ErrorAction SilentlyContinue
}

function Get-Processed {
    if (-not (Test-Path $stateFile)) { return @() }
    return @(Get-Content -LiteralPath $stateFile)
}
function Mark-Processed([string]$name) {
    Add-Content -LiteralPath $stateFile -Value $name -Encoding UTF8
}

function Invoke-Repair {
    param([System.IO.FileInfo]$Incident)
    $body = Get-Content -LiteralPath $Incident.FullName -Raw
    $prompt = @"
You are repairing the MxSyncBridge Android app at $Repo (package $Package).

The on-device bridge failed its own exam - it made too many mistakes - and
raised this incident report. Diagnose the root cause in the matcher / alias /
planner / catalogue code, fix it properly (no lookup-table or exam-specific
hacks), run the unit tests, rebuild the debug APK and reinstall on the device.

--- incident report: $($Incident.Name) ---
$body
--- end incident ---

When done, append a short summary and the fix to
C:\Users\pc\Documents\master_memory.txt.
"@

    if ($DryRun) {
        Log "DRY RUN - prompt for $($Incident.Name):"
        Write-Host $prompt
        return
    }

    $ocArgs = @('run', '--auto', '--agent', $Agent, '--prompt', $prompt)
    if ($Model) { $ocArgs += @('-m', $Model) }

    Log "opencode: opening repair session for $($Incident.Name)"
    Push-Location $Repo
    try {
        & opencode @ocArgs
        Log "opencode: session for $($Incident.Name) exited ($LASTEXITCODE)"
    } finally {
        Pop-Location
    }
}

function Process-Once {
    param([string]$Device)
    Log "polling $Device"
    $incidents = Pull-Incidents -Device $Device
    if (-not $incidents) { Log 'no incidents pulled'; return }
    $processed = Get-Processed
    $new = $incidents | Where-Object { ($ProcessAll -or ($processed -notcontains $_.Name)) } |
           Sort-Object LastWriteTime
    if (-not $new) { Log "no new incidents ($($incidents.Count) present)"; return }
    foreach ($inc in $new) {
        Invoke-Repair -Incident $inc
        Mark-Processed $inc.Name
    }
}

function Serve-Bridge {
    $prefix = "http://+:$Port/"
    Log "serving bridge on $prefix (Ctrl-C to stop)"
    $listener = New-Object System.Net.HttpListener
    $listener.Prefixes.Add($prefix)
    try {
        $listener.Start()
    } catch {
        Log "listener failed (run as admin?): $($_.Exception.Message)"
        return
    }
    New-Item -ItemType Directory -Force -Path $incoming | Out-Null
    while ($true) {
        $ctx = $listener.GetContext()
        try {
            $reader = New-Object System.IO.StreamReader($ctx.Request.InputStream, [Text.Encoding]::UTF8)
            $body = $reader.ReadToEnd()
            $reader.Close()
            $name = "incident-{0}.md" -f (Get-Date -Format 'yyyyMMdd-HHmmss')
            $file = Join-Path $incoming $name
            Set-Content -LiteralPath $file -Value $body -Encoding UTF8
            $ctx.Response.StatusCode = 200
            $ctx.Response.Close()
            Log "bridge received $name"
            Invoke-Repair -Incident (Get-Item $file)
            Mark-Processed $name
        } catch {
            Log "bridge error: $($_.Exception.Message)"
        }
    }
}

# ------------------------------------------------------------------ main

New-Item -ItemType Directory -Force -Path $work | Out-Null
Log "=== opencode_repair start (serial=$Serial repo=$Repo wireless=$Wireless watch=$Watch serve=$Serve) ==="

if ($Serve) { Serve-Bridge; return }

$dev = Resolve-Serial -Want $Serial
Log "device: $dev"

if ($Wireless) {
    $t = Enable-Wireless -Device $dev
    if ($t) { $dev = $t }
}

if ($Watch) {
    while ($true) {
        try { Process-Once -Device (Resolve-Serial -Want $dev) }
        catch { Log "watch error: $($_.Exception.Message)" }
        Start-Sleep -Seconds $IntervalSec
    }
} else {
    Process-Once -Device $dev
}
