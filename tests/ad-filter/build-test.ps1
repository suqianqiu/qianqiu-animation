$ErrorActionPreference='Stop'
Set-Location $PSScriptRoot
$app=(Resolve-Path ../..).Path
$sdk=Join-Path $env:LOCALAPPDATA 'Android/Sdk'
$bt=Join-Path $sdk 'build-tools/35.0.0'
$android=Join-Path $sdk 'platforms/android-35/android.jar'
function Run($exe,$arguments){& $exe @arguments;if($LASTEXITCODE -ne 0){throw "$exe failed"}}
New-Item -ItemType Directory -Force classes,dex | Out-Null
Run javac @('-encoding','UTF-8','--release','8','-classpath',"$android;$app/build/app-classes.jar;$app/deps/libvlc/classes.jar",'-d','classes','AdIntegrationTest.java')
Run jar @('cf','tests.jar','-C','classes','.')
Run "$bt/d8.bat" @('--min-api','21','--lib',$android,'--classpath',"$app/build/app-classes.jar",'--output','dex','tests.jar')
Run "$bt/aapt.exe" @('package','-f','-M','AndroidManifest.xml','-A','fixture','-I',$android,'-F','unsigned.apk')
Copy-Item dex/classes.dex classes.dex -Force
Run "$bt/aapt.exe" @('add','unsigned.apk','classes.dex')
Run "$bt/zipalign.exe" @('-f','4','unsigned.apk','aligned.apk')
Run "$bt/apksigner.bat" @('sign','--ks',"$app/anime-local.jks",'--ks-pass','pass:simplelive-local','--key-pass','pass:simplelive-local','--out','ad-test.apk','aligned.apk')
