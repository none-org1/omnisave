# Draws the Omnisave logo (blue rounded square + white bookmark) at 16/48/128px.
# shortcut: placeholder mark until the design skill (Python/Gemini) makes the real one, upgrade at P2 branding.
Add-Type -AssemblyName System.Drawing
function New-Icon([int]$s, [string]$out) {
  $bmp = New-Object System.Drawing.Bitmap($s, $s)
  $g = [System.Drawing.Graphics]::FromImage($bmp)
  $g.SmoothingMode = 'AntiAlias'
  $blue = [System.Drawing.Color]::FromArgb(10, 132, 255)

  # rounded square
  $r = $s * 0.24
  $p = New-Object System.Drawing.Drawing2D.GraphicsPath
  $p.AddArc(0, 0, $r * 2, $r * 2, 180, 90)
  $p.AddArc($s - $r * 2, 0, $r * 2, $r * 2, 270, 90)
  $p.AddArc($s - $r * 2, $s - $r * 2, $r * 2, $r * 2, 0, 90)
  $p.AddArc(0, $s - $r * 2, $r * 2, $r * 2, 90, 90)
  $p.CloseFigure()
  # bigger canvas margin so antialiasing isn't clipped
  $m = [Math]::Max(1, [int]($s * 0.02))
  $g.FillPath((New-Object System.Drawing.SolidBrush($blue)), $p)

  # bookmark glyph (x/y are fractions of s)
  $x1 = $s * 0.31; $x2 = $s * 0.69; $xm = $s * 0.50
  $y1 = $s * 0.24; $y2 = $s * 0.78; $yn = $s * 0.62
  $pts = @(
    (New-Object System.Drawing.PointF($x1, $y1)),
    (New-Object System.Drawing.PointF($x2, $y1)),
    (New-Object System.Drawing.PointF($x2, $y2)),
    (New-Object System.Drawing.PointF($xm, $yn)),
    (New-Object System.Drawing.PointF($x1, $y2))
  )
  $g.FillPolygon([System.Drawing.Brushes]::White, $pts)

  $g.Dispose()
  $bmp.Save($out, [System.Drawing.Imaging.ImageFormat]::Png)
  $bmp.Dispose()
  Write-Host "wrote $out"
}
$icons = Join-Path $PSScriptRoot '..\extension\assets\icons'
New-Item -ItemType Directory -Force $icons | Out-Null
New-Icon 16  (Join-Path $icons 'icon16.png')
New-Icon 48  (Join-Path $icons 'icon48.png')
New-Icon 128 (Join-Path $icons 'icon128.png')
