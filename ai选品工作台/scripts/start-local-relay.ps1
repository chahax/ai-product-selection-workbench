[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string]$SshTarget,
    [Parameter(Mandatory)]
    [string]$IdentityFile,
    [Parameter(Mandatory)]
    [string]$WebOrigin,
    [ValidateRange(1024, 65535)]
    [int]$RelayPort = 18311,
    [ValidateRange(1, 65535)]
    [int]$LocalPort = 18310
)

$ErrorActionPreference = 'Stop'
$workbench = Split-Path -Parent $PSScriptRoot
$identity = (Resolve-Path -LiteralPath $IdentityFile).Path
$server = Join-Path $workbench 'dist-server\production.js'
$logs = Join-Path $env:LOCALAPPDATA 'AI选品工作台\relay'
$node = (Get-Command node -CommandType Application | Select-Object -First 1).Source
if (-not (Test-Path -LiteralPath $server -PathType Leaf)) {
    throw '请先在工作台目录运行 npm run build:server'
}
if (Get-NetTCPConnection -LocalPort $LocalPort -State Listen -ErrorAction SilentlyContinue) {
    throw "本机端口 $LocalPort 已被占用"
}
New-Item -ItemType Directory -Path $logs -Force | Out-Null

$env:WORKBENCH_DOUYIN_DB = Join-Path $workbench 'data\douyin.sqlite'
$env:WORKBENCH_BASE_PATH = '/ai-selection/'
$env:WORKBENCH_WEB_ORIGIN = $WebOrigin
$env:WORKBENCH_PORT = [string]$LocalPort
$backend = Start-Process -FilePath $node -ArgumentList @('dist-server/production.js') -WorkingDirectory $workbench `
    -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $logs 'backend-out.log') `
    -RedirectStandardError (Join-Path $logs 'backend-error.log')
try {
    while (-not $backend.HasExited) {
        $forward = '127.0.0.1:{0}:127.0.0.1:{1}' -f $RelayPort, $LocalPort
        & ssh -N -T -o BatchMode=yes -o StrictHostKeyChecking=yes -o ExitOnForwardFailure=yes -o ConnectTimeout=8 `
            -o ServerAliveInterval=20 -o ServerAliveCountMax=3 -o IdentitiesOnly=yes `
            -i $identity -R $forward $SshTarget
        if (-not $backend.HasExited) { Start-Sleep -Seconds 5 }
    }
    throw "本地工作台已退出，详情见 $logs"
} finally {
    if (-not $backend.HasExited) { Stop-Process -Id $backend.Id }
}
