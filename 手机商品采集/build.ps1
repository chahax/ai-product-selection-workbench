[CmdletBinding()]
param(
    [string]$SdkRoot = (Join-Path $env:LOCALAPPDATA 'Android\Sdk'),
    [string]$JdkRoot = 'D:\Android Studio\jbr',
    [string]$BuildToolsVersion = '36.0.0',
    [string]$PlatformVersion = 'android-37.0'
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

function Invoke-BuildTool {
    param(
        [string]$Executable,
        [string[]]$Arguments
    )

    & $Executable @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "构建工具执行失败，退出码 $LASTEXITCODE"
    }
}

$projectRoot = [IO.Path]::GetFullPath($PSScriptRoot)
$buildRoot = Join-Path $projectRoot 'build'
$outputRoot = Join-Path $projectRoot 'dist'
$signingRoot = Join-Path $projectRoot '.signing'
$buildTools = Join-Path $SdkRoot ('build-tools\' + $BuildToolsVersion)
$androidJar = Join-Path $SdkRoot ('platforms\' + $PlatformVersion + '\android.jar')
$aapt2 = Join-Path $buildTools 'aapt2.exe'
$zipalign = Join-Path $buildTools 'zipalign.exe'
$d8Jar = Join-Path $buildTools 'lib\d8.jar'
$apksignerJar = Join-Path $buildTools 'lib\apksigner.jar'
$java = Join-Path $JdkRoot 'bin\java.exe'
$javac = Join-Path $JdkRoot 'bin\javac.exe'
$jar = Join-Path $JdkRoot 'bin\jar.exe'
$keytool = Join-Path $JdkRoot 'bin\keytool.exe'

foreach ($tool in @($androidJar, $aapt2, $zipalign, $d8Jar, $apksignerJar, $java, $javac, $jar, $keytool)) {
    if (-not (Test-Path -LiteralPath $tool -PathType Leaf)) {
        throw "缺少构建依赖 $tool"
    }
}

$sourceRoot = Join-Path $projectRoot 'src'
$sources = @(Get-ChildItem -LiteralPath $sourceRoot -Filter '*.java' -Recurse -File)
if ($sources.Count -eq 0) {
    throw 'src 目录里没有 Java 源码。'
}

if (Test-Path -LiteralPath $buildRoot) {
    if ((Get-Item -LiteralPath $buildRoot).Attributes -band [IO.FileAttributes]::ReparsePoint) {
        throw '构建目录为链接，请先确认其目标后再构建。'
    }
    $resolvedBuild = [IO.Path]::GetFullPath((Resolve-Path -LiteralPath $buildRoot).Path)
    $expectedBuild = [IO.Path]::GetFullPath((Join-Path $projectRoot 'build'))
    if ($resolvedBuild -ne $expectedBuild -or [IO.Path]::GetDirectoryName($resolvedBuild) -ne $projectRoot) {
        throw '构建临时目录超出当前项目，已停止清理。'
    }
    Remove-Item -LiteralPath $resolvedBuild -Recurse -Force
}

$classes = Join-Path $buildRoot 'classes'
$generated = Join-Path $buildRoot 'generated'
$dex = Join-Path $buildRoot 'dex'
foreach ($directory in @($classes, $generated, $dex, $outputRoot, $signingRoot)) {
    New-Item -ItemType Directory -Path $directory -Force | Out-Null
}

Push-Location -LiteralPath $projectRoot
try {
    $resourceZip = 'build\resources.zip'
    $resourceApk = 'build\resources.ap_'
    $manifest = 'AndroidManifest.xml'
    $resources = 'res'
    $categoryData = Join-Path (Split-Path -Parent $projectRoot) 'ai选品工作台\src\data\kaogu-categories.json'
    $assets = Join-Path $buildRoot 'assets'
    Copy-Item -LiteralPath (Join-Path $projectRoot 'assets') -Destination $assets -Recurse
    Copy-Item -LiteralPath $categoryData -Destination $assets
    Invoke-BuildTool $aapt2 @('compile', '--dir', $resources, '-o', $resourceZip)
    Invoke-BuildTool $aapt2 @('link', '-o', $resourceApk, '-I', $androidJar, '--manifest', $manifest,
        '--java', 'build\generated', '--min-sdk-version', '30', '--target-sdk-version', '35', '-A', 'build\assets', $resourceZip)

    # Windows aapt2 can preserve backslashes in asset entry names. AssetManager
    # requires ZIP paths with forward slashes on the device.
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $assetArchive = [IO.Compression.ZipFile]::Open((Join-Path $projectRoot $resourceApk), [IO.Compression.ZipArchiveMode]::Update)
    try {
        $assetEntries = @($assetArchive.Entries | Where-Object { $_.FullName.Contains('\') })
        foreach ($assetEntry in $assetEntries) {
            $entryName = $assetEntry.FullName.Replace('\', '/')
            $memory = [IO.MemoryStream]::new()
            $inputStream = $assetEntry.Open()
            try { $inputStream.CopyTo($memory) } finally { $inputStream.Dispose() }
            $assetEntry.Delete()
            $replacement = $assetArchive.CreateEntry($entryName, [IO.Compression.CompressionLevel]::Optimal)
            $outputStream = $replacement.Open()
            try { $memory.Position = 0; $memory.CopyTo($outputStream) }
            finally { $outputStream.Dispose(); $memory.Dispose() }
        }
    }
    finally { $assetArchive.Dispose() }

    $generatedSources = @(Get-ChildItem -LiteralPath $generated -Filter '*.java' -Recurse -File)
    $javaFiles = @($sources | ForEach-Object { $_.FullName }) + @($generatedSources | ForEach-Object { $_.FullName })
    $libraryFiles = @(Get-ChildItem -LiteralPath (Join-Path $projectRoot 'libs') -Filter '*.jar' -File | ForEach-Object { $_.FullName })
    $compilePath = (@($androidJar) + $libraryFiles) -join [IO.Path]::PathSeparator
    $compilerArguments = @('-J-Dfile.encoding=UTF-8', '-encoding', 'UTF-8', '-source', '8', '-target', '8', '-Xlint:-options',
        '-classpath', $compilePath, '-d', $classes) + $javaFiles
    Invoke-BuildTool $javac $compilerArguments

    $classesJar = Join-Path $buildRoot 'classes.jar'
    Invoke-BuildTool $jar @('cf', $classesJar, '-C', $classes, '.')
    $dexArguments = @('-cp', $d8Jar, 'com.android.tools.r8.D8', '--lib', $androidJar,
        '--min-api', '30', '--output', $dex, $classesJar) + $libraryFiles
    Invoke-BuildTool $java $dexArguments

    $unsigned = 'build\app-unsigned.apk'
    $aligned = 'build\app-aligned.apk'
    $apk = Join-Path $outputRoot '手机商品采集.apk'
    Copy-Item -LiteralPath $resourceApk -Destination $unsigned
    Invoke-BuildTool $jar @('uf', $unsigned, '-C', $dex, '.')
    Invoke-BuildTool $zipalign @('-f', '4', $unsigned, $aligned)

    $keystore = Join-Path $signingRoot 'local-ai-collector.jks'
    $passwordFile = Join-Path $signingRoot 'store-password.txt'
    if ((Test-Path -LiteralPath $keystore) -and -not (Test-Path -LiteralPath $passwordFile)) {
        throw '签名密钥已存在，缺少配套口令文件。请找回原文件以保持应用升级签名一致。'
    }
    if (-not (Test-Path -LiteralPath $keystore)) {
        $bytes = New-Object byte[] 32
        $random = [Security.Cryptography.RandomNumberGenerator]::Create()
        try {
            $random.GetBytes($bytes)
        }
        finally {
            $random.Dispose()
        }
        [IO.File]::WriteAllText($passwordFile, [Convert]::ToBase64String($bytes), [Text.UTF8Encoding]::new($false))
        Invoke-BuildTool $keytool @('-J-Dfile.encoding=UTF-8', '-genkeypair', '-noprompt', '-keystore', $keystore,
            '-storetype', 'JKS', '-storepass:file', $passwordFile, '-keypass:file', $passwordFile,
            '-alias', 'collector', '-keyalg', 'RSA', '-keysize', '2048', '-validity', '10000',
            '-dname', 'CN=Local AI Collector,O=Local,C=CN')
    }

    Invoke-BuildTool $java @('-Dfile.encoding=UTF-8', '-jar', $apksignerJar, 'sign', '--ks', $keystore,
        '--ks-key-alias', 'collector', '--ks-pass', ('file:' + $passwordFile),
        '--out', $apk, $aligned)
    Invoke-BuildTool $java @('-Dfile.encoding=UTF-8', '-jar', $apksignerJar, 'verify', $apk)
    Write-Output "APK 已生成 $apk"

}
finally {
    Pop-Location
}
