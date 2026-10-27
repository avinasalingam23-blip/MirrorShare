[CmdletBinding()]
param(
    [ValidateSet('All', 'Windows', 'Android')][string]$Target = 'All',
    [string]$AndroidSdkPath,
    [string]$JavaInstallPath
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$ProjectRoot = $PSScriptRoot
$Artifacts = Join-Path $ProjectRoot 'artifacts'
$TranscriptStarted = $false
$OriginalEnvironment = @{}
foreach ($Name in @('JAVA_HOME','ANDROID_HOME','ANDROID_SDK_ROOT','PATH',
    'MIRRORSHARE_KEYSTORE_PATH','MIRRORSHARE_KEYSTORE_PASSWORD','MIRRORSHARE_KEY_ALIAS')) {
    $OriginalEnvironment[$Name] = [Environment]::GetEnvironmentVariable($Name, 'Process')
}

function Invoke-BuildTool {
    param([string]$Executable, [string[]]$ToolArguments)
    & $Executable @ToolArguments
    if ($LASTEXITCODE -ne 0) { throw "Build tool failed with exit code $LASTEXITCODE. Read its first error above." }
}

function Initialize-AndroidTools {
    if ([string]::IsNullOrWhiteSpace($script:AndroidSdkPath)) {
        foreach ($Candidate in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT,
            (Join-Path $env:LOCALAPPDATA 'Android\Sdk'))) {
            if (-not [string]::IsNullOrWhiteSpace($Candidate) -and (Test-Path -LiteralPath $Candidate)) {
                $script:AndroidSdkPath = $Candidate; break
            }
        }
    }
    if ([string]::IsNullOrWhiteSpace($script:AndroidSdkPath) -or -not (Test-Path -LiteralPath $script:AndroidSdkPath)) {
        throw 'Android SDK not found. Install Android Studio and its SDK, or pass -AndroidSdkPath with the SDK folder.'
    }
    if ([string]::IsNullOrWhiteSpace($script:JavaInstallPath)) {
        foreach ($Candidate in @($env:JAVA_HOME, (Join-Path $env:ProgramFiles 'Android\Android Studio\jbr'))) {
            if (-not [string]::IsNullOrWhiteSpace($Candidate) -and
                (Test-Path -LiteralPath (Join-Path $Candidate 'bin\javac.exe')) -and
                (Test-Path -LiteralPath (Join-Path $Candidate 'bin\keytool.exe'))) {
                $script:JavaInstallPath = $Candidate; break
            }
        }
    }
    if ([string]::IsNullOrWhiteSpace($script:JavaInstallPath) -or
        -not (Test-Path -LiteralPath (Join-Path $script:JavaInstallPath 'bin\javac.exe'))) {
        throw 'A JDK is required. Select Android Studio''s bundled JDK or pass -JavaInstallPath. JDK 17 or 21 is recommended.'
    }
    $ReleaseFile = Join-Path $script:JavaInstallPath 'release'
    if (Test-Path -LiteralPath $ReleaseFile) {
        if ((Get-Content -LiteralPath $ReleaseFile -Raw) -match 'JAVA_VERSION="(\d+)') {
            $Major = [int]$Matches[1]
            if ($Major -lt 17 -or $Major -gt 23) { throw "Select JDK 17 or 21; the selected JDK $Major is outside this Gradle runtime range." }
        }
    }
    $env:JAVA_HOME = $script:JavaInstallPath
    $env:ANDROID_HOME = $script:AndroidSdkPath
    $env:ANDROID_SDK_ROOT = $script:AndroidSdkPath
    $env:PATH = (Join-Path $env:JAVA_HOME 'bin') + ';' + $env:PATH
    $Required = @()
    if (-not (Test-Path -LiteralPath (Join-Path $script:AndroidSdkPath 'platforms\android-36\android.jar'))) { $Required += 'platforms;android-36' }
    if (-not (Test-Path -LiteralPath (Join-Path $script:AndroidSdkPath 'build-tools\35.0.0\apksigner.bat'))) { $Required += 'build-tools;35.0.0' }
    if ($Required.Count -gt 0) {
        $SdkManager = Join-Path $script:AndroidSdkPath 'cmdline-tools\latest\bin\sdkmanager.bat'
        if (-not (Test-Path -LiteralPath $SdkManager)) {
            $Found = @(Get-ChildItem -LiteralPath (Join-Path $script:AndroidSdkPath 'cmdline-tools') -Filter 'sdkmanager.bat' -Recurse -ErrorAction SilentlyContinue)
            if ($Found.Count -gt 0) { $SdkManager = $Found[0].FullName }
        }
        if (-not (Test-Path -LiteralPath $SdkManager)) { throw 'Install SDK Platform 36 and Build Tools 35.0.0 using Android Studio > SDK Manager, then rerun.' }
        Write-Host 'Installing missing SDK components. Respond to any SDK license prompt.' -ForegroundColor Cyan
        Invoke-BuildTool $SdkManager (@('--install') + $Required)
    }
}

function Initialize-ReleaseSigning {
    $HasPath = -not [string]::IsNullOrWhiteSpace($env:MIRRORSHARE_KEYSTORE_PATH)
    $HasPassword = -not [string]::IsNullOrWhiteSpace($env:MIRRORSHARE_KEYSTORE_PASSWORD)
    if ($HasPath -or $HasPassword) {
        if (-not ($HasPath -and $HasPassword)) { throw 'Set both signing-path and signing-password environment variables, or clear both to use the local key.' }
        if (-not (Test-Path -LiteralPath $env:MIRRORSHARE_KEYSTORE_PATH)) { throw 'The configured Android signing keystore does not exist.' }
        if ([string]::IsNullOrWhiteSpace($env:MIRRORSHARE_KEY_ALIAS)) { $env:MIRRORSHARE_KEY_ALIAS = 'mirrorshare' }
        return
    }
    $SigningDirectory = Join-Path $env:LOCALAPPDATA 'Mirror Share\Signing'
    New-Item -ItemType Directory -Path $SigningDirectory -Force | Out-Null
    $KeyStore = Join-Path $SigningDirectory 'MirrorShare-TV.jks'
    $PasswordFile = Join-Path $SigningDirectory 'keystore-password.dpapi'
    $KeyExists = Test-Path -LiteralPath $KeyStore
    $PasswordExists = Test-Path -LiteralPath $PasswordFile
    if ($KeyExists -ne $PasswordExists) { throw 'The saved Android key/password pair is incomplete. Restore both from backup rather than replacing the signing key.' }
    $env:MIRRORSHARE_KEYSTORE_PATH = $KeyStore
    $env:MIRRORSHARE_KEY_ALIAS = 'mirrorshare'
    if ($KeyExists) {
        $Secure = Get-Content -LiteralPath $PasswordFile -Raw | ConvertTo-SecureString
        $env:MIRRORSHARE_KEYSTORE_PASSWORD = ([System.Net.NetworkCredential]::new('', $Secure)).Password
        return
    }
    $Random = New-Object byte[] 32
    $Generator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $Generator.GetBytes($Random) } finally { $Generator.Dispose() }
    $env:MIRRORSHARE_KEYSTORE_PASSWORD = [Convert]::ToBase64String($Random)
    $Secure = ConvertTo-SecureString $env:MIRRORSHARE_KEYSTORE_PASSWORD -AsPlainText -Force
    $TemporaryKey = Join-Path $SigningDirectory ('new-key-' + [Guid]::NewGuid().ToString('N') + '.jks')
    try {
        Invoke-BuildTool (Join-Path $env:JAVA_HOME 'bin\keytool.exe') @(
            '-genkeypair','-noprompt','-keystore',$TemporaryKey,'-storetype','JKS',
            '-alias','mirrorshare','-keyalg','RSA','-keysize','3072','-validity','10000',
            '-dname','CN=Mirror Share, OU=Development, O=Mirror Share',
            '-storepass:env','MIRRORSHARE_KEYSTORE_PASSWORD','-keypass:env','MIRRORSHARE_KEYSTORE_PASSWORD')
        $Secure | ConvertFrom-SecureString | Set-Content -LiteralPath $PasswordFile -Encoding ASCII
        Move-Item -LiteralPath $TemporaryKey -Destination $KeyStore
    } finally {
        if (Test-Path -LiteralPath $TemporaryKey) { Remove-Item -LiteralPath $TemporaryKey }
    }
    Write-Host 'Created the persistent local Mirror Share Android release key.' -ForegroundColor Green
}

try {
    if ([Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT) { throw 'Run this script on Windows to build the sender and MSI.' }
    New-Item -ItemType Directory -Path $Artifacts -Force | Out-Null
    Start-Transcript -Path (Join-Path $Artifacts 'Build.log') -Force | Out-Null
    $TranscriptStarted = $true
    if ($Target -eq 'All' -or $Target -eq 'Windows') {
        if (-not (Get-Command dotnet -ErrorAction SilentlyContinue)) { throw 'Install the .NET 10 SDK, reopen this command window, and rerun.' }
        $SdkList = (& dotnet --list-sdks | Out-String)
        if ($LASTEXITCODE -ne 0 -or $SdkList -notmatch '(?m)^10\.') { throw 'The .NET 10 SDK is required for the supplied sender source.' }
        $WindowsRoot = Join-Path $ProjectRoot 'WindowsSender'
        if (-not (Test-Path -LiteralPath (Join-Path $WindowsRoot 'ffmpeg.exe'))) { throw 'Restore the supplied ffmpeg.exe into WindowsSender before building.' }
        $Publish = Join-Path $WindowsRoot 'publish\win-x64'
        if (Test-Path -LiteralPath $Publish) { Remove-Item -LiteralPath $Publish -Recurse -Force }
        foreach ($Name in @('MirrorShare-Setup.msi','MirrorShare-Windows-Portable.zip')) {
            $Old = Join-Path $Artifacts $Name
            if (Test-Path -LiteralPath $Old) { Remove-Item -LiteralPath $Old }
        }
        Write-Host 'Building Mirror Share Windows sender...' -ForegroundColor Cyan
        Invoke-BuildTool 'dotnet' @('publish',(Join-Path $WindowsRoot 'MirrorShare.csproj'),'-c','Release',
            '-r','win-x64','--self-contained','true','-o',$Publish,
            '-p:PublishSingleFile=false','-p:DebugType=None','-p:DebugSymbols=false')
        foreach ($RequiredFile in @('MirrorShare.exe','ffmpeg.exe')) {
            if (-not (Test-Path -LiteralPath (Join-Path $Publish $RequiredFile))) { throw "Missing published file: $RequiredFile" }
        }
        Write-Host 'Building Mirror Share MSI...' -ForegroundColor Cyan
        Invoke-BuildTool 'dotnet' @('build',(Join-Path $ProjectRoot 'Packaging\MirrorShare.wixproj'),
            '-c','Release',('-p:PublishDir=' + $Publish))
        $Msi = Join-Path $Artifacts 'MirrorShare-Setup.msi'
        if (-not (Test-Path -LiteralPath $Msi)) {
            $Found = @(Get-ChildItem -LiteralPath $Artifacts -Filter 'MirrorShare-Setup.msi' -Recurse)
            if ($Found.Count -eq 0) { throw 'WiX did not produce MirrorShare-Setup.msi.' }
            Copy-Item -LiteralPath $Found[0].FullName -Destination $Msi
        }
        Compress-Archive -Path (Join-Path $Publish '*') -DestinationPath (Join-Path $Artifacts 'MirrorShare-Windows-Portable.zip') -Force
    }
    if ($Target -eq 'All' -or $Target -eq 'Android') {
        Initialize-AndroidTools
        Initialize-ReleaseSigning
        $ApkDestination = Join-Path $Artifacts 'MirrorShare-TV.apk'
        if (Test-Path -LiteralPath $ApkDestination) { Remove-Item -LiteralPath $ApkDestination }
        Write-Host 'Building signed Mirror Share TV APK...' -ForegroundColor Cyan
        $AndroidRoot = Join-Path $ProjectRoot 'AndroidTVReceiver'
        Push-Location $AndroidRoot
        try { Invoke-BuildTool '.\gradlew.bat' @('--no-daemon',':app:assembleRelease') } finally { Pop-Location }
        $BuiltApk = Join-Path $AndroidRoot 'app\build\outputs\apk\release\app-release.apk'
        if (-not (Test-Path -LiteralPath $BuiltApk)) { throw 'A signed release APK was not produced.' }
        Invoke-BuildTool (Join-Path $script:AndroidSdkPath 'build-tools\35.0.0\apksigner.bat') @('verify','--verbose',$BuiltApk)
        Copy-Item -LiteralPath $BuiltApk -Destination $ApkDestination
    }
    $Files = @(Get-ChildItem -LiteralPath $Artifacts -File | Where-Object {
        $_.Name -in @('MirrorShare-Setup.msi','MirrorShare-TV.apk','MirrorShare-Windows-Portable.zip')
    })
    $Lines = @($Files | Sort-Object Name | ForEach-Object {
        (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant() + '  ' + $_.Name
    })
    $Lines | Set-Content -LiteralPath (Join-Path $Artifacts 'SHA256SUMS.txt') -Encoding ASCII
    Write-Host ('BUILD COMPLETED: ' + $Artifacts) -ForegroundColor Green
    Write-Host 'Install and test these builds before replacing the existing release.' -ForegroundColor Yellow
} catch {
    Write-Host ('BUILD STOPPED: ' + $_.Exception.Message) -ForegroundColor Red
    exit 1
} finally {
    foreach ($Name in $OriginalEnvironment.Keys) { [Environment]::SetEnvironmentVariable($Name,$OriginalEnvironment[$Name],'Process') }
    if ($TranscriptStarted) { Stop-Transcript | Out-Null }
}
