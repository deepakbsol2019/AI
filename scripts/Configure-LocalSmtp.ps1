[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$SenderEmail,

    [string]$SmtpHost = 'smtp.gmail.com',

    [ValidateRange(1, 65535)]
    [int]$SmtpPort = 587
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Assert-WithinDirectory {
    param([string]$Path, [string]$Directory)
    $boundary = [System.IO.Path]::GetFullPath($Directory).TrimEnd([char[]]'\/') + [System.IO.Path]::DirectorySeparatorChar
    $candidate = [System.IO.Path]::GetFullPath($Path)
    if (-not $candidate.StartsWith($boundary, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw 'The private SMTP path must remain inside the project target directory.'
    }
}

function Assert-SafeDirectory {
    param([string]$Path)
    $item = Get-Item -LiteralPath $Path -Force
    if (-not $item.PSIsContainer -or ($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint)) {
        throw 'An SMTP directory is missing, is not a directory, or is a reparse point.'
    }
}

function Assert-SafeFile {
    param([string]$Path)
    $item = Get-Item -LiteralPath $Path -Force
    if ($item.PSIsContainer -or ($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint)) {
        throw 'The private SMTP file must be a regular file, not a reparse point.'
    }
}

function Assert-PrivateAcl {
    param([string]$Path, [System.Security.Principal.SecurityIdentifier]$UserSid, [switch]$Directory,
        [System.Security.Principal.SecurityIdentifier]$RuntimeReaderSid = $null)
    $acl = Get-Acl -LiteralPath $Path
    if (-not $acl.AreAccessRulesProtected -or $acl.GetOwner([System.Security.Principal.SecurityIdentifier]).Value -ne $UserSid.Value) {
        throw 'The private SMTP permissions could not be verified.'
    }
    $seenUser = $false
    $seenSystem = $false
    $hasReader = $null -ne $RuntimeReaderSid -and $RuntimeReaderSid.Value -ne $UserSid.Value
    $seenReader = -not $hasReader
    $readerRights = [System.Security.AccessControl.FileSystemRights]::Read
    if ($Directory) { $readerRights = [System.Security.AccessControl.FileSystemRights]::ReadAndExecute }
    if ($hasReader) {
        # Windows may add Synchronize to an Allow rule; compare the normalized expected rule.
        $readerRights = ([System.Security.AccessControl.FileSystemAccessRule]::new($RuntimeReaderSid, $readerRights,
            [System.Security.AccessControl.AccessControlType]::Allow)).FileSystemRights
    }
    $inheritance = [System.Security.AccessControl.InheritanceFlags]::ContainerInherit -bor [System.Security.AccessControl.InheritanceFlags]::ObjectInherit
    foreach ($rule in $acl.GetAccessRules($true, $true, [System.Security.Principal.SecurityIdentifier])) {
        $sid = $rule.IdentityReference.Value
        $isReader = $hasReader -and $sid -eq $RuntimeReaderSid.Value
        $expectedRights = [System.Security.AccessControl.FileSystemRights]::FullControl
        if ($isReader) { $expectedRights = $readerRights }
        if (($sid -ne $UserSid.Value -and $sid -ne 'S-1-5-18' -and -not $isReader) -or $rule.IsInherited -or
            $rule.AccessControlType -ne [System.Security.AccessControl.AccessControlType]::Allow -or $rule.FileSystemRights -ne $expectedRights) {
            throw 'The private SMTP permissions contain an unexpected access rule.'
        }
        if ($Directory -and ($rule.InheritanceFlags -ne $inheritance -or
            $rule.PropagationFlags -ne [System.Security.AccessControl.PropagationFlags]::None)) {
            throw 'The private SMTP directory must pass its restricted permissions to children.'
        }
        if ($sid -eq $UserSid.Value) { $seenUser = $true }
        if ($sid -eq 'S-1-5-18') { $seenSystem = $true }
        if ($isReader) { $seenReader = $true }
    }
    if (-not $seenUser -or -not $seenSystem -or -not $seenReader) { throw 'Required private SMTP permissions are missing.' }
}

function Set-PrivateDirectoryAcl {
    param([string]$Path, [System.Security.Principal.SecurityIdentifier]$UserSid,
        [System.Security.Principal.SecurityIdentifier]$RuntimeReaderSid = $null)
    Assert-SafeDirectory $Path
    $acl = [System.Security.AccessControl.DirectorySecurity]::new()
    $acl.SetAccessRuleProtection($true, $false)
    $acl.SetOwner($UserSid)
    $inheritance = [System.Security.AccessControl.InheritanceFlags]::ContainerInherit -bor [System.Security.AccessControl.InheritanceFlags]::ObjectInherit
    foreach ($sid in @($UserSid, [System.Security.Principal.SecurityIdentifier]::new('S-1-5-18'))) {
        $rule = [System.Security.AccessControl.FileSystemAccessRule]::new($sid,
            [System.Security.AccessControl.FileSystemRights]::FullControl, $inheritance,
            [System.Security.AccessControl.PropagationFlags]::None,
            [System.Security.AccessControl.AccessControlType]::Allow)
        $null = $acl.AddAccessRule($rule)
    }
    if ($null -ne $RuntimeReaderSid -and $RuntimeReaderSid.Value -ne $UserSid.Value) {
        $null = $acl.AddAccessRule([System.Security.AccessControl.FileSystemAccessRule]::new($RuntimeReaderSid,
            [System.Security.AccessControl.FileSystemRights]::ReadAndExecute, $inheritance,
            [System.Security.AccessControl.PropagationFlags]::None, [System.Security.AccessControl.AccessControlType]::Allow))
    }
    Set-Acl -LiteralPath $Path -AclObject $acl
    Assert-PrivateAcl $Path $UserSid -Directory -RuntimeReaderSid $RuntimeReaderSid
}

function Set-PrivateFileAcl {
    param([string]$Path, [System.Security.Principal.SecurityIdentifier]$UserSid,
        [System.Security.Principal.SecurityIdentifier]$RuntimeReaderSid = $null)
    Assert-SafeFile $Path
    $acl = [System.Security.AccessControl.FileSecurity]::new()
    $acl.SetAccessRuleProtection($true, $false)
    $acl.SetOwner($UserSid)
    foreach ($sid in @($UserSid, [System.Security.Principal.SecurityIdentifier]::new('S-1-5-18'))) {
        $rule = [System.Security.AccessControl.FileSystemAccessRule]::new($sid,
            [System.Security.AccessControl.FileSystemRights]::FullControl,
            [System.Security.AccessControl.AccessControlType]::Allow)
        $null = $acl.AddAccessRule($rule)
    }
    if ($null -ne $RuntimeReaderSid -and $RuntimeReaderSid.Value -ne $UserSid.Value) {
        $null = $acl.AddAccessRule([System.Security.AccessControl.FileSystemAccessRule]::new($RuntimeReaderSid,
            [System.Security.AccessControl.FileSystemRights]::Read, [System.Security.AccessControl.AccessControlType]::Allow))
    }
    Set-Acl -LiteralPath $Path -AclObject $acl
    Assert-PrivateAcl $Path $UserSid -RuntimeReaderSid $RuntimeReaderSid
}

function Get-OptionalRuntimeReaderSid {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) { return $null }
    Assert-SafeFile $Path
    if ((Get-Item -LiteralPath $Path -Force).Length -gt 512) { throw 'The optional runtime reader SID file is invalid.' }
    try {
        $sid = [System.Security.Principal.SecurityIdentifier]::new([System.IO.File]::ReadAllText($Path).Trim())
        if (-not $sid.IsAccountSid() -or $sid.Value -notmatch '^S-1-5-21-(?:[0-9]+-){3}[0-9]+$') {
            throw 'Invalid account SID.'
        }
        if (-not ('BookSearchSmtpHelper.AccountSidCheck' -as [type])) {
            Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
using System.Text;
namespace BookSearchSmtpHelper {
    public static class AccountSidCheck {
        [DllImport("advapi32.dll", CharSet=CharSet.Unicode, SetLastError=true)]
        private static extern bool LookupAccountSid(string systemName, byte[] sid, StringBuilder name,
            ref uint nameLength, StringBuilder domain, ref uint domainLength, out int use);
        public static bool IsUserAccount(byte[] sid) {
            uint nameLength=0, domainLength=0; int use;
            LookupAccountSid(null, sid, null, ref nameLength, null, ref domainLength, out use);
            if (nameLength==0) return false;
            var name=new StringBuilder((int)nameLength);
            var domain=new StringBuilder((int)Math.Max(domainLength,1));
            return LookupAccountSid(null,sid,name,ref nameLength,domain,ref domainLength,out use) && use==1;
        }
    }
}
'@ | Out-Null
        }
        $bytes = [byte[]]::new($sid.BinaryLength)
        $sid.GetBinaryForm($bytes, 0)
        if (-not [BookSearchSmtpHelper.AccountSidCheck]::IsUserAccount($bytes)) { throw 'Invalid user account SID.' }
        return $sid
    } catch {
        throw 'The optional runtime reader SID must identify an existing local or domain user account.'
    }
}

function ConvertTo-JavaPropertyValue {
    param([AllowEmptyString()][string]$Value)
    $escaped = [System.Text.StringBuilder]::new()
    foreach ($character in $Value.ToCharArray()) {
        $code = [int]$character
        switch ($code) {
            0x5c { $null = $escaped.Append('\\'); break }
            0x0d { $null = $escaped.Append('\r'); break }
            0x0a { $null = $escaped.Append('\n'); break }
            0x09 { $null = $escaped.Append('\t'); break }
            0x0c { $null = $escaped.Append('\f'); break }
            0x20 { $null = $escaped.Append('\ '); break }
            0x3d { $null = $escaped.Append('\='); break }
            0x3a { $null = $escaped.Append('\:'); break }
            0x23 { $null = $escaped.Append('\#'); break }
            0x21 { $null = $escaped.Append('\!'); break }
            default {
                if ($code -lt 0x20 -or $code -gt 0x7e) {
                    $null = $escaped.Append(('\u{0:x4}' -f $code))
                } else {
                    $null = $escaped.Append($character)
                }
            }
        }
    }
    return $escaped.ToString()
}

if ([System.Environment]::OSVersion.Platform -ne [System.PlatformID]::Win32NT) {
    throw 'Run this helper on Windows in the existing backend project.'
}
try { $sender = [System.Net.Mail.MailAddress]::new($SenderEmail) }
catch { throw 'SenderEmail must be one exact email address without a display name.' }
if ($sender.Address -cne $SenderEmail -or -not [string]::IsNullOrEmpty($sender.DisplayName) -or
    $SenderEmail.Contains("`r") -or $SenderEmail.Contains("`n")) {
    throw 'SenderEmail must be one exact email address without a display name.'
}
if ([string]::IsNullOrWhiteSpace($SmtpHost) -or [System.Uri]::CheckHostName($SmtpHost) -eq [System.UriHostNameType]::Unknown) {
    throw 'SmtpHost must be a valid SMTP host name or IP address.'
}

Assert-SafeDirectory $PSScriptRoot
$projectRoot = (Get-Item -LiteralPath (Join-Path $PSScriptRoot '..') -Force).FullName
Assert-SafeDirectory $projectRoot
$applicationProperties = Join-Path $projectRoot 'src/main/resources/application.properties'
$projectPom = Join-Path $projectRoot 'pom.xml'
if (-not (Test-Path -LiteralPath $applicationProperties -PathType Leaf) -or -not (Test-Path -LiteralPath $projectPom -PathType Leaf)) {
    throw 'Run the helper from the existing Book-Search-Download backend scripts directory.'
}
Assert-SafeFile $applicationProperties
Assert-SafeFile $projectPom
if (-not (Select-String -LiteralPath $applicationProperties -Pattern '^spring\.application\.name=Book-Search-Download\s*$' -Quiet) -or
    -not (Select-String -LiteralPath $projectPom -Pattern '<artifactId>Book-Search-Download</artifactId>' -Quiet)) {
    throw 'The helper does not belong to the expected backend application.'
}

# Reject redirected/noninteractive execution before touching the private directory or prompting.
$nonInteractive = @([System.Environment]::GetCommandLineArgs() | Where-Object { $_ -match '^-(NonInteractive|NonI)$' }).Count -gt 0
if ($nonInteractive -or [System.Console]::IsInputRedirected -or [System.Console]::IsOutputRedirected) {
    throw 'Run this helper directly in an interactive Windows PowerShell terminal without redirection.'
}
$git = Get-Command git -CommandType Application -ErrorAction SilentlyContinue
if ($null -eq $git) { throw 'Git is required to verify that the private SMTP file is ignored.' }
& $git.Source -C $projectRoot check-ignore --quiet -- 'target/private-smtp/application-smtp.properties' 2>$null
if ($LASTEXITCODE -ne 0) { throw 'The private SMTP file must be ignored by Git before credentials can be saved.' }

$targetDirectory = [System.IO.Path]::GetFullPath((Join-Path $projectRoot 'target'))
$privateDirectory = [System.IO.Path]::GetFullPath((Join-Path $targetDirectory 'private-smtp'))
$configurationPath = [System.IO.Path]::GetFullPath((Join-Path $privateDirectory 'application-smtp.properties'))
Assert-WithinDirectory $privateDirectory $targetDirectory
Assert-WithinDirectory $configurationPath $privateDirectory
foreach ($directory in @($targetDirectory, $privateDirectory)) {
    if (-not (Test-Path -LiteralPath $directory)) { $null = [System.IO.Directory]::CreateDirectory($directory) }
    Assert-SafeDirectory $directory
}
$userSid = [System.Security.Principal.WindowsIdentity]::GetCurrent().User
$readerSidPath = Join-Path $targetDirectory 'local-runtime-reader.sid'
Assert-WithinDirectory $readerSidPath $targetDirectory
$runtimeReaderSid = Get-OptionalRuntimeReaderSid $readerSidPath
Set-PrivateDirectoryAcl $privateDirectory $userSid $runtimeReaderSid
if (Test-Path -LiteralPath $configurationPath) { Set-PrivateFileAcl $configurationPath $userSid $runtimeReaderSid }

$securePassword = $null
$passwordPointer = [System.IntPtr]::Zero
$plainPassword = $null
$escapedPassword = $null
$properties = $null
$temporaryPath = $null
try {
    $securePassword = Read-Host 'SMTP app password (private input)' -AsSecureString
    $passwordPointer = [System.Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePassword)
    $plainPassword = [System.Runtime.InteropServices.Marshal]::PtrToStringBSTR($passwordPointer)
    if ($SmtpHost -ieq 'smtp.gmail.com') { $plainPassword = [System.Text.RegularExpressions.Regex]::Replace($plainPassword, '\s', '') }
    if ([string]::IsNullOrWhiteSpace($plainPassword)) { throw 'The SMTP app password cannot be blank.' }
    $escapedPassword = ConvertTo-JavaPropertyValue $plainPassword
    $escapedSender = ConvertTo-JavaPropertyValue $SenderEmail
    $properties = @(
        'app.user.mail.enabled=true'
        ('spring.mail.host=' + (ConvertTo-JavaPropertyValue $SmtpHost))
        ('spring.mail.port=' + $SmtpPort.ToString([System.Globalization.CultureInfo]::InvariantCulture))
        ('spring.mail.username=' + $escapedSender)
        ('spring.mail.password=' + $escapedPassword)
        ('app.mail.from=' + $escapedSender)
        'spring.mail.test-connection=true'
    ) -join "`n"

    # Recheck the fixed destination after human input. Write a private temporary file, then replace
    # only this directory entry so an existing hard link cannot overwrite a different file's bytes.
    Assert-SafeDirectory $projectRoot
    Assert-SafeDirectory $targetDirectory
    Assert-SafeDirectory $privateDirectory
    Assert-PrivateAcl $privateDirectory $userSid -Directory -RuntimeReaderSid $runtimeReaderSid
    if (Test-Path -LiteralPath $configurationPath) { Set-PrivateFileAcl $configurationPath $userSid $runtimeReaderSid }
    $temporaryPath = Join-Path $privateDirectory ('.smtp-' + [Guid]::NewGuid().ToString('N') + '.tmp')
    Assert-WithinDirectory $temporaryPath $privateDirectory
    $null = New-Item -Path $temporaryPath -ItemType File -ErrorAction Stop
    Set-PrivateFileAcl $temporaryPath $userSid $runtimeReaderSid
    [System.IO.File]::WriteAllText($temporaryPath, $properties + "`n", [System.Text.UTF8Encoding]::new($false))
    if (Test-Path -LiteralPath $configurationPath) {
        Assert-SafeFile $configurationPath
        [System.IO.File]::Replace($temporaryPath, $configurationPath, $null)
    } else {
        [System.IO.File]::Move($temporaryPath, $configurationPath)
    }
    $temporaryPath = $null
    Assert-SafeFile $configurationPath
    Assert-PrivateAcl $configurationPath $userSid -RuntimeReaderSid $runtimeReaderSid
}
catch {
    throw 'SMTP configuration was not saved successfully. Check the input and private directory permissions, then try again.'
}
finally {
    if ($passwordPointer -ne [System.IntPtr]::Zero) { [System.Runtime.InteropServices.Marshal]::ZeroFreeBSTR($passwordPointer) }
    if ($null -ne $securePassword) { $securePassword.Dispose() }
    $plainPassword = $null
    $escapedPassword = $null
    $properties = $null
    if ($null -ne $temporaryPath -and (Test-Path -LiteralPath $temporaryPath -PathType Leaf)) {
        try {
            Assert-WithinDirectory $temporaryPath $privateDirectory
            Assert-SafeFile $temporaryPath
            Remove-Item -LiteralPath $temporaryPath -Force
        } catch { }
    }
}

Write-Output $configurationPath
Write-Output 'Private SMTP configuration saved. Reply ready in the chat.'
