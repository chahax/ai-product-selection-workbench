[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [ValidatePattern('^(127\.0\.0\.1|10\.\d{1,3}\.\d{1,3}\.\d{1,3}|192\.168\.\d{1,3}\.\d{1,3}|172\.(1[6-9]|2\d|3[01])\.\d{1,3}\.\d{1,3})$')]
    [string]$Address,
    [ValidateRange(1, 65535)]
    [int]$Port = 3443,
    [ValidateRange(1, 65535)]
    [int]$WorkbenchPort = 3000
)

$ErrorActionPreference = 'Stop'
if (-not (Get-NetIPAddress -AddressFamily IPv4 -IPAddress $Address -ErrorAction SilentlyContinue)) {
    throw "本机没有 IPv4 地址 $Address"
}

$tlsDir = Join-Path $env:LOCALAPPDATA 'AI选品工作台\手机采集证书'
New-Item -ItemType Directory -Path $tlsDir -Force | Out-Null
$certificate = Join-Path $tlsDir "$Address.crt"
$privateKey = Join-Path $tlsDir "$Address.key"
$hasCertificate = Test-Path -LiteralPath $certificate -PathType Leaf
$hasPrivateKey = Test-Path -LiteralPath $privateKey -PathType Leaf
if ($hasCertificate -ne $hasPrivateKey) {
    throw '服务器证书和私钥只有其中一个文件，请先恢复配套文件'
}
if (-not $hasCertificate) {
    $openssl = (Get-Command openssl -CommandType Application).Source
    $certConfig = [IO.Path]::GetTempFileName()
    try {
        Set-Content -LiteralPath $certConfig -Value @('[req]', 'distinguished_name = request_dn', '[request_dn]') -Encoding ascii
        $certificateLog = & $openssl req -config $certConfig -x509 -newkey rsa:2048 -nodes -days 365 -subj "/CN=$Address" `
            -addext "subjectAltName=IP:$Address" -addext 'basicConstraints=critical,CA:FALSE' `
            -addext 'extendedKeyUsage=serverAuth' -keyout $privateKey -out $certificate 2>&1
        if ($LASTEXITCODE -ne 0) { throw "生成手机采集证书失败。$certificateLog" }
    } finally {
        Remove-Item -LiteralPath $certConfig -Force
    }
}

& node (Join-Path $PSScriptRoot 'mobile-gateway.mjs') --bind $Address --port $Port `
    --workbench-port $WorkbenchPort --cert $certificate --key $privateKey
if ($LASTEXITCODE -ne 0) { throw "手机采集入口启动失败（$LASTEXITCODE）" }
