$ErrorActionPreference = 'Stop'
$env:JAVA_HOME = Join-Path $env:USERPROFILE 'AndroidDev/jdk-17'
$pastaTemporariaCasa = Join-Path $env:USERPROFILE 'AndroidDev/tmp'
New-Item -ItemType Directory -Force -Path $pastaTemporariaCasa | Out-Null
$env:TMP = $pastaTemporariaCasa
$env:TEMP = $pastaTemporariaCasa
$env:JAVA_TOOL_OPTIONS = "-Djdk.net.unixdomain.tmpdir=$pastaTemporariaCasa -Djava.io.tmpdir=$pastaTemporariaCasa"
$sdkCasa = Join-Path $env:USERPROFILE 'AndroidDev/sdk'
if (-not (Test-Path -LiteralPath (Join-Path $sdkCasa 'platforms/android-35/android.jar'))) {
    throw 'O Android SDK 35 ainda precisa ser disponibilizado. Nenhum download automático foi feito.'
}
if (-not (Test-Path -LiteralPath (Join-Path $sdkCasa 'build-tools/35.0.0/aapt2.exe'))) {
    throw 'O Android Build Tools 35.0.0 ainda precisa ser disponibilizado. Nenhum download automático foi feito.'
}
'sdk.dir=' + $sdkCasa.Replace('\','/').Replace(':','\:') | Set-Content -LiteralPath (Join-Path $PSScriptRoot 'local.properties') -Encoding utf8NoBOM -NoNewline
$gradleCasa = Join-Path $env:USERPROFILE 'AndroidDev/gradle-8.14.3/bin/gradle.bat'
Push-Location $PSScriptRoot
try {
    & $gradleCasa --offline --console=plain :app:assembleDebug :app:lintDebug
    if ($LASTEXITCODE -ne 0) { throw 'Falha na geração ou validação do APK.' }
    New-Item -ItemType Directory -Force -Path dist | Out-Null
    Copy-Item -LiteralPath app/build/outputs/apk/debug/app-debug.apk -Destination dist/Casa-0.4.2.apk -Force
    Get-FileHash -LiteralPath dist/Casa-0.4.2.apk -Algorithm SHA256
} finally { Pop-Location }
