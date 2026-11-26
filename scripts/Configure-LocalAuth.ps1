[CmdletBinding()]
param(
    [string]$ProjectRoot = (Split-Path -Parent $PSScriptRoot)
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Assert-SafePath {
    param([string]$Path, [bool]$Directory)
    $item = Get-Item -LiteralPath $Path -Force
    if ($item.PSIsContainer -ne $Directory -or ($item.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
        throw 'Local authentication configuration must use ordinary files and directories, not links.'
    }
}

function Read-ValidKey {
    param([string]$Path)
    Assert-SafePath $Path $false
    $entries = @([IO.File]::ReadAllLines($Path) | Where-Object { $_ -match '^\s*app\.user\.dev\.jwt-secret\s*[:=]' })
    if ($entries.Count -ne 1) { throw 'The local signing file must contain exactly one app.user.dev.jwt-secret property.' }
    $value = ($entries[0] -split '[:=]', 2)[1].TrimStart()
    # Java's Base64 decoder rejects whitespace that .NET would otherwise ignore.
    if ($value -notmatch '^[A-Za-z0-9+/]+={0,2}$') { throw 'The local signing key must be uninterrupted standard Base64. No key was replaced.' }
    try { $keyBytes = [Convert]::FromBase64String($value) }
    catch { throw 'The local signing key must be valid Base64. No key was replaced.' }
    if ($keyBytes.Length -lt 32) { throw 'The local signing key must decode to at least 32 bytes. No key was replaced.' }
    return $value
}

$root = (Resolve-Path -LiteralPath $ProjectRoot).Path
for ($ancestor = [IO.DirectoryInfo]::new($root); $null -ne $ancestor; $ancestor = $ancestor.Parent) {
    Assert-SafePath $ancestor.FullName $true
}
$configDirectory = Join-Path $root 'config'
if (-not (Test-Path -LiteralPath $configDirectory)) {
    $null = [IO.Directory]::CreateDirectory($configDirectory)
}
Assert-SafePath $configDirectory $true
$destination = Join-Path $configDirectory 'local-jwt.properties'

# Restrict only the private key file; other config examples retain their existing permissions.
$owner = [Security.Principal.WindowsIdentity]::GetCurrent().User
function Test-PrivateKeyAcl {
    param([string]$Path)
    $acl = Get-Acl -LiteralPath $Path
    if (-not $acl.AreAccessRulesProtected -or $acl.GetOwner([Security.Principal.SecurityIdentifier]).Value -ne $owner.Value) { return $false }
    $seen = @{}
    foreach ($rule in $acl.GetAccessRules($true, $true, [Security.Principal.SecurityIdentifier])) {
        $sid = $rule.IdentityReference.Value
        if (($sid -ne $owner.Value -and $sid -ne 'S-1-5-18') -or $rule.IsInherited -or
            $rule.AccessControlType -ne [Security.AccessControl.AccessControlType]::Allow -or
            $rule.FileSystemRights -ne [Security.AccessControl.FileSystemRights]::FullControl) { return $false }
        $seen[$sid] = $true
    }
    return ($seen.ContainsKey($owner.Value) -and $seen.ContainsKey('S-1-5-18'))
}
function Protect-KeyFile {
    param([string]$Path)
    Assert-SafePath $Path $false
    if (Test-PrivateKeyAcl $Path) { return }
    $acl = [Security.AccessControl.FileSecurity]::new()
    $acl.SetAccessRuleProtection($true, $false)
    $acl.SetOwner($owner)
    foreach ($sid in @($owner, [Security.Principal.SecurityIdentifier]::new('S-1-5-18'))) {
        $null = $acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new(
            $sid, [Security.AccessControl.FileSystemRights]::FullControl,
            [Security.AccessControl.AccessControlType]::Allow))
    }
    Set-Acl -LiteralPath $Path -AclObject $acl
    if (-not (Test-PrivateKeyAcl $Path)) { throw 'The private signing-key permissions could not be verified.' }
}

if (Test-Path -LiteralPath $destination) {
    $null = Read-ValidKey $destination
    Protect-KeyFile $destination
    Write-Output 'Local authentication signing key is already configured; existing key preserved.'
    return
}

$legacyDirectory = Join-Path $root 'target'
$legacyPath = Join-Path $legacyDirectory 'local-jwt.properties'
if (Test-Path -LiteralPath $legacyPath) {
    Assert-SafePath $legacyDirectory $true
    $signingKey = Read-ValidKey $legacyPath
} else {
    $randomBytes = [byte[]]::new(64)
    $generator = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $generator.GetBytes($randomBytes) } finally { $generator.Dispose() }
    $signingKey = [Convert]::ToBase64String($randomBytes)
}

# CreateNew refuses to overwrite a concurrent configuration. Set its ACL while still empty.
$stream = [IO.File]::Open($destination, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
try {
    Protect-KeyFile $destination
    $content = "# Local development signing key. Keep private; excluded from source control and the JAR.`napp.user.dev.jwt-secret=$signingKey`n"
    $bytes = [Text.UTF8Encoding]::new($false).GetBytes($content)
    $stream.Write($bytes, 0, $bytes.Length)
    $stream.Flush()
} finally {
    $stream.Dispose()
    $signingKey = $null
}
$null = Read-ValidKey $destination
Write-Output 'Local authentication signing key configured in config/local-jwt.properties. Restart the backend to load it.'
