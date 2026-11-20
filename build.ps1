param([string[]]$Tasks = @('assembleDebug'))
$ErrorActionPreference = 'Stop'
$projectRoot = $PSScriptRoot
$jdk = Get-ChildItem -LiteralPath (Join-Path $projectRoot 'tools') -Directory -Filter 'jdk-*' | Select-Object -First 1
if (-not $jdk) { throw 'Project JDK is missing. Run scripts/setup_toolchain.py first.' }
$env:JAVA_HOME = $jdk.FullName
$env:ANDROID_HOME = Join-Path $projectRoot 'tools\android-sdk'
$env:ANDROID_USER_HOME = Join-Path $projectRoot 'cache\android'
$env:GRADLE_USER_HOME = Join-Path $projectRoot 'cache\gradle'
$env:TEMP = Join-Path $projectRoot 'work\temp'
$env:TMP = $env:TEMP
New-Item -ItemType Directory -Force -Path $env:ANDROID_USER_HOME,$env:GRADLE_USER_HOME,$env:TEMP | Out-Null
$env:PATH = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:PATH"
Set-Content -LiteralPath (Join-Path $projectRoot 'local.properties') -Value ('sdk.dir=' + $env:ANDROID_HOME.Replace('\','/')) -Encoding utf8
$gradle = Join-Path $projectRoot 'tools\gradle-8.11.1\bin\gradle.bat'
if (-not (Test-Path -LiteralPath $gradle)) { $gradle = Join-Path $projectRoot 'gradlew.bat' }
Push-Location $projectRoot
try {
    & $gradle @Tasks --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Gradle exited with $LASTEXITCODE" }
    $apk = Join-Path $projectRoot 'app\build\outputs\apk\debug\app-debug.apk'
    if (($Tasks -contains 'assembleDebug') -and (Test-Path -LiteralPath $apk)) {
        $artifacts = Join-Path $projectRoot 'artifacts\apk\current'
        $archive = Join-Path $projectRoot 'artifacts\apk\archive'
        New-Item -ItemType Directory -Force -Path $artifacts,$archive | Out-Null
        $metadataPath = Join-Path $projectRoot 'app\build\outputs\apk\debug\output-metadata.json'
        $apkMetadata = Get-Content -LiteralPath $metadataPath -Raw | ConvertFrom-Json
        $versionName = $apkMetadata.elements[0].versionName
        $artifactName = "PhoneAgent-$versionName-debug.apk"
        $destination = Join-Path $artifacts $artifactName
        if (Test-Path -LiteralPath $destination) {
            $oldHash = (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash
            $newHash = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash
            if ($oldHash -ne $newHash) {
                $revision = Join-Path $archive ("PhoneAgent-$versionName-debug-" + $oldHash.Substring(0,12) + '.apk')
                if (-not (Test-Path -LiteralPath $revision)) { Copy-Item -LiteralPath $destination -Destination $revision }
            }
        }
        $oldApks = @(Get-ChildItem -LiteralPath $artifacts -File -Filter 'PhoneAgent-*-debug.apk' | Where-Object { $_.Name -ne $artifactName })
        foreach ($oldApk in $oldApks) {
            if (Test-Path -LiteralPath (Join-Path $archive $oldApk.Name)) {
                throw "Archive already contains $($oldApk.Name); review duplicate files before publishing the next APK."
            }
        }
        foreach ($oldApk in $oldApks) {
            Move-Item -LiteralPath $oldApk.FullName -Destination (Join-Path $archive $oldApk.Name)
        }
        Copy-Item -LiteralPath $apk -Destination (Join-Path $artifacts $artifactName)
    }
} finally { Pop-Location }
