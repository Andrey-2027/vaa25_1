#Requires -Version 5.1
<#
.SYNOPSIS
    Toggle a strict fail-closed firewall mode for the Happ VPN.

.DESCRIPTION
    Enable:
      - disables all existing outbound allow rules;
      - allows outbound traffic only through the Happ tunnel interface;
      - allows the Happ processes, DHCP and DNS so the VPN can reconnect;
      - sets the default outbound action to Block.

    Disable:
      - removes the fail-closed rules;
      - restores the previous default outbound actions;
      - re-enables the outbound allow rules that were disabled.

    The state is stored in ProgramData so a reboot does not lose the
    information needed to roll back.

.PARAMETER Action
    Enable, Disable or Status.

.PARAMETER DryRun
    Show what would happen without changing firewall state.

.EXAMPLE
    .\vpn-failclose.ps1 -Action Status

.EXAMPLE
    .\vpn-failclose.ps1 -Action Enable -DryRun

.NOTES
    Enable and Disable require an elevated PowerShell session.
    This is a leak-prevention tool, not a way to make use from an
    unsupported region compliant with a provider's terms.
#>
[CmdletBinding()]
param(
    [ValidateSet('Enable', 'Disable', 'Status')]
    [string]$Action = 'Status',

    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'

$Group          = 'Claude VPN Fail-Closed'
$StateDirectory = Join-Path $env:ProgramData 'ClaudeVpnFailClose'
$StateFile      = Join-Path $StateDirectory 'state.json'
$HappDirectory  = 'C:\Program Files\FlyFrogLLC\Happ'
$HappExe        = Join-Path $HappDirectory 'Happ.exe'
$HappdExe       = Join-Path $HappDirectory 'happd.exe'
$VpnInterface   = 'happ-*'
$Profiles       = @('Domain', 'Private', 'Public')
$SvchostExe     = Join-Path $env:SystemRoot 'System32\svchost.exe'

function Test-IsAdministrator {
    $identity  = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = New-Object Security.Principal.WindowsPrincipal($identity)
    return $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

function Get-OutboundProfileState {
    $result = [ordered]@{}
    foreach ($name in $Profiles) {
        $profile = Get-NetFirewallProfile -Profile $name -ErrorAction Stop
        $result[$name] = [string]$profile.DefaultOutboundAction
    }
    return $result
}

function Set-OutboundProfileState {
    param(
        [Parameter(Mandatory = $true)]
        $State
    )

    foreach ($name in $Profiles) {
        $value = [string]$State.$name
        if ($value -notin @('Allow', 'Block', 'NotConfigured')) {
            $value = 'Allow'
        }

        try {
            Set-NetFirewallProfile -Profile $name -DefaultOutboundAction $value -ErrorAction Stop
        }
        catch {
            Write-Warning "Could not restore DefaultOutboundAction=$value for profile $name. Falling back to Allow."
            Set-NetFirewallProfile -Profile $name -DefaultOutboundAction Allow -ErrorAction Stop
        }
    }
}

function Get-FailCloseRules {
    return @(Get-NetFirewallRule -Group $Group -ErrorAction SilentlyContinue)
}

function Remove-FailCloseRules {
    Get-NetFirewallRule -Group $Group -ErrorAction SilentlyContinue |
        Remove-NetFirewallRule -ErrorAction SilentlyContinue
}

function Get-XrayPath {
    $found = Get-ChildItem -LiteralPath $HappDirectory -Recurse -Filter 'xray.exe' -File -ErrorAction SilentlyContinue |
        Select-Object -First 1 -ExpandProperty FullName

    if (-not $found) {
        throw "xray.exe was not found under $HappDirectory."
    }

    return $found
}

function New-FailCloseRules {
    if (-not (Test-Path -LiteralPath $HappExe))  { throw "Happ.exe was not found at $HappExe." }
    if (-not (Test-Path -LiteralPath $HappdExe)) { throw "happd.exe was not found at $HappdExe." }

    $xrayExe = Get-XrayPath

    $outboundAllow = @{
        Group     = $Group
        Direction = 'Outbound'
        Action    = 'Allow'
        Profile   = 'Any'
        Enabled   = 'True'
    }

    # All application traffic is allowed only when it leaves through the
    # Happ tunnel interface. This is the actual fail-closed path.
    New-NetFirewallRule @outboundAllow `
        -DisplayName "$Group - VPN interface" `
        -InterfaceAlias $VpnInterface | Out-Null

    # These processes must be able to reach the VPN server while the tunnel
    # is down, otherwise the client can never reconnect.
    New-NetFirewallRule @outboundAllow `
        -DisplayName "$Group - Happ UI" `
        -Program $HappExe | Out-Null

    New-NetFirewallRule @outboundAllow `
        -DisplayName "$Group - Happ service" `
        -Program $HappdExe | Out-Null

    New-NetFirewallRule @outboundAllow `
        -DisplayName "$Group - Xray core" `
        -Program $xrayExe | Out-Null

    # Keep basic network plumbing working. DNS is deliberately allowed
    # outside the tunnel for bootstrapping; application TCP/UDP is not.
    New-NetFirewallRule @outboundAllow `
        -DisplayName "$Group - DNS UDP" `
        -Program $SvchostExe `
        -Protocol UDP `
        -RemotePort 53 | Out-Null

    New-NetFirewallRule @outboundAllow `
        -DisplayName "$Group - DNS TCP" `
        -Program $SvchostExe `
        -Protocol TCP `
        -RemotePort 53 | Out-Null

    New-NetFirewallRule @outboundAllow `
        -DisplayName "$Group - DHCP out" `
        -Program $SvchostExe `
        -Protocol UDP `
        -LocalPort 68 `
        -RemotePort 67 | Out-Null

    New-NetFirewallRule @outboundAllow `
        -DisplayName "$Group - Loopback IPv4" `
        -RemoteAddress '127.0.0.0/8' | Out-Null

    New-NetFirewallRule `
        -Group $Group `
        -DisplayName "$Group - DHCP in" `
        -Direction Inbound `
        -Action Allow `
        -Profile Any `
        -Enabled True `
        -Program $SvchostExe `
        -Protocol UDP `
        -LocalPort 68 `
        -RemotePort 67 | Out-Null
}

function Enable-FailClose {
    if (-not $DryRun -and -not (Test-IsAdministrator)) {
        throw 'Run this action from an elevated PowerShell session.'
    }

    if (Test-Path -LiteralPath $StateFile) {
        throw "A saved fail-closed state already exists at $StateFile. Run -Action Disable first."
    }

    $before = Get-OutboundProfileState

    $existingRules = @(
        Get-NetFirewallRule -Direction Outbound -Action Allow -Enabled True -ErrorAction Stop |
            Where-Object { $_.Group -ne $Group }
    )

    $groupPolicyRules = @(
        $existingRules |
            Where-Object { $_.PolicyStoreSourceType -eq 'GroupPolicy' }
    )

    if ($groupPolicyRules.Count -gt 0) {
        throw 'Group Policy outbound allow rules were detected. A complete fail-closed state cannot be guaranteed; use a dedicated firewall/kill-switch product instead.'
    }

    $state = [ordered]@{
        Version           = 1
        EnabledAt         = (Get-Date).ToString('o')
        DefaultOutbound   = $before
        DisabledRuleNames = @($existingRules | ForEach-Object { $_.Name })
        DisabledRuleCount = $existingRules.Count
    }

    if ($DryRun) {
        Write-Host 'DRY RUN: no firewall changes will be made.'
        Write-Host "State file: $StateFile"
        Write-Host "Existing outbound allow rules to disable: $($existingRules.Count)"
        Write-Host 'Would allow outbound only via interface happ-* and Happ processes, plus DHCP/DNS.'
        Write-Host 'Would set DefaultOutboundAction=Block for Domain, Private and Public.'
        return
    }

    New-Item -ItemType Directory -Force -Path $StateDirectory | Out-Null
    $state | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $StateFile -Encoding UTF8

    try {
        foreach ($rule in $existingRules) {
            $rule | Disable-NetFirewallRule -ErrorAction Stop
        }

        New-FailCloseRules

        foreach ($name in $Profiles) {
            Set-NetFirewallProfile -Profile $name -DefaultOutboundAction Block -ErrorAction Stop
        }

        $leftover = @(
            Get-NetFirewallRule -Direction Outbound -Action Allow -Enabled True -ErrorAction Stop |
                Where-Object { $_.Group -ne $Group }
        )

        if ($leftover.Count -gt 0) {
            $names = ($leftover | Select-Object -First 5 -ExpandProperty DisplayName) -join '; '
            throw "Fail-closed verification failed: $($leftover.Count) outbound allow rule(s) are still enabled ($names)."
        }

        Write-Host 'Fail-closed mode enabled.'
        Write-Host 'Application traffic is now limited to the Happ tunnel, Happ processes, DHCP and DNS.'
    }
    catch {
        $errorMessage = $_.Exception.Message
        Write-Warning "Enable failed: $errorMessage"
        Write-Warning 'Rolling back firewall changes...'

        Remove-FailCloseRules
        Set-OutboundProfileState -State $before

        foreach ($name in @($state.DisabledRuleNames)) {
            try {
                Enable-NetFirewallRule -Name $name -ErrorAction Stop
            }
            catch {
                Write-Warning "Could not re-enable firewall rule $name."
            }
        }

        Remove-Item -LiteralPath $StateFile -Force -ErrorAction SilentlyContinue
        throw
    }
}

function Disable-FailClose {
    if (-not $DryRun -and -not (Test-IsAdministrator)) {
        throw 'Run this action from an elevated PowerShell session.'
    }

    $state = $null
    if (Test-Path -LiteralPath $StateFile) {
        $state = Get-Content -LiteralPath $StateFile -Raw | ConvertFrom-Json
    }

    if ($DryRun) {
        Write-Host 'DRY RUN: no firewall changes will be made.'
        if ($state) {
            Write-Host "Would restore default outbound actions: $($state.DefaultOutbound | ConvertTo-Json -Compress)"
            Write-Host "Would re-enable outbound allow rules: $(@($state.DisabledRuleNames).Count)"
        }
        else {
            Write-Host 'No saved state found. Would remove fail-closed rules and set DefaultOutboundAction=Allow.'
        }
        return
    }

    Remove-FailCloseRules

    if ($state) {
        Set-OutboundProfileState -State $state.DefaultOutbound

        $failed = 0
        foreach ($name in @($state.DisabledRuleNames)) {
            try {
                Enable-NetFirewallRule -Name $name -ErrorAction Stop
            }
            catch {
                $failed++
                Write-Warning "Could not re-enable firewall rule $name."
            }
        }

        if ($failed -eq 0) {
            Remove-Item -LiteralPath $StateFile -Force
            Write-Host 'Fail-closed mode disabled. Previous firewall state restored.'
        }
        else {
            Write-Warning "Fail-closed rules were removed, but $failed rule(s) could not be re-enabled. The state file was kept so Disable can be retried."
        }
    }
    else {
        # Emergency recovery path: the state file is gone. Re-enable all
        # outbound allow rules to avoid leaving the machine without network.
        Get-NetFirewallRule -Direction Outbound -Action Allow -ErrorAction SilentlyContinue |
            Enable-NetFirewallRule -ErrorAction SilentlyContinue

        foreach ($name in $Profiles) {
            Set-NetFirewallProfile -Profile $name -DefaultOutboundAction Allow -ErrorAction Stop
        }

        Write-Warning 'No saved state was found. All outbound allow rules were enabled and DefaultOutboundAction was set to Allow.'
    }
}

function Show-FailCloseStatus {
    $rules = Get-FailCloseRules
    $outboundAllow = @(Get-NetFirewallRule -Direction Outbound -Action Allow -Enabled True -ErrorAction SilentlyContinue)
    $otherOutboundAllow = @($outboundAllow | Where-Object { $_.Group -ne $Group })
    $adapters = @(Get-NetAdapter -ErrorAction SilentlyContinue | Where-Object { $_.Name -like 'happ-*' })

    try {
        $profileState = Get-OutboundProfileState
        $defaults = ($profileState.GetEnumerator() | ForEach-Object { "$($_.Key)=$($_.Value)" }) -join ', '
        $allBlocked = @($profileState.Values | Where-Object { $_ -ne 'Block' }).Count -eq 0
    }
    catch {
        $defaults = "unavailable: $($_.Exception.Message)"
        $allBlocked = $false
    }

    [pscustomobject]@{
        'Fail-close rules present'       = ($rules.Count -gt 0)
        'Default outbound'               = $defaults
        'All profiles blocked'           = $allBlocked
        'Fail-close rule count'          = $rules.Count
        'Other enabled outbound allows'  = $otherOutboundAllow.Count
        'VPN adapter'                    = (($adapters | ForEach-Object { "$($_.Name)=$($_.Status)" }) -join ', ')
        'Saved state file'               = (Test-Path -LiteralPath $StateFile)
    } | Format-List
}

# Self-elevate only for real state changes. Status and DryRun are read-only.
if ($Action -in @('Enable', 'Disable') -and -not $DryRun -and -not (Test-IsAdministrator)) {
    Write-Host 'Requesting administrator rights...'
    $argumentList = "-NoProfile -ExecutionPolicy Bypass -File `"$PSCommandPath`" -Action $Action"

    try {
        Start-Process -FilePath 'powershell.exe' -Verb RunAs -ArgumentList $argumentList -Wait
    }
    catch {
        Write-Error "Could not start an elevated PowerShell process: $($_.Exception.Message)"
        exit 1
    }

    exit
}

switch ($Action) {
    'Enable'  { Enable-FailClose }
    'Disable' { Disable-FailClose }
    'Status'  { Show-FailCloseStatus }
}
