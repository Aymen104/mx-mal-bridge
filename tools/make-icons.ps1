# MX-MAL Bridge launcher icons
# Design: vertical split - left MX Player red (#E42517), right MAL blue (#2E51A2)
# Glyphs: white play triangle (MX) + white check mark (MAL) = "watch -> synced to MAL"
Add-Type -AssemblyName System.Drawing

$resDir = "C:\Users\pc\Documents\MxSyncBridge\app\src\main\res"
$red  = [System.Drawing.Color]::FromArgb(255, 228, 37, 23)   # #E42517
$blue = [System.Drawing.Color]::FromArgb(255, 46, 81, 162)   # #2E51A2
$white = [System.Drawing.Color]::White

# ensure output dirs exist
foreach ($d in "mipmap-mdpi","mipmap-hdpi","mipmap-xhdpi","mipmap-xxhdpi","mipmap-xxxhdpi") {
    New-Item -ItemType Directory -Force -Path (Join-Path $resDir $d) | Out-Null
}

function New-LegacyIcon([int]$size, [string]$path) {
    $bmp = New-Object System.Drawing.Bitmap($size, $size, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g.Clear([System.Drawing.Color]::Transparent)

    $s = [float]$size
    # rounded-corner clip path (30% corner radius scaled)
    $r = $s * 0.30
    $pathRect = New-Object System.Drawing.Drawing2D.GraphicsPath
    $d = $r * 2
    $pathRect.AddArc(0, 0, $d, $d, 180, 90)
    $pathRect.AddArc($s - $d, 0, $d, $d, 270, 90)
    $pathRect.AddArc($s - $d, $s - $d, $d, $d, 0, 90)
    $pathRect.AddArc(0, $s - $d, $d, $d, 90, 90)
    $pathRect.CloseFigure()
    $g.SetClip($pathRect)

    # left red half / right blue half
    $g.FillRectangle((New-Object System.Drawing.SolidBrush($red)), 0, 0, $s/2, $s)
    $g.FillRectangle((New-Object System.Drawing.SolidBrush($blue)), $s/2, 0, $s/2, $s)
    # subtle seam highlight
    $seam = New-Object System.Drawing.Pen([System.Drawing.Color]::FromArgb(70, 255, 255, 255), [float]($s * 0.012))
    $g.DrawLine($seam, $s/2, 0, $s/2, $s)
    $seam.Dispose()

    # play triangle (left, MX)
    $pts = @(
        [System.Drawing.PointF]::new($s*0.30, $s*0.315),
        [System.Drawing.PointF]::new($s*0.30, $s*0.685),
        [System.Drawing.PointF]::new($s*0.52, $s*0.50)
    )
    $g.FillPolygon((New-Object System.Drawing.SolidBrush($white)), $pts)

    # check mark (right, MAL) - stroked polyline
    $pen = New-Object System.Drawing.Pen($white, [float]($s * 0.09))
    $pen.StartCap = [System.Drawing.Drawing2D.LineCap]::Round
    $pen.EndCap   = [System.Drawing.Drawing2D.LineCap]::Round
    $pen.LineJoin = [System.Drawing.Drawing2D.LineJoin]::Round
    $g.DrawLines($pen, @(
        [System.Drawing.PointF]::new($s*0.62, $s*0.545),
        [System.Drawing.PointF]::new($s*0.695, $s*0.655),
        [System.Drawing.PointF]::new($s*0.83, $s*0.415)
    ))
    $pen.Dispose()

    $g.ResetClip()
    $g.Dispose()
    $bmp.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
    Write-Output "OK $path ($size x $size)"
}

New-LegacyIcon 48  "$resDir\mipmap-mdpi\ic_launcher.png"
New-LegacyIcon 72  "$resDir\mipmap-hdpi\ic_launcher.png"
New-LegacyIcon 96  "$resDir\mipmap-xhdpi\ic_launcher.png"
New-LegacyIcon 144 "$resDir\mipmap-xxhdpi\ic_launcher.png"
New-LegacyIcon 192 "$resDir\mipmap-xxxhdpi\ic_launcher.png"
Write-Output "LEGACY ICONS DONE"