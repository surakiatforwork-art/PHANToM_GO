param(
    [string]$P12Certificate,
    [string]$ProvisioningProfile,
    [string]$ShareProvisioningProfile,
    [string]$AppleTeamId,
    [string]$Repository = "surakiatforwork-art/PHANToM_GO"
)

$ErrorActionPreference = "Stop"

function Set-FileSecret {
    param([string]$Name, [string]$FilePath)
    if (-not (Test-Path -LiteralPath $FilePath -PathType Leaf)) {
        throw "Signing file not found: $FilePath"
    }
    $resolvedSigningPath = (Resolve-Path -LiteralPath $FilePath).Path
    $encodedSigningFile = [Convert]::ToBase64String([IO.File]::ReadAllBytes($resolvedSigningPath))
    $encodedSigningFile | gh secret set $Name --repo $Repository
    if ($LASTEXITCODE -ne 0) { throw "Unable to save GitHub secret $Name" }
    Write-Output "Configured $Name; signing data was not printed."
}

if ($P12Certificate) { Set-FileSecret "BUILD_CERTIFICATE_BASE64" $P12Certificate }
if ($ProvisioningProfile) { Set-FileSecret "BUILD_PROVISION_PROFILE_BASE64" $ProvisioningProfile }
if ($ShareProvisioningProfile) { Set-FileSecret "BUILD_SHARE_PROVISION_PROFILE_BASE64" $ShareProvisioningProfile }
if ($AppleTeamId) {
    $AppleTeamId | gh secret set APPLE_TEAM_ID --repo $Repository
    if ($LASTEXITCODE -ne 0) { throw "Unable to save APPLE_TEAM_ID" }
}
Write-Output "Signed mode also needs P12_PASSWORD and KEYCHAIN_PASSWORD."
Write-Output "Set password secrets interactively with gh secret set; do not paste private data in chat."
Write-Output "Firebase plist is not required by the native REST implementation."
Write-Output "Unsigned mode requires no signing secrets."
