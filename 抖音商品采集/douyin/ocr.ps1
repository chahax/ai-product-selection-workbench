[CmdletBinding(DefaultParameterSetName='Files')]
param(
    [Parameter(Mandatory=$true,Position=0,ParameterSetName='Files')][string[]]$ImagePath,
    [Parameter(Mandatory=$true,ParameterSetName='Stream')][switch]$ReadStdin
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
Add-Type -AssemblyName System.Runtime.WindowsRuntime
$null = [Windows.Storage.StorageFile,Windows.Storage,ContentType=WindowsRuntime]
$null = [Windows.Storage.Streams.IRandomAccessStream,Windows.Storage.Streams,ContentType=WindowsRuntime]
$null = [Windows.Graphics.Imaging.BitmapDecoder,Windows.Graphics.Imaging,ContentType=WindowsRuntime]
$null = [Windows.Graphics.Imaging.SoftwareBitmap,Windows.Graphics.Imaging,ContentType=WindowsRuntime]
$null = [Windows.Media.Ocr.OcrEngine,Windows.Foundation,ContentType=WindowsRuntime]
$asTask = [System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
    $_.Name -eq 'AsTask' -and $_.IsGenericMethod -and $_.GetGenericArguments().Count -eq 1 -and $_.GetParameters().Count -eq 1
} | Select-Object -First 1

function Wait-OcrOperation($operation, $resultType) {
    $task = $asTask.MakeGenericMethod($resultType).Invoke($null, @($operation))
    $task.Wait(-1) | Out-Null
    $task.Result
}

$language = [Windows.Globalization.Language,Windows.Globalization,ContentType=WindowsRuntime]::new('zh-Hans-CN')
$engine = [Windows.Media.Ocr.OcrEngine]::TryCreateFromLanguage($language)
if (-not $engine) { throw 'Chinese Windows OCR recognizer is unavailable.' }

function Read-OcrStream($stream, $path) {
    $bitmap = $null
    try {
        $decoder = Wait-OcrOperation ([Windows.Graphics.Imaging.BitmapDecoder]::CreateAsync($stream)) ([Windows.Graphics.Imaging.BitmapDecoder])
        $bitmap = Wait-OcrOperation ($decoder.GetSoftwareBitmapAsync()) ([Windows.Graphics.Imaging.SoftwareBitmap])
        $result = Wait-OcrOperation ($engine.RecognizeAsync($bitmap)) ([Windows.Media.Ocr.OcrResult,Windows.Foundation,ContentType=WindowsRuntime])
        $lines = foreach ($line in $result.Lines) {
            $words = foreach ($word in $line.Words) {
                $rect = $word.BoundingRect
                [pscustomobject]@{text=$word.Text; x=$rect.X; y=$rect.Y; w=$rect.Width; h=$rect.Height}
            }
            [pscustomobject]@{text=$line.Text; words=@($words)}
        }
        [pscustomobject]@{path=$path; width=$bitmap.PixelWidth; height=$bitmap.PixelHeight; lines=@($lines)}
    }
    finally {
        if ($bitmap) { $bitmap.Dispose() }
        $stream.Dispose()
    }
}

if ($ReadStdin) {
    $data = [Convert]::FromBase64String([Console]::In.ReadToEnd())
    $stream = [Windows.Storage.Streams.InMemoryRandomAccessStream,Windows.Storage.Streams,ContentType=WindowsRuntime]::new()
    $writer = [Windows.Storage.Streams.DataWriter,Windows.Storage.Streams,ContentType=WindowsRuntime]::new($stream)
    try {
        $writer.WriteBytes($data)
        $null = Wait-OcrOperation ($writer.StoreAsync()) ([uint32])
        $null = $writer.DetachStream()
    } finally {
        $writer.Dispose()
    }
    $stream.Seek(0)
    $reports = Read-OcrStream $stream '<stdin>'
} else {
    $reports = foreach ($inputPath in $ImagePath) {
        $path = [IO.Path]::GetFullPath($inputPath)
        $file = Wait-OcrOperation ([Windows.Storage.StorageFile]::GetFileFromPathAsync($path)) ([Windows.Storage.StorageFile])
        $stream = Wait-OcrOperation ($file.OpenAsync([Windows.Storage.FileAccessMode,Windows.Storage,ContentType=WindowsRuntime]::Read)) ([Windows.Storage.Streams.IRandomAccessStream])
        Read-OcrStream $stream $path
    }
}
ConvertTo-Json -InputObject @($reports) -Depth 8 -Compress
