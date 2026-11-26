param(
    [string]$DeviceIp = "192.168.1.4",
    [int]$Port = 5555
)

$ErrorActionPreference = "Stop"

$env:JAVA_HOME = "C:\Program Files\Android\Android Studio1\jbr"
$env:ANDROID_HOME = "C:\Users\CASA\AppData\Local\Android\Sdk"
$gradle = "C:\Users\CASA\.gradle\wrapper\dists\gradle-9.6.0-bin\42k10rwplmzkhuboz9kdazi7s\gradle-9.6.0\bin\gradle.bat"
$adb = "C:\Users\CASA\Downloads\scrcpy\scrcpy-win64-v5.0.1\adb.exe"
$apk = Join-Path $PSScriptRoot "app\build\outputs\apk\debug\app-debug.apk"

Write-Host "Compilando TubeBoxTV..." -ForegroundColor Cyan
Push-Location $PSScriptRoot
try {
    & $gradle assembleDebug --no-daemon
    if ($LASTEXITCODE -ne 0) { throw "Falha na compilação do Gradle." }
} finally {
    Pop-Location
}

$target = "${DeviceIp}:${Port}"
Write-Host "Conectando na TV Box ($target)..." -ForegroundColor Cyan
& $adb connect $target
& $adb -s $target install -r $apk
if ($LASTEXITCODE -ne 0) { throw "Falha ao instalar APK na TV Box." }

Write-Host "Abrindo TubeBox na TV Box..." -ForegroundColor Green
& $adb -s $target shell am start -n com.tubebox.tv/.ui.MainActivity
Write-Host "Concluído!" -ForegroundColor Green
