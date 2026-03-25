$chrome = 'C:\Program Files\Google\Chrome\Application\chrome.exe'
$html = 'file:///C:/Project_winsurf/PHANToM%20GO/docs/Video/Storyboard-Infographic-render.html'
$outDir = 'C:\Project_winsurf\PHANToM GO\docs\Video'
$scales = @('9.6', '9', '8')

Add-Type -AssemblyName System.Drawing
$success = $null

foreach ($scale in $scales) {
    $temp = Join-Path $outDir ("Storyboard-Infographic-scale-{0}.png" -f $scale.Replace('.', '_'))
    if (Test-Path $temp) {
        Remove-Item $temp -Force
    }

    & $chrome --headless=new --disable-gpu --hide-scrollbars --force-device-scale-factor=$scale --window-size=1600,3400 --screenshot=$temp $html 2>$null

    if (Test-Path $temp) {
        try {
            $img = [System.Drawing.Image]::FromFile($temp)
            $dims = "{0}x{1}" -f $img.Width, $img.Height
            $img.Dispose()
            $success = [PSCustomObject]@{
                Scale = $scale
                Path  = $temp
                Dims  = $dims
            }
            break
        } catch {
            Remove-Item $temp -Force -ErrorAction SilentlyContinue
        }
    }
}

if (-not $success) {
    throw 'Unable to render PNG at tested scales.'
}

Copy-Item $success.Path (Join-Path $outDir 'Storyboard-Infographic.png') -Force
Write-Output ("SCALE={0}" -f $success.Scale)
Write-Output ("PATH={0}" -f $success.Path)
Write-Output ("DIMS={0}" -f $success.Dims)
