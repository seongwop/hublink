Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$scriptDirectory = if ([string]::IsNullOrWhiteSpace($PSScriptRoot)) {
    Join-Path (Get-Location).Path 'monitoring\grafana\scripts'
} else {
    $PSScriptRoot
}
$repoRoot = (Resolve-Path (Join-Path $scriptDirectory '..\..\..')).Path
$outputRoot = Join-Path $repoRoot 'monitoring\grafana\provisioning\dashboards\portfolio'
$datasource = [ordered]@{
    type = 'grafana-testdata-datasource'
    uid  = 'hublink-comparison-csv'
}
$utf8WithoutBom = New-Object Text.UTF8Encoding($false)
$syntheticStart = [DateTime]::Parse(
    '2026-01-01T00:00:00Z',
    [Globalization.CultureInfo]::InvariantCulture,
    [Globalization.DateTimeStyles]::AdjustToUniversal
)

function ConvertTo-CsvCell {
    param([AllowNull()][object]$Value)

    if ($null -eq $Value) {
        return '""'
    }

    $text = [string]$Value
    return '"' + $text.Replace('"', '""') + '"'
}

function ConvertTo-NumberText {
    param([AllowNull()][object]$Value)

    if ($null -eq $Value) {
        return ''
    }

    $text = ([string]$Value).Trim()
    if ([string]::IsNullOrWhiteSpace($text) -or $text -eq 'NaN') {
        return ''
    }

    $number = 0.0
    if (-not [double]::TryParse(
        $text,
        [Globalization.NumberStyles]::Float,
        [Globalization.CultureInfo]::InvariantCulture,
        [ref]$number
    )) {
        return ''
    }

    return $number.ToString(
        '0.######',
        [Globalization.CultureInfo]::InvariantCulture
    )
}

function New-AlignedCsv {
    param(
        [object[]]$Rows,
        [Collections.Specialized.OrderedDictionary]$Columns
    )

    $header = @('Time') + @($Columns.Values)
    $lines = @(
        ($header | ForEach-Object { ConvertTo-CsvCell $_ }) -join ','
    )

    foreach ($row in $Rows) {
        $elapsed = [int][double]$row.elapsed_seconds
        $time = $syntheticStart.AddSeconds($elapsed).ToString(
            'yyyy-MM-dd HH:mm:ss',
            [Globalization.CultureInfo]::InvariantCulture
        )
        $values = @($time)
        foreach ($propertyName in $Columns.Keys) {
            $values += ConvertTo-NumberText $row.$propertyName
        }
        $lines += ($values | ForEach-Object { ConvertTo-CsvCell $_ }) -join ','
    }

    return $lines -join "`n"
}

function Get-RawSeries {
    param(
        [string]$Path,
        [string]$LabelPattern = '',
        [double]$StartEpoch = [double]::NaN
    )

    $rows = Import-Csv -Encoding UTF8 -LiteralPath $Path
    $selected = foreach ($row in $rows) {
        $labels = ''
        if ($row.PSObject.Properties.Name -contains 'labels_json') {
            $labels = [string]$row.labels_json
        } elseif ($row.PSObject.Properties.Name -contains 'metric_labels') {
            $labels = [string]$row.metric_labels
        } elseif ($row.PSObject.Properties.Name -contains 'series_labels') {
            $labels = [string]$row.series_labels
        }

        if ($LabelPattern -and $labels -notmatch $LabelPattern) {
            continue
        }

        $timestamp = [double]$row.timestamp_epoch
        if (-not [double]::IsNaN($StartEpoch) -and $timestamp -lt $StartEpoch) {
            continue
        }

        [PSCustomObject]@{
            timestamp_epoch = $timestamp
            value           = ConvertTo-NumberText $row.value
        }
    }

    $selected = @($selected | Sort-Object timestamp_epoch)
    if ($selected.Count -eq 0) {
        throw "시계열 데이터 없음: $Path"
    }

    $start = if ([double]::IsNaN($StartEpoch)) {
        $selected[0].timestamp_epoch
    } else {
        $StartEpoch
    }
    return @(
        $selected | ForEach-Object {
            [PSCustomObject]@{
                elapsed_seconds = [int][Math]::Round(
                    $_.timestamp_epoch - $start
                )
                value = $_.value
            }
        }
    )
}

function New-RawComparisonCsv {
    param(
        [object[]]$BeforeRows,
        [object[]]$AfterRows,
        [string]$BeforeName,
        [string]$AfterName,
        [double]$BeforeMultiplier = 1,
        [double]$AfterMultiplier = 1
    )

    $before = @{}
    foreach ($row in $BeforeRows) {
        $value = ConvertTo-NumberText $row.value
        if ($value) {
            $value = (
                [double]::Parse(
                    $value,
                    [Globalization.CultureInfo]::InvariantCulture
                ) * $BeforeMultiplier
            ).ToString(
                '0.######',
                [Globalization.CultureInfo]::InvariantCulture
            )
        }
        $before[[int]$row.elapsed_seconds] = $value
    }

    $after = @{}
    foreach ($row in $AfterRows) {
        $value = ConvertTo-NumberText $row.value
        if ($value) {
            $value = (
                [double]::Parse(
                    $value,
                    [Globalization.CultureInfo]::InvariantCulture
                ) * $AfterMultiplier
            ).ToString(
                '0.######',
                [Globalization.CultureInfo]::InvariantCulture
            )
        }
        $after[[int]$row.elapsed_seconds] = $value
    }

    $elapsedValues = @(
        @($before.Keys) + @($after.Keys) |
            Sort-Object -Unique
    )
    $lines = @(
        (
            @('Time', $BeforeName, $AfterName) |
                ForEach-Object { ConvertTo-CsvCell $_ }
        ) -join ','
    )

    foreach ($elapsed in $elapsedValues) {
        $time = $syntheticStart.AddSeconds([int]$elapsed).ToString(
            'yyyy-MM-dd HH:mm:ss',
            [Globalization.CultureInfo]::InvariantCulture
        )
        $beforeValue = if ($before.ContainsKey($elapsed)) {
            $before[$elapsed]
        } else {
            ''
        }
        $afterValue = if ($after.ContainsKey($elapsed)) {
            $after[$elapsed]
        } else {
            ''
        }
        $lines += (
            @($time, $beforeValue, $afterValue) |
                ForEach-Object { ConvertTo-CsvCell $_ }
        ) -join ','
    }

    return $lines -join "`n"
}

function New-OutboxComparisonCsv {
    param(
        [object[]]$Rows,
        [string]$MetricKey,
        [string]$BeforeName,
        [string]$AfterName
    )

    $selected = @(
        $Rows |
            Where-Object metric_key -eq $MetricKey |
            Sort-Object { [int]$_.elapsed_seconds }
    )
    $lines = @(
        (
            @('Time', $BeforeName, $AfterName) |
                ForEach-Object { ConvertTo-CsvCell $_ }
        ) -join ','
    )

    foreach ($row in $selected) {
        $lines += (
            @(
                $row.synthetic_time,
                (ConvertTo-NumberText $row.before_value),
                (ConvertTo-NumberText $row.after_value)
            ) |
                ForEach-Object { ConvertTo-CsvCell $_ }
        ) -join ','
    }

    return $lines -join "`n"
}

function New-TextPanel {
    param(
        [int]$Id,
        [string]$Title,
        [string]$Content,
        [int]$Height = 5
    )

    return [ordered]@{
        id      = $Id
        title   = $Title
        type    = 'text'
        gridPos = [ordered]@{
            h = $Height
            w = 24
            x = 0
            y = 0
        }
        options = [ordered]@{
            mode    = 'markdown'
            content = $Content
        }
    }
}

function New-TimeSeriesPanel {
    param(
        [int]$Id,
        [string]$Title,
        [string]$Description,
        [string]$CsvContent,
        [string]$Unit,
        [double]$Maximum,
        [string[]]$SeriesNames,
        [string[]]$Colors,
        [int]$X,
        [int]$Y,
        [int]$Width = 12,
        [int]$Height = 9
    )

    $overrides = @()
    for ($index = 0; $index -lt $SeriesNames.Count; $index++) {
        $overrides += [ordered]@{
            matcher = [ordered]@{
                id      = 'byName'
                options = $SeriesNames[$index]
            }
            properties = @(
                [ordered]@{
                    id    = 'color'
                    value = [ordered]@{
                        mode       = 'fixed'
                        fixedColor = $Colors[$index]
                    }
                }
            )
        }
    }

    return [ordered]@{
        id          = $Id
        title       = $Title
        description = $Description
        type        = 'timeseries'
        datasource  = $datasource
        gridPos     = [ordered]@{
            h = $Height
            w = $Width
            x = $X
            y = $Y
        }
        fieldConfig = [ordered]@{
            defaults = [ordered]@{
                min        = 0
                max        = $Maximum
                unit       = $Unit
                color      = [ordered]@{ mode = 'palette-classic' }
                thresholds = [ordered]@{
                    mode  = 'absolute'
                    steps = @(
                        [ordered]@{
                            color = 'green'
                            value = $null
                        }
                    )
                }
                custom = [ordered]@{
                    axisBorderShow   = $false
                    axisCenteredZero = $false
                    axisColorMode    = 'text'
                    axisPlacement    = 'auto'
                    drawStyle        = 'line'
                    fillOpacity      = 8
                    gradientMode     = 'none'
                    lineInterpolation = 'linear'
                    lineWidth        = 3
                    pointSize        = 4
                    showPoints       = 'never'
                    spanNulls        = $false
                    stacking         = [ordered]@{
                        mode  = 'none'
                        group = 'A'
                    }
                    thresholdsStyle  = [ordered]@{ mode = 'off' }
                }
            }
            overrides = $overrides
        }
        options = [ordered]@{
            legend = [ordered]@{
                showLegend  = $true
                displayMode = 'table'
                placement   = 'bottom'
                calcs       = @('mean', 'max', 'lastNotNull')
            }
            tooltip = [ordered]@{
                mode      = 'multi'
                sort      = 'desc'
                hideZeros = $false
            }
        }
        targets = @(
            [ordered]@{
                refId      = 'A'
                datasource = $datasource
                scenarioId = 'csv_content'
                format     = 'time_series'
                csvContent = $CsvContent
            }
        )
        transformations = @(
            [ordered]@{
                id      = 'convertFieldType'
                options = [ordered]@{
                    conversions = @(
                        [ordered]@{
                            targetField     = 'Time'
                            destinationType = 'time'
                        }
                    )
                }
            }
        )
    }
}

function New-BarGaugePanel {
    param(
        [int]$Id,
        [string]$Title,
        [string]$Description,
        [double]$BeforeValue,
        [double]$AfterValue,
        [string]$Unit,
        [double]$Maximum,
        [int]$Decimals,
        [int]$X,
        [int]$Y,
        [int]$Width = 12,
        [int]$Height = 8
    )

    $seriesNames = @('Redis 분산락', 'DB 비관적 락')
    $colors = @('red', 'green')
    $overrides = @()
    for ($index = 0; $index -lt $seriesNames.Count; $index++) {
        $overrides += [ordered]@{
            matcher = [ordered]@{
                id      = 'byName'
                options = $seriesNames[$index]
            }
            properties = @(
                [ordered]@{
                    id    = 'color'
                    value = [ordered]@{
                        mode       = 'fixed'
                        fixedColor = $colors[$index]
                    }
                }
            )
        }
    }

    $header = (
        $seriesNames |
            ForEach-Object { ConvertTo-CsvCell $_ }
    ) -join ','
    $values = (
        @(
            (ConvertTo-NumberText $BeforeValue),
            (ConvertTo-NumberText $AfterValue)
        ) |
            ForEach-Object { ConvertTo-CsvCell $_ }
    ) -join ','

    return [ordered]@{
        id          = $Id
        title       = $Title
        description = $Description
        type        = 'bargauge'
        datasource  = $datasource
        gridPos     = [ordered]@{
            h = $Height
            w = $Width
            x = $X
            y = $Y
        }
        fieldConfig = [ordered]@{
            defaults = [ordered]@{
                min      = 0
                max      = $Maximum
                unit     = $Unit
                decimals = $Decimals
            }
            overrides = $overrides
        }
        options = [ordered]@{
            displayMode   = 'gradient'
            minVizHeight  = 10
            minVizWidth   = 0
            namePlacement = 'auto'
            orientation   = 'horizontal'
            reduceOptions = [ordered]@{
                calcs  = @('lastNotNull')
                fields = ''
                values = $false
            }
            showUnfilled = $true
            sizing       = 'auto'
            valueMode    = 'color'
        }
        targets = @(
            [ordered]@{
                refId      = 'A'
                datasource = $datasource
                scenarioId = 'csv_content'
                format     = 'table'
                csvContent = $header + "`n" + $values
            }
        )
    }
}

function Write-Dashboard {
    param(
        [string]$FileName,
        [string]$Uid,
        [string]$Title,
        [string]$Description,
        [object[]]$Panels,
        [string]$TimeTo,
        [string[]]$Tags
    )

    $dashboard = [ordered]@{
        annotations  = [ordered]@{ list = @() }
        description  = $Description
        editable     = $true
        graphTooltip = 1
        links        = @()
        panels       = $Panels
        refresh      = ''
        schemaVersion = 42
        tags         = $Tags
        templating   = [ordered]@{ list = @() }
        time         = [ordered]@{
            from = '2026-01-01T00:00:00.000Z'
            to   = $TimeTo
        }
        timepicker   = [ordered]@{ hidden = $true }
        timezone     = 'utc'
        title        = $Title
        uid          = $Uid
    }

    $path = Join-Path $outputRoot $FileName
    $json = $dashboard | ConvertTo-Json -Depth 100
    [IO.File]::WriteAllText($path, $json, $utf8WithoutBom)
}

$controlledReplayRoot = Join-Path $repoRoot (
    'docs\performance\delivery\delivery-assignment-optimization\' +
    'results\19-controlled-replay\03a-db-pessimistic-lock\' +
    'run02-100vu\local-artifacts'
)
$redisRemovalRows = Import-Csv -Encoding UTF8 -LiteralPath (
    Join-Path $controlledReplayRoot 'comparison-a-to-c-timeseries.csv'
)
$atomicCacheRows = Import-Csv -Encoding UTF8 -LiteralPath (
    Join-Path $controlledReplayRoot 'comparison-c-to-d-timeseries.csv'
)

$stageAName = 'A. Redis 분산락 전체 임계 구간'
$stageCName = 'C. DB 비관적 락'
$stageDName = 'D. 원자적 선점·캐시'
$diagnosticNames = @($stageAName, $stageCName)
$improvementNames = @($stageCName, $stageDName)
$comparisonColors = @('red', 'green')

$dashboard1Description = @(
    '### Redis 락 제거 후 병목 이동 진단 - 저장 시계열'
    ''
    '- A: 회사·허브별 Redis 분산락 안에서 담당자 조회와 배송 저장 수행'
    '- C: Redis 배정 락을 제거하고 후보 집계 행을 DB 비관적 락으로 조회'
    '- 공통 조건: 동일 워밍업, 100VU 8분, supplier 1개 고정, receiver 18개 분산'
    '- C 기준 실행: 재검증 Run 02'
    '- A→C 결과: 성공 RPS `15.07 → 9.06`, 실패율 `27.69% → 98.62%`'
    '- 해석: Redis 락 제거만으로 완료된 개선이 아니라, 대량 후보 행 잠금과 User 담당자 조회로 병목이 이동한 진단 단계'
    '- C의 짧은 지연은 User 회로 개방 뒤 발생한 빠른 502 실패가 포함된 값이므로 성능 개선으로 해석하지 않음'
) -join [Environment]::NewLine

$dashboard1Panels = @(
    (New-TextPanel -Id 1 -Title '비교 기준' -Content $dashboard1Description `
        -Height 6)
    (New-TimeSeriesPanel -Id 2 -Title '성공 RPS' `
        -Description '동일 100VU에서 실제 201 응답 RPS' `
        -CsvContent (New-AlignedCsv -Rows $redisRemovalRows -Columns ([ordered]@{
            before_success_rps = $stageAName
            after_success_rps  = $stageCName
        })) -Unit 'reqps' -Maximum 35 -SeriesNames $diagnosticNames `
        -Colors $comparisonColors -X 0 -Y 6)
    (New-TimeSeriesPanel -Id 3 -Title '실패 RPS' `
        -Description 'A는 Redis lock timeout 409, C는 User 회로 개방 뒤 502' `
        -CsvContent (New-AlignedCsv -Rows $redisRemovalRows -Columns ([ordered]@{
            before_failure_rps = $stageAName
            after_failure_rps  = $stageCName
        })) -Unit 'reqps' -Maximum 1000 -SeriesNames $diagnosticNames `
        -Colors $comparisonColors -X 12 -Y 6)
    (New-TimeSeriesPanel -Id 4 -Title 'User 회로 차단기 OPEN' `
        -Description '1이면 user-service 회로 차단기가 열린 상태' `
        -CsvContent (New-AlignedCsv -Rows $redisRemovalRows -Columns ([ordered]@{
            before_user_circuit_open = $stageAName
            after_user_circuit_open  = $stageCName
        })) -Unit 'short' -Maximum 1 -SeriesNames $diagnosticNames `
        -Colors $comparisonColors -X 0 -Y 15)
    (New-TimeSeriesPanel -Id 5 -Title '배송 서비스 CPU' `
        -Description 'delivery-service 프로세스 CPU 사용률' `
        -CsvContent (New-AlignedCsv -Rows $redisRemovalRows -Columns ([ordered]@{
            before_delivery_cpu_percent = $stageAName
            after_delivery_cpu_percent  = $stageCName
        })) -Unit 'percent' -Maximum 100 -SeriesNames $diagnosticNames `
        -Colors $comparisonColors -X 12 -Y 15)
)
Write-Dashboard -FileName '01-redis-lock-bottleneck-comparison.json' `
    -Uid 'pf-redis-lock' -Title '01. Redis 락 제거 후 병목 이동 - 저장 시계열' `
    -Description '동일 100VU A→C 저장 시계열로 Redis 락 제거 뒤 이동한 병목을 진단' `
    -Panels $dashboard1Panels -TimeTo '2026-01-01T00:08:15.000Z' `
    -Tags @('delivery', 'portfolio', 'comparison', 'timeseries', 'redis-lock', 'db-pessimistic-lock')

$dashboard2Description = @(
    '### 원자적 선점 및 후속 조회 병목 해소 - 저장 시계열'
    ''
    '- C: 후보 집계 행 전체를 DB 비관적 락으로 조회하고 User 담당자를 요청마다 조회'
    '- D: `SKIP LOCKED` 기반 단일 원자적 선점 SQL과 Hub별 담당자 캐시 누적 적용'
    '- 공통 조건: 동일 워밍업, 100VU 8분, supplier 1개 고정, receiver 18개 분산'
    '- C 기준 실행: 재검증 Run 02'
    '- 결과: 성공 RPS `9.06 → 172.33`, 실패율 `98.62% → 0%`'
    '- 주의: D는 원자적 선점과 담당자 캐시가 함께 적용된 누적 효과이며 각 효과를 단독 수치로 분리하지 않음'
) -join [Environment]::NewLine

$dashboard2Panels = @(
    (New-TextPanel -Id 1 -Title '비교 기준' -Content $dashboard2Description `
        -Height 6)
    (New-TimeSeriesPanel -Id 2 -Title '성공 RPS' `
        -Description '동일 100VU에서 실제 201 응답 RPS' `
        -CsvContent (New-AlignedCsv -Rows $atomicCacheRows -Columns ([ordered]@{
            before_success_rps = $stageCName
            after_success_rps  = $stageDName
        })) -Unit 'reqps' -Maximum 250 -SeriesNames $improvementNames `
        -Colors $comparisonColors -X 0 -Y 6)
    (New-TimeSeriesPanel -Id 3 -Title '실패 RPS' `
        -Description 'C의 User 회로 개방 502와 D의 실패 RPS 비교' `
        -CsvContent (New-AlignedCsv -Rows $atomicCacheRows -Columns ([ordered]@{
            before_failure_rps = $stageCName
            after_failure_rps  = $stageDName
        })) -Unit 'reqps' -Maximum 1000 -SeriesNames $improvementNames `
        -Colors $comparisonColors -X 12 -Y 6)
    (New-TimeSeriesPanel -Id 4 -Title 'User 회로 차단기 OPEN' `
        -Description '1이면 user-service 회로 차단기가 열린 상태' `
        -CsvContent (New-AlignedCsv -Rows $atomicCacheRows -Columns ([ordered]@{
            before_user_circuit_open = $stageCName
            after_user_circuit_open  = $stageDName
        })) -Unit 'short' -Maximum 1 -SeriesNames $improvementNames `
        -Colors $comparisonColors -X 0 -Y 15)
    (New-TimeSeriesPanel -Id 5 -Title '배송 API 평균 지연' `
        -Description 'Grafana Server Avg Latency 5m. C는 빠른 502가 포함돼 별도 해석 필요' `
        -CsvContent (New-AlignedCsv -Rows $atomicCacheRows -Columns ([ordered]@{
            before_server_avg_latency_ms = $stageCName
            after_server_avg_latency_ms  = $stageDName
        })) -Unit 'ms' -Maximum 4000 -SeriesNames $improvementNames `
        -Colors $comparisonColors -X 12 -Y 15)
)
Write-Dashboard -FileName '02-atomic-reservation-cache-comparison.json' `
    -Uid 'pf-atomic-cache' -Title '02. 원자적 선점·담당자 캐시 - 저장 시계열' `
    -Description '동일 100VU C→D 저장 시계열로 원자적 선점과 담당자 캐시의 누적 효과를 비교' `
    -Panels $dashboard2Panels -TimeTo '2026-01-01T00:08:15.000Z' `
    -Tags @('delivery', 'portfolio', 'comparison', 'timeseries', 'skip-locked', 'cache')

$poolPath = Join-Path $repoRoot (
    'docs\performance\delivery\delivery-assignment-optimization\' +
    'results\16-capacity-and-pool-tuning\comparison\local-artifacts\' +
    'delivery-assignment-pool30-pool60-150vu-comparison.csv'
)
$poolRows = Import-Csv -Encoding UTF8 -LiteralPath $poolPath
$sharedRoot = Join-Path $repoRoot (
    'docs\performance\delivery\delivery-outbox-optimization\' +
    'results\04-batch-status-update\run01-100vu\local-artifacts\' +
    'grafana\panels'
)
$isolatedRoot = Join-Path $repoRoot (
    'docs\performance\delivery\delivery-outbox-optimization\' +
    'results\05-delivery-vm-isolation\run01-100vu\local-artifacts\' +
    'grafana\panels'
)
$status201Pattern = '"status"\s*:\s*"201"'
$sharedNames = @('공유 Domain B VM', '배송 전용 VM')
$sharedColors = @('red', 'green')
$dashboard3Description = @(
    '### 측정 기반 인프라 용량 최적화 - 저장 시계열 비교'
    ''
    '- Pool 실험: 동일 150VU에서 Hikari 30/60 비교'
    '- VM 격리 실험: DB 4 vCPU 고정, 공유 Domain B VM과 배송 전용 VM 비교'
    '- DB 2→4 vCPU의 개선 전 시계열은 보존되지 않아 이 화면에 포함하지 않음'
) -join [Environment]::NewLine

$dashboard3Panels = @(
    (New-TextPanel -Id 1 -Title '비교 기준' -Content $dashboard3Description)
    (New-TimeSeriesPanel -Id 2 -Title '150VU Pool 30/60 성공 RPS' `
        -Description 'Pool 증설만으로 병목이 해결되는지 확인한 시계열' `
        -CsvContent (New-AlignedCsv -Rows $poolRows -Columns ([ordered]@{
            pool30_success_rps = 'Pool 30'
            pool60_success_rps = 'Pool 60'
        })) -Unit 'reqps' -Maximum 200 `
        -SeriesNames @('Pool 30', 'Pool 60') `
        -Colors @('red', 'green') -X 0 -Y 5)
    (New-TimeSeriesPanel -Id 3 -Title '150VU Hikari pending' `
        -Description 'Pool 증설 전후 connection 대기 시계열' `
        -CsvContent (New-AlignedCsv -Rows $poolRows -Columns ([ordered]@{
            pool30_hikari_pending = 'Pool 30'
            pool60_hikari_pending = 'Pool 60'
        })) -Unit 'short' -Maximum 120 `
        -SeriesNames @('Pool 30', 'Pool 60') `
        -Colors @('red', 'green') -X 12 -Y 5)
    (New-TimeSeriesPanel -Id 4 -Title 'VM 분리 전후 배송 성공 RPS' `
        -Description '공유 VM과 배송 전용 VM 저장 시계열' `
        -CsvContent (New-RawComparisonCsv `
            -BeforeRows (Get-RawSeries `
                -Path (Join-Path $sharedRoot 'panel-501-a-internal-deliveries-rps-and-tomcat-threads.csv') `
                -LabelPattern $status201Pattern) `
            -AfterRows (Get-RawSeries `
                -Path (Join-Path $isolatedRoot 'panel-501-a-internal-deliveries-rps-and-tomcat-threads.csv') `
                -LabelPattern $status201Pattern) `
            -BeforeName $sharedNames[0] -AfterName $sharedNames[1]) `
        -Unit 'reqps' -Maximum 200 -SeriesNames $sharedNames `
        -Colors $sharedColors -X 0 -Y 14)
    (New-TimeSeriesPanel -Id 5 -Title 'VM 분리 전후 배송 평균 지연' `
        -Description 'Server Avg Latency 5m 저장 시계열' `
        -CsvContent (New-RawComparisonCsv `
            -BeforeRows (Get-RawSeries -Path (Join-Path $sharedRoot 'panel-105-a-server-avg-latency-5m.csv')) `
            -AfterRows (Get-RawSeries -Path (Join-Path $isolatedRoot 'panel-105-a-server-avg-latency-5m.csv')) `
            -BeforeName $sharedNames[0] -AfterName $sharedNames[1]) `
        -Unit 'ms' -Maximum 3000 -SeriesNames $sharedNames `
        -Colors $sharedColors -X 12 -Y 14)
    (New-TimeSeriesPanel -Id 6 -Title 'VM 분리 전후 Delivery process CPU' `
        -Description '배송 프로세스 CPU 사용률 저장 시계열' `
        -CsvContent (New-RawComparisonCsv `
            -BeforeRows (Get-RawSeries -Path (Join-Path $sharedRoot 'panel-402-a-cpu-and-gc-pause.csv')) `
            -AfterRows (Get-RawSeries -Path (Join-Path $isolatedRoot 'panel-402-a-cpu-and-gc-pause.csv')) `
            -BeforeName $sharedNames[0] -AfterName $sharedNames[1] `
            -BeforeMultiplier 100 -AfterMultiplier 100) `
        -Unit 'percent' -Maximum 100 -SeriesNames $sharedNames `
        -Colors $sharedColors -X 0 -Y 23)
    (New-TimeSeriesPanel -Id 7 -Title 'VM 분리 전후 Data VM CPU' `
        -Description '처리량 증가가 DB VM에 전달된 정도' `
        -CsvContent (New-RawComparisonCsv `
            -BeforeRows (Get-RawSeries -Path (Join-Path $sharedRoot 'panel-1201-a-data-vm-host-cpu-memory.csv')) `
            -AfterRows (Get-RawSeries -Path (Join-Path $isolatedRoot 'panel-1201-a-data-vm-host-cpu-memory.csv')) `
            -BeforeName $sharedNames[0] -AfterName $sharedNames[1]) `
        -Unit 'percent' -Maximum 100 -SeriesNames $sharedNames `
        -Colors $sharedColors -X 12 -Y 23)
)
Write-Dashboard -FileName '03-infrastructure-capacity-comparison.json' `
    -Uid 'pf-infra-capacity' -Title '03. 인프라 용량 최적화 - 저장 시계열' `
    -Description 'Pool 진단과 배송 VM 격리의 실제 저장 시계열 비교' `
    -Panels $dashboard3Panels -TimeTo '2026-01-01T00:11:15.000Z' `
    -Tags @('delivery', 'portfolio', 'comparison', 'timeseries', 'capacity', 'vm-isolation')

$outboxBeforeRoot = Join-Path $repoRoot (
    'docs\performance\delivery\delivery-outbox-optimization\' +
    'results\01-publishable-index\before-run01-100vu\local-artifacts\' +
    'grafana\panels'
)
$outboxAfterRoot = Join-Path $repoRoot (
    'docs\performance\delivery\delivery-outbox-optimization\' +
    'results\06-final-pipeline-validation\run01-100vu\local-artifacts\' +
    'grafana\panels'
)
$outboxBeforeStart = 1784733882
$outboxAfterStart = 1785399328
$pipelineNames = @(
    '개선 전 e0149 (인덱스 없음·1초 polling·순차·단건 UPDATE)',
    '최종 bfb1 (인덱스·100ms polling·병렬·배치 UPDATE)'
)
$dashboard4Description = @(
    '### Outbox 처리 파이프라인 최적화 - 저장 시계열 비교'
    ''
    '- 개선 전: `e0149eeffd3931ce4f2b843986950c9853716617`'
    '- 최종 개선 후: `bfb1d501f98cc690086bce3b2a317181d00a648c`'
    '- 두 실행 모두 100VU, 1분 상승 + 5분 유지 + 2분 하강, sleep 0'
    '- 원본 Grafana CSV를 각 부하 시작 시각 기준으로 정렬'
) -join [Environment]::NewLine

$dashboard4Panels = @(
    (New-TextPanel -Id 1 -Title '비교 기준' -Content $dashboard4Description)
    (New-TimeSeriesPanel -Id 2 -Title '배송 생성 RPS' `
        -Description '동기 배송 생성 처리량' `
        -CsvContent (New-RawComparisonCsv `
            -BeforeRows (Get-RawSeries `
                -Path (Join-Path $outboxBeforeRoot 'panel-501-a-internal-deliveries-rps-and-tomcat-threads.csv') `
                -LabelPattern 'status=201' -StartEpoch $outboxBeforeStart) `
            -AfterRows (Get-RawSeries `
                -Path (Join-Path $outboxAfterRoot 'panel-501-a-internal-deliveries-rps-and-tomcat-threads.csv') `
                -LabelPattern 'status=201' -StartEpoch $outboxAfterStart) `
            -BeforeName $pipelineNames[0] -AfterName $pipelineNames[1]) `
        -Unit 'reqps' -Maximum 200 -SeriesNames $pipelineNames `
        -Colors @('red', 'green') -X 0 -Y 5)
    (New-TimeSeriesPanel -Id 3 -Title '배송 평균 응답시간' `
        -Description '서버 평균 응답시간 5분 이동 평균' `
        -CsvContent (New-RawComparisonCsv `
            -BeforeRows (Get-RawSeries `
                -Path (Join-Path $outboxBeforeRoot 'panel-105-a-server-avg-latency-5m.csv') `
                -StartEpoch $outboxBeforeStart) `
            -AfterRows (Get-RawSeries `
                -Path (Join-Path $outboxAfterRoot 'panel-105-a-server-avg-latency-5m.csv') `
                -StartEpoch $outboxAfterStart) `
            -BeforeName $pipelineNames[0] -AfterName $pipelineNames[1]) `
        -Unit 'ms' -Maximum 2000 -SeriesNames $pipelineNames `
        -Colors @('red', 'green') -X 12 -Y 5)
    (New-TimeSeriesPanel -Id 4 -Title 'Outbox Published TPS' `
        -Description 'delivery.create.succeed 발행 처리량' `
        -CsvContent (New-RawComparisonCsv `
            -BeforeRows (Get-RawSeries `
                -Path (Join-Path $outboxBeforeRoot 'panel-1216-a-outbox-published-tps.csv') `
                -StartEpoch $outboxBeforeStart) `
            -AfterRows (Get-RawSeries `
                -Path (Join-Path $outboxAfterRoot 'panel-1216-a-outbox-published-tps.csv') `
                -StartEpoch $outboxAfterStart) `
            -BeforeName $pipelineNames[0] -AfterName $pipelineNames[1]) `
        -Unit 'ops' -Maximum 200 -SeriesNames $pipelineNames `
        -Colors @('red', 'green') -X 0 -Y 14)
    (New-TimeSeriesPanel -Id 5 -Title 'Outbox Publishable Backlog' `
        -Description 'PENDING + 재시도 가능한 FAILED, 부하 종료 후 회복 포함' `
        -CsvContent (New-RawComparisonCsv `
            -BeforeRows (Get-RawSeries `
                -Path (Join-Path $outboxBeforeRoot 'panel-1217-a-outbox-publishable-backlog.csv') `
                -StartEpoch $outboxBeforeStart) `
            -AfterRows (Get-RawSeries `
                -Path (Join-Path $outboxAfterRoot 'panel-1217-a-outbox-publishable-backlog.csv') `
                -StartEpoch $outboxAfterStart) `
            -BeforeName $pipelineNames[0] -AfterName $pipelineNames[1]) `
        -Unit 'short' -Maximum 70000 -SeriesNames $pipelineNames `
        -Colors @('red', 'green') -X 12 -Y 14)
    (New-TimeSeriesPanel -Id 6 -Title 'Data VM CPU' `
        -Description 'DB·Kafka·Redis가 공유하는 Data VM CPU' `
        -CsvContent (New-RawComparisonCsv `
            -BeforeRows (Get-RawSeries `
                -Path (Join-Path $outboxBeforeRoot 'panel-1201-a-data-vm-host-cpu-memory.csv') `
                -StartEpoch $outboxBeforeStart) `
            -AfterRows (Get-RawSeries `
                -Path (Join-Path $outboxAfterRoot 'panel-1201-a-data-vm-host-cpu-memory.csv') `
                -StartEpoch $outboxAfterStart) `
            -BeforeName $pipelineNames[0] -AfterName $pipelineNames[1]) `
        -Unit 'percent' -Maximum 100 -SeriesNames $pipelineNames `
        -Colors @('red', 'green') -X 0 -Y 23)
    (New-TimeSeriesPanel -Id 7 -Title 'Hikari Pending' `
        -Description '배송 서비스 DB connection 대기 수' `
        -CsvContent (New-RawComparisonCsv `
            -BeforeRows (Get-RawSeries `
                -Path (Join-Path $outboxBeforeRoot 'panel-301-b-hikari-connection-pool.csv') `
                -StartEpoch $outboxBeforeStart) `
            -AfterRows (Get-RawSeries `
                -Path (Join-Path $outboxAfterRoot 'panel-301-b-hikari-connection-pool.csv') `
                -StartEpoch $outboxAfterStart) `
            -BeforeName $pipelineNames[0] -AfterName $pipelineNames[1]) `
        -Unit 'short' -Maximum 60 -SeriesNames $pipelineNames `
        -Colors @('red', 'green') -X 12 -Y 23)
)
Write-Dashboard -FileName '04-outbox-pipeline-comparison.json' `
    -Uid 'pf-outbox-pipeline' -Title '04. Outbox 파이프라인 최적화 - 저장 시계열' `
    -Description 'e0149 개선 전과 bfb1 최종 개선 후의 실제 저장 시계열 비교' `
    -Panels $dashboard4Panels -TimeTo '2026-01-01T00:23:30.000Z' `
    -Tags @('delivery', 'portfolio', 'comparison', 'timeseries', 'outbox')

Write-Output "생성 완료: $outputRoot"
