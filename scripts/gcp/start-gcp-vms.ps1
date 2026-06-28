param(
    [string]$ProjectId = "hublink-500805",
    [string]$Zone = "asia-northeast3-a",
    [string]$Branch = "develop",
    [int]$DataWaitSeconds = 90,
    [int]$PlatformWaitSeconds = 90,
    [switch]$IncludeLoadTest,
    [switch]$Deploy
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Invoke-CheckedCommand {
    param(
        [string]$Command,
        [string[]]$Arguments
    )

    & $Command @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "$Command failed with exit code $LASTEXITCODE"
    }
}

function Start-GcpInstances {
    param([string[]]$Names)

    if ($Names.Count -eq 0) {
        return
    }

    Write-Host "Starting: $($Names -join ', ')"
    $args = @("compute", "instances", "start") + $Names + @("--zone", $Zone, "--project", $ProjectId)
    Invoke-CheckedCommand -Command "gcloud" -Arguments $args
}

function Show-GcpInstances {
    $args = @(
        "compute", "instances", "list",
        "--project", $ProjectId,
        "--filter", "name~'hublink-.*-vm'",
        "--format", "table(name,status,networkInterfaces[0].networkIP,networkInterfaces[0].accessConfigs[0].natIP)"
    )
    Invoke-CheckedCommand -Command "gcloud" -Arguments $args
}

if (-not (Get-Command gcloud -ErrorAction SilentlyContinue)) {
    throw "gcloud command was not found. Install Google Cloud SDK and authenticate first."
}

Start-GcpInstances -Names @("hublink-data-vm")
Write-Host "Waiting $DataWaitSeconds seconds for data services bootstrap..."
Start-Sleep -Seconds $DataWaitSeconds

Start-GcpInstances -Names @("hublink-platform-vm")
Write-Host "Waiting $PlatformWaitSeconds seconds for platform services bootstrap..."
Start-Sleep -Seconds $PlatformWaitSeconds

$appVms = @("hublink-domain-a-vm", "hublink-domain-b-vm", "hublink-monitoring-vm")
if ($IncludeLoadTest) {
    $appVms += "hublink-load-test-vm"
}

Start-GcpInstances -Names $appVms
Show-GcpInstances

if ($Deploy) {
    if (-not (Get-Command gh -ErrorAction SilentlyContinue)) {
        throw "gh command was not found. Install GitHub CLI or run the GCP CI/CD workflow manually."
    }

    Write-Host "Triggering GitHub Actions workflow gcp-cicd.yml on branch $Branch..."
    Invoke-CheckedCommand -Command "gh" -Arguments @("workflow", "run", "gcp-cicd.yml", "--ref", $Branch)
    Invoke-CheckedCommand -Command "gh" -Arguments @("run", "list", "--workflow", "gcp-cicd.yml", "--limit", "1")
}
