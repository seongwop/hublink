param(
    [string]$ProjectId = "hublink-500805",
    [string]$Zone = "asia-northeast3-a",
    [string]$Branch = "develop",
    [int]$DataWaitSeconds = 600,
    [int]$PlatformWaitSeconds = 600,
    [int]$AppWaitSeconds = 600,
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

function Invoke-GcpSshCommand {
    param(
        [string]$Name,
        [string]$RemoteCommand
    )

    $args = @(
        "compute", "ssh", $Name,
        "--zone", $Zone,
        "--project", $ProjectId,
        "--tunnel-through-iap",
        "--quiet",
        "--command", $RemoteCommand
    )

    & gcloud @args *> $null
    $exitCode = $LASTEXITCODE
    return $exitCode
}

function Wait-GcpSshCommand {
    param(
        [string]$Name,
        [string]$Description,
        [string]$RemoteCommand,
        [int]$TimeoutSeconds = 600,
        [int]$IntervalSeconds = 10
    )

    Write-Host "Waiting for $Description on $Name..."
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)

    while ((Get-Date) -lt $deadline) {
        $exitCode = Invoke-GcpSshCommand -Name $Name -RemoteCommand $RemoteCommand
        if ($exitCode -eq 0) {
            Write-Host "$Description is ready on $Name."
            return
        }

        Start-Sleep -Seconds $IntervalSeconds
    }

    throw "$Description was not ready on $Name after ${TimeoutSeconds}s."
}

if (-not (Get-Command gcloud -ErrorAction SilentlyContinue)) {
    throw "gcloud command was not found. Install Google Cloud SDK and authenticate first."
}

Start-GcpInstances -Names @("hublink-data-vm")
Wait-GcpSshCommand `
    -Name "hublink-data-vm" `
    -Description "data services" `
    -TimeoutSeconds $DataWaitSeconds `
    -RemoteCommand "timeout 1 bash -c ':</dev/tcp/127.0.0.1/5432' && timeout 1 bash -c ':</dev/tcp/127.0.0.1/6379' && timeout 1 bash -c ':</dev/tcp/127.0.0.1/9092'"

Start-GcpInstances -Names @("hublink-platform-vm")
Wait-GcpSshCommand `
    -Name "hublink-platform-vm" `
    -Description "platform services" `
    -TimeoutSeconds $PlatformWaitSeconds `
    -RemoteCommand "curl -fsS http://localhost:19090/actuator/health >/dev/null && curl -fsS http://localhost:19092/actuator/health >/dev/null"

$appVms = @(
    "hublink-domain-a-vm",
    "hublink-domain-b-vm",
    "hublink-delivery-vm",
    "hublink-monitoring-vm"
)
if ($IncludeLoadTest) {
    $appVms += "hublink-load-test-vm"
}

Start-GcpInstances -Names $appVms
Show-GcpInstances

if ($Deploy) {
    Wait-GcpSshCommand `
        -Name "hublink-domain-a-vm" `
        -Description "domain-a Docker" `
        -TimeoutSeconds $AppWaitSeconds `
        -RemoteCommand "sudo docker info >/dev/null 2>&1"

    Wait-GcpSshCommand `
        -Name "hublink-domain-b-vm" `
        -Description "domain-b Docker" `
        -TimeoutSeconds $AppWaitSeconds `
        -RemoteCommand "sudo docker info >/dev/null 2>&1"

    Wait-GcpSshCommand `
        -Name "hublink-delivery-vm" `
        -Description "delivery Docker" `
        -TimeoutSeconds $AppWaitSeconds `
        -RemoteCommand "sudo docker info >/dev/null 2>&1"

    if (-not (Get-Command gh -ErrorAction SilentlyContinue)) {
        throw "gh command was not found. Install GitHub CLI or run the GCP CI/CD workflow manually."
    }

    Write-Host "Triggering GitHub Actions workflow gcp-cicd.yml on branch $Branch..."
    Invoke-CheckedCommand -Command "gh" -Arguments @("workflow", "run", "gcp-cicd.yml", "--ref", $Branch)
    Invoke-CheckedCommand -Command "gh" -Arguments @("run", "list", "--workflow", "gcp-cicd.yml", "--limit", "1")
} else {
    Wait-GcpSshCommand `
        -Name "hublink-platform-vm" `
        -Description "domain-a Eureka apps" `
        -TimeoutSeconds $AppWaitSeconds `
        -RemoteCommand "curl -fsS http://localhost:19090/eureka/apps/COMPANY-SERVICE >/dev/null && curl -fsS http://localhost:19090/eureka/apps/HUB-SERVICE >/dev/null && curl -fsS http://localhost:19090/eureka/apps/USER-SERVICE >/dev/null && curl -fsS http://localhost:19090/eureka/apps/PRODUCT-SERVICE >/dev/null"

    Wait-GcpSshCommand `
        -Name "hublink-platform-vm" `
        -Description "domain-b Eureka apps" `
        -TimeoutSeconds $AppWaitSeconds `
        -RemoteCommand "curl -fsS http://localhost:19090/eureka/apps/ORDER-SERVICE >/dev/null && curl -fsS http://localhost:19090/eureka/apps/STOCK-SERVICE >/dev/null"

    Wait-GcpSshCommand `
        -Name "hublink-platform-vm" `
        -Description "delivery Eureka app" `
        -TimeoutSeconds $AppWaitSeconds `
        -RemoteCommand "curl -fsS http://localhost:19090/eureka/apps/DELIVERY-SERVICE >/dev/null"

    Wait-GcpSshCommand `
        -Name "hublink-monitoring-vm" `
        -Description "monitoring services" `
        -TimeoutSeconds $AppWaitSeconds `
        -RemoteCommand "curl -fsS http://localhost:9090/-/healthy >/dev/null && curl -fsS http://localhost:3100/ready >/dev/null && curl -fsS http://localhost:3000/api/health >/dev/null"
}
