# ═══════════════════════════════════════════════════
#  RDP Pro - Setup & Build Script
#  شغّل هذا الملف في PowerShell مرة واحدة فقط
# ═══════════════════════════════════════════════════

$proj = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $proj
Write-Host "=== RDP Pro Build ===" -ForegroundColor Cyan
Write-Host "المشروع: $proj"

# 1. ابحث عن gradle-wrapper.jar من Flutter
$wrapperJar = "$proj\gradle\wrapper\gradle-wrapper.jar"
$flutterWrapper = "$env:USERPROFILE\flutter\.gradle\wrapper\dists"
$sources = @(
    (Get-ChildItem "$env:USERPROFILE\.gradle\wrapper\dists" -Recurse -Filter "gradle-wrapper.jar" -ErrorAction SilentlyContinue | Select-Object -First 1 -ExpandProperty FullName),
    (Get-ChildItem "C:\flutter" -Recurse -Filter "gradle-wrapper.jar" -ErrorAction SilentlyContinue | Select-Object -First 1 -ExpandProperty FullName),
    (Get-ChildItem "$env:USERPROFILE\AppData\Local\flutter" -Recurse -Filter "gradle-wrapper.jar" -ErrorAction SilentlyContinue | Select-Object -First 1 -ExpandProperty FullName)
)

$found = $false
foreach ($src in $sources) {
    if ($src -and (Test-Path $src) -and (Get-Item $src).Length -gt 1000) {
        Copy-Item $src $wrapperJar -Force
        Write-Host "✓ gradle-wrapper.jar من: $src" -ForegroundColor Green
        $found = $true
        break
    }
}

if (-not $found) {
    Write-Host "⬇ تحميل gradle-wrapper.jar..." -ForegroundColor Yellow
    try {
        $url = "https://raw.githubusercontent.com/gradle/gradle/v8.3.0/gradle/wrapper/gradle-wrapper.jar"
        Invoke-WebRequest -Uri $url -OutFile $wrapperJar -UseBasicParsing
        Write-Host "✓ تم التحميل" -ForegroundColor Green
    } catch {
        Write-Host "✗ فشل التحميل. تأكد من اتصال الإنترنت" -ForegroundColor Red
        exit 1
    }
}

# 2. ابحث عن Gradle مثبت في النظام
$gradleExe = $null
$gradlePaths = @(
    "$env:USERPROFILE\.gradle\wrapper\dists\gradle-8.3-bin\*\gradle-8.3\bin\gradle.bat",
    "$env:USERPROFILE\.gradle\wrapper\dists\gradle-8.5-bin\*\gradle-8.5\bin\gradle.bat",
    "$env:USERPROFILE\.gradle\wrapper\dists\gradle-8.1-bin\*\gradle-8.1\bin\gradle.bat",
    "C:\Gradle\gradle-8.3\bin\gradle.bat",
    "C:\Gradle\gradle-8.5\bin\gradle.bat"
)
foreach ($gp in $gradlePaths) {
    $match = Get-Item $gp -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($match) { $gradleExe = $match.FullName; break }
}

# 3. إعداد local.properties
[System.IO.File]::WriteAllText("$proj\local.properties", "sdk.dir=C\:\\Android`n", [System.Text.UTF8Encoding]::new($false))
Write-Host "✓ local.properties" -ForegroundColor Green

# 4. البناء
Write-Host ""
Write-Host "=== بدء البناء ===" -ForegroundColor Cyan

if ($gradleExe) {
    Write-Host "Gradle: $gradleExe"
    & $gradleExe assembleDebug
} else {
    Write-Host "استخدام gradlew..."
    $env:JAVA_HOME = (Get-Command java -ErrorAction SilentlyContinue)?.Source | Split-Path | Split-Path
    .\gradlew.bat assembleDebug
}

if ($LASTEXITCODE -eq 0) {
    $apk = Get-ChildItem "$proj\app\build\outputs\apk\debug\*.apk" | Select-Object -First 1
    Write-Host ""
    Write-Host "══════════════════════════════════════════" -ForegroundColor Green
    Write-Host " ✓ تم البناء بنجاح!" -ForegroundColor Green
    Write-Host " APK: $($apk.FullName)" -ForegroundColor Green
    Write-Host "══════════════════════════════════════════" -ForegroundColor Green
    Copy-Item $apk.FullName "$env:USERPROFILE\Desktop\RDPPro.apk" -Force
    Write-Host " نُسخ إلى Desktop: RDPPro.apk" -ForegroundColor Green
} else {
    Write-Host "✗ فشل البناء - راجع الأخطاء أعلاه" -ForegroundColor Red
}
