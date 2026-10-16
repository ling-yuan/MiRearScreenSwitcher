# Generate the Android signing keystore and print the GitHub Actions secrets.
$ErrorActionPreference = 'Stop'

$alias = 'mrss'
$keystorePath = Join-Path $PSScriptRoot 'app/keystore.jks'
$keyPropertiesPath = Join-Path $PSScriptRoot 'key.properties'
$base64Path = Join-Path $PSScriptRoot 'keystore.base64.txt'

if (Test-Path $keystorePath) {
    throw "Keystore already exists: $keystorePath"
}

if (-not (Get-Command keytool -ErrorAction SilentlyContinue)) {
    throw 'keytool not found. Install a JDK 17 and add its bin folder to PATH.'
}

$secure = Read-Host -Prompt 'Keystore password' -AsSecureString
$plain = [Runtime.InteropServices.Marshal]::PtrToStringBSTR(
    [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure))
if ([string]::IsNullOrWhiteSpace($plain)) { throw 'Password cannot be empty.' }

# Password goes through an environment variable so it never appears in the process arguments.
$env:MRSS_STORE_PASSWORD = $plain
try {
    & keytool -genkeypair -v `
        -keystore $keystorePath `
        -storetype PKCS12 `
        -alias $alias `
        -keyalg RSA -keysize 4096 -validity 10000 `
        -dname 'CN=MRSS, OU=MRSS, O=MRSS, L=Unknown, ST=Unknown, C=CN' `
        -storepass:env MRSS_STORE_PASSWORD `
        -keypass:env MRSS_STORE_PASSWORD
    if ($LASTEXITCODE -ne 0) { throw "keytool failed with exit code $LASTEXITCODE" }
} finally {
    Remove-Item Env:MRSS_STORE_PASSWORD -ErrorAction SilentlyContinue
}

@"
storeFile=keystore.jks
keyAlias=$alias
storePassword=$plain
keyPassword=$plain
"@ | Set-Content -Path $keyPropertiesPath -Encoding ascii

[Convert]::ToBase64String([IO.File]::ReadAllBytes($keystorePath)) |
    Set-Content -Path $base64Path -Encoding ascii -NoNewline

Write-Host ''
Write-Host "Keystore  : $keystorePath"
Write-Host "Base64    : $base64Path"
Write-Host "key.properties written for local builds: $keyPropertiesPath"
Write-Host ''
Write-Host 'Add these repository secrets (Settings > Secrets and variables > Actions):'
Write-Host "  KEY_ALIAS         = $alias"
Write-Host '  KEYSTORE_PASSWORD = <the password you just typed>'
Write-Host '  KEY_PASSWORD      = <same as KEYSTORE_PASSWORD>'
Write-Host "  KEYSTORE_FILE     = contents of $base64Path"
Write-Host ''
Write-Host 'With GitHub CLI you can set them in one go:'
Write-Host "  gh secret set KEY_ALIAS --body ""$alias"""
Write-Host '  gh secret set KEYSTORE_PASSWORD'
Write-Host '  gh secret set KEY_PASSWORD'
Write-Host "  gh secret set KEYSTORE_FILE --body ""$(Get-Content $base64Path -Raw)"""
