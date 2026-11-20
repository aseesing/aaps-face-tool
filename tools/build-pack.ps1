# Builds Pack (https://github.com/google/pack, Apache-2.0) on Windows. The same as build-pack.sh
# for macOS and Linux; see docs/wff-apk.md. NOT TESTED on a Windows machine yet.
#
# Needs: git, Rust (https://rustup.rs, with the Visual Studio C++ build tools it asks for), and
# openssl on the PATH (Git for Windows has one in C:\Program Files\Git\usr\bin).
# Everything it makes goes into %USERPROFILE%\.cache\aaps-wff (or %AAPS_WFF_CACHE%):
#   pack\target\release\pack-cli.exe   the tool
#   dev-key.pem                        the signing key of this machine, made once
#
# usage: powershell -ExecutionPolicy Bypass -File tools\build-pack.ps1
$ErrorActionPreference = "Stop"
$PackCommit = "7b60931e4058d892344ac57e49fcb582fd349b76"
$ProtocVersion = "36.2"
$ProtocSha256 = "f0c128dc0d8492eceece83bb459a4c0e316764b929ffbf1aa416357fd644edd3"   # protoc-36.2-win64.zip
$Dir = if ($env:AAPS_WFF_CACHE) { $env:AAPS_WFF_CACHE } else { Join-Path $env:USERPROFILE ".cache\aaps-wff" }
New-Item -ItemType Directory -Force -Path $Dir | Out-Null
if (-not (Get-Command cargo -ErrorAction SilentlyContinue)) { throw "Rust is needed: install it with rustup (https://rustup.rs)" }

# Pack's build needs protoc. Set PROTOC to use your own; else the official release is fetched and checked.
if (-not $env:PROTOC) {
    $Protoc = Join-Path $Dir "protoc-$ProtocVersion\bin\protoc.exe"
    if (-not (Test-Path $Protoc)) {
        $Zip = Join-Path $Dir "protoc-$ProtocVersion.zip"
        Invoke-WebRequest -Uri "https://github.com/protocolbuffers/protobuf/releases/download/v$ProtocVersion/protoc-$ProtocVersion-win64.zip" -OutFile $Zip
        if ((Get-FileHash $Zip -Algorithm SHA256).Hash.ToLower() -ne $ProtocSha256) { Remove-Item $Zip; throw "The protoc download does not match its SHA-256" }
        Expand-Archive -Force -Path $Zip -DestinationPath (Join-Path $Dir "protoc-$ProtocVersion")
        Remove-Item $Zip
    }
    $env:PROTOC = $Protoc
}

# Pack itself, at a fixed commit
$Src = Join-Path $Dir "pack"
if (-not (Test-Path (Join-Path $Src ".git"))) { git clone -q https://github.com/google/pack.git $Src }
if ((git -C $Src rev-parse HEAD) -ne $PackCommit) {
    git -C $Src fetch -q origin
    git -C $Src checkout -q $PackCommit
}
cargo build -q --release --manifest-path (Join-Path $Src "Cargo.toml") -p pack-cli

# A signing key for the faces built on this machine, made once
$Key = Join-Path $Dir "dev-key.pem"
if (-not (Test-Path $Key)) {
    $KeyTmp = Join-Path $Dir "dev-key.tmp"; $CertTmp = Join-Path $Dir "dev-cert.tmp"
    openssl req -x509 -newkey rsa:2048 -nodes -days 10000 -subj "/CN=AAPS WFF dev" -keyout $KeyTmp -out $CertTmp 2>$null
    (Get-Content $CertTmp -Raw) + (Get-Content $KeyTmp -Raw) | Set-Content -NoNewline -Encoding ascii $Key
    Remove-Item $KeyTmp, $CertTmp
}
Write-Output (Join-Path $Src "target\release\pack-cli.exe")
