param([switch]$Install, [string]$Device='192.168.1.100:5555')
$ErrorActionPreference='Stop'
Set-Location $PSScriptRoot
$sdk=Join-Path $env:LOCALAPPDATA 'Android/Sdk'
$bt=Join-Path $sdk 'build-tools/35.0.0'
$android=Join-Path $sdk 'platforms/android-35/android.jar'
function Run([string]$exe,[string[]]$Arguments) {
    & $exe @Arguments
    if($LASTEXITCODE -ne 0){ throw "$exe failed ($LASTEXITCODE)" }
}
$aar='deps/libvlc-all-3.6.5.aar'
if(!(Test-Path $aar)){throw 'Missing LibVLC AAR. See README for the official Maven URL.'}
$expected='6A15A1F7ACD0738A31ACCE0C7E5EB6F5F340B62E6B64D8F83DF547DA56A44B47'
if((Get-FileHash $aar -Algorithm SHA256).Hash -ne $expected){throw 'LibVLC dependency checksum does not match the reviewed build.'}
if((Get-FileHash 'deps/jsoup-1.13.1.jar' -Algorithm SHA256).Hash -ne 'E2B99C0D2FA39F69F27EFB1C0016390713FEB2F2E02D8EA7F1C36B780271598A'){throw 'jsoup checksum mismatch'}
if((Get-FileHash 'assets/trusted-roots.pem' -Algorithm SHA256).Hash -ne '0E99DB0DD2EAEBC57711CF214980196E1F1E2A569BFA4EA7BAE26A5FDC439DBF'){throw 'CA root bundle checksum mismatch'}
Add-Type -AssemblyName System.IO.Compression.FileSystem
if(!(Test-Path 'deps/libvlc/classes.jar')) {
    [IO.Compression.ZipFile]::ExtractToDirectory((Resolve-Path $aar).Path,(Join-Path $PSScriptRoot 'deps/libvlc'))
}
$classesDir=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot 'build/classes'))
$buildRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot 'build'))
if(!$classesDir.StartsWith($buildRoot+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Unexpected classes output directory'}
if(Test-Path -LiteralPath $classesDir){
    if((Get-Item -LiteralPath $classesDir).Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'Refusing to clean a linked classes output directory'}
    Remove-Item -LiteralPath $classesDir -Recurse -Force
}
New-Item -ItemType Directory -Force -Path build/classes,build/generated,build/dex,build/package-armv7/lib,dist | Out-Null
Run "$bt/aapt.exe" @('package','-f','--auto-add-overlay','-M','AndroidManifest.xml','-S','res','-S','deps/libvlc/res','-A','assets','-I',$android,'-J','build/generated','--extra-packages','org.videolan','-F','build/unsigned.apk')
$sources=@(Get-ChildItem src,build/generated -Filter '*.java' -Recurse | ForEach-Object FullName)
Run javac (@('-encoding','UTF-8','-source','8','-target','8','-bootclasspath',$android,'-classpath','deps/libvlc/classes.jar;deps/jsoup-1.13.1.jar','-d','build/classes')+$sources)
Run jar @('cf','build/app-classes.jar','-C','build/classes','.')
Run "$bt/d8.bat" @('--min-api','21','--lib',$android,'--output','build/dex','deps/libvlc/classes.jar','deps/jsoup-1.13.1.jar','build/app-classes.jar')
Copy-Item -LiteralPath build/dex/classes.dex -Destination build/package-armv7/classes.dex -Force
foreach($abi in @('armeabi-v7a')) {
    Copy-Item -LiteralPath "deps/libvlc/jni/$abi" -Destination build/package-armv7/lib -Recurse -Force
}
Push-Location build/package-armv7
try {
    $files=@(Get-ChildItem -File -Recurse | ForEach-Object { [IO.Path]::GetRelativePath((Get-Location).Path,$_.FullName).Replace('\','/') })
    Run "$bt/aapt.exe" (@('add','../unsigned.apk')+$files)
} finally {Pop-Location}
Run "$bt/zipalign.exe" @('-f','4','build/unsigned.apk','build/aligned-armv7.apk')
if(!(Test-Path anime-local.jks)) {
    Run keytool @('-genkeypair','-keystore','anime-local.jks','-storepass','simplelive-local','-keypass','simplelive-local','-alias','simplelive','-keyalg','RSA','-keysize','2048','-validity','10000','-dname','CN=KanDongman Local Build')
}
Run "$bt/apksigner.bat" @('sign','--ks','anime-local.jks','--ks-pass','pass:simplelive-local','--key-pass','pass:simplelive-local','--min-sdk-version','21','--v1-signing-enabled','true','--out','dist/KanDongman-1.4.0.apk','build/aligned-armv7.apk')
Run "$bt/apksigner.bat" @('verify','--verbose','dist/KanDongman-1.4.0.apk')
Run "$bt/aapt.exe" @('dump','badging','dist/KanDongman-1.4.0.apk')
if($Install) {
    Run adb @('-s',$Device,'install','-r','dist/KanDongman-1.4.0.apk')
    Run adb @('-s',$Device,'shell','am','start','-n','com.qianq.kandongman/.MainActivity')
}
Write-Output 'Built dist/KanDongman-1.4.0.apk'
