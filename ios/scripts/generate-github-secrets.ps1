param(
    [string]$GoogleServiceInfoPlist,
    [string]$P12Certificate,
    [string]$ProvisioningProfile
)

function Convert-FileToBase64 {
    param([Parameter(Mandatory = $true)][string]$Path)

    if (-not (Test-Path -LiteralPath $Path)) {
        throw "File not found: $Path"
    }

    [Convert]::ToBase64String([IO.File]::ReadAllBytes((Resolve-Path -LiteralPath $Path)))
}

Write-Host ""
Write-Host "PHANToM GO iOS GitHub Secrets Helper" -ForegroundColor Cyan
Write-Host "Copy each output below into the matching GitHub Actions secret." -ForegroundColor DarkGray
Write-Host ""

if ($GoogleServiceInfoPlist) {
    Write-Host "===== IOS_FIREBASE_PLIST_BASE64 =====" -ForegroundColor Yellow
    Convert-FileToBase64 -Path $GoogleServiceInfoPlist
    Write-Host ""
}

if ($P12Certificate) {
    Write-Host "===== BUILD_CERTIFICATE_BASE64 =====" -ForegroundColor Yellow
    Convert-FileToBase64 -Path $P12Certificate
    Write-Host ""
}

if ($ProvisioningProfile) {
    Write-Host "===== BUILD_PROVISION_PROFILE_BASE64 =====" -ForegroundColor Yellow
    Convert-FileToBase64 -Path $ProvisioningProfile
    Write-Host ""
}

Write-Host "Manual secrets you still need to prepare:" -ForegroundColor Green
Write-Host "- P12_PASSWORD"
Write-Host "- KEYCHAIN_PASSWORD"
Write-Host "- APPLE_TEAM_ID"
Write-Host "- Optional: IOS_BUNDLE_IDENTIFIER"
Write-Host "- Optional: CODE_SIGN_IDENTITY"
