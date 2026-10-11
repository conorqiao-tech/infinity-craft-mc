$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
try {
    $python = Join-Path $PSScriptRoot '.runtime\python\python.exe'
    if (-not (Test-Path -LiteralPath $python)) {
        if ([System.Runtime.InteropServices.RuntimeInformation]::OSArchitecture.ToString() -ne 'X64') {
            throw 'Automatic setup supports Windows x64. See README.md for manual setup.'
        }
        New-Item -ItemType Directory -Force -Path '.runtime' | Out-Null
        $lock = New-Item -ItemType Directory -Path '.runtime\bootstrap.lock'
        $stage = Join-Path $PSScriptRoot ('.runtime\python-stage-' + [guid]::NewGuid().ToString('N'))
        try {
            New-Item -ItemType Directory -Path $stage | Out-Null
            $pin = Get-Content -LiteralPath 'setup\windows-x64.txt'
            $archive = Join-Path $stage 'python.tar.gz'
            Write-Host 'Infinity Armor: preparing the launcher (first time only)...'
            [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
            Invoke-WebRequest -Uri $pin[0] -OutFile $archive -UseBasicParsing
            if ((Get-FileHash -Algorithm SHA256 -LiteralPath $archive).Hash.ToLowerInvariant() -ne $pin[1]) {
                throw 'Download verification failed. Open the launcher to retry.'
            }
            & tar.exe -xzf $archive -C $stage
            if ($LASTEXITCODE -ne 0) { throw 'Unable to unpack Python. Windows 10/11 tar.exe is required.' }
            & (Join-Path $stage 'python\python.exe') -c 'import ssl, http.server, pathlib'
            if ($LASTEXITCODE -ne 0) { throw 'The downloaded launcher could not start.' }
            if (Test-Path -LiteralPath '.runtime\python') { throw 'Incomplete runtime exists. Remove .runtime\python and retry.' }
            Move-Item -LiteralPath (Join-Path $stage 'python') -Destination '.runtime\python'
        } finally {
            Remove-Item -LiteralPath $stage -Recurse -Force -ErrorAction SilentlyContinue
            Remove-Item -LiteralPath $lock.FullName -Force -ErrorAction SilentlyContinue
        }
    }
    & $python server.py --dashboard @args
    exit $LASTEXITCODE
} catch {
    Write-Host ('Setup could not finish: ' + $_.Exception.Message) -ForegroundColor Red
    exit 1
}
