$videoDir = 'C:\Project_winsurf\PHANToM GO\docs\Video'
$sourceSvg = Join-Path $videoDir 'Storyboard-Infographic.svg'
$embeddedSvg = Join-Path $videoDir 'Storyboard-Infographic-embedded.svg'
$renderHtml = 'file:///C:/Project_winsurf/PHANToM%20GO/docs/Video/Storyboard-Infographic-embedded-render.html'
$outputPng = Join-Path $videoDir 'Storyboard-Infographic.png'
$chrome = 'C:\Program Files\Google\Chrome\Application\chrome.exe'

$pngFiles = @(
    '01-main-home.png',
    '02-main-drawer.png',
    '03-settings-top.png',
    '04-settings-middle.png',
    '05-settings-lower.png',
    '06-scan-qr.png',
    '07-share-sheet.png',
    '08-display-qr.png',
    '09-display-connected.png',
    '10-display-maps.png'
)

$svg = Get-Content -Raw $sourceSvg

# Remove heavy filters from cards for more reliable headless rendering.
$svg = $svg -replace '\sfilter="url\(#shadow\)"', ''

foreach ($png in $pngFiles) {
    $path = Join-Path $videoDir $png
    $bytes = [System.IO.File]::ReadAllBytes($path)
    $base64 = [Convert]::ToBase64String($bytes)
    $dataUri = 'data:image/png;base64,' + $base64
    $pattern = 'href="' + [regex]::Escape($png) + '"'
    $replacement = 'href="' + $dataUri + '"'
    $svg = [regex]::Replace($svg, $pattern, [System.Text.RegularExpressions.MatchEvaluator]{ param($m) $replacement })
}

[System.IO.File]::WriteAllText($embeddedSvg, $svg, [System.Text.Encoding]::UTF8)

Add-Type -AssemblyName System.Drawing
$scales = @('10', '9.6', '9', '8', '6')
$best = $null

foreach ($scale in $scales) {
    $temp = Join-Path $videoDir ("Storyboard-Infographic-scale-{0}.png" -f $scale.Replace('.', '_'))
    if (Test-Path $temp) {
        Remove-Item $temp -Force -ErrorAction SilentlyContinue
    }

    & $chrome --headless=new --disable-gpu --hide-scrollbars --run-all-compositor-stages-before-draw --virtual-time-budget=12000 --force-device-scale-factor=$scale --window-size=1600,3400 --screenshot=$temp $renderHtml 2>$null

    if (-not (Test-Path $temp)) {
        continue
    }

    try {
        $img = [System.Drawing.Bitmap]::new($temp)
        $w = $img.Width
        $h = $img.Height

        $colors = New-Object 'System.Collections.Generic.HashSet[int]'
        for ($yi = 0; $yi -lt 24; $yi++) {
            for ($xi = 0; $xi -lt 24; $xi++) {
                $x = [int](($w - 1) * $xi / 23)
                $y = [int](($h - 1) * $yi / 23)
                [void]$colors.Add($img.GetPixel($x, $y).ToArgb())
            }
        }

        $img.Dispose()

        if ($colors.Count -gt 12) {
            $best = [PSCustomObject]@{
                Scale = $scale
                Path = $temp
                Width = $w
                Height = $h
                UniqueGridColors = $colors.Count
            }
            break
        }
    } catch {
        Remove-Item $temp -Force -ErrorAction SilentlyContinue
    }
}

if (-not $best) {
    throw 'Unable to render a valid storyboard infographic PNG.'
}

Copy-Item $best.Path $outputPng -Force
Write-Output ("SCALE={0}" -f $best.Scale)
Write-Output ("DIMS={0}x{1}" -f $best.Width, $best.Height)
Write-Output ("GRID_COLORS={0}" -f $best.UniqueGridColors)
Write-Output ("OUTPUT={0}" -f $outputPng)
