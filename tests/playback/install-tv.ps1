param([Parameter(Mandatory=$true)][string]$Apk)
$ErrorActionPreference='Stop'
$adb=Join-Path $env:LOCALAPPDATA 'Android/Sdk/platform-tools/adb.exe'
$target='DEVICE_IP:PORT'
$taskLog=Join-Path $env:TEMP ('tv-install-'+[Guid]::NewGuid().ToString('N'))
$process=Start-Process -FilePath $adb -ArgumentList @('-s',$target,'install','-r',('"'+(Resolve-Path $Apk).Path+'"')) -WindowStyle Hidden -PassThru -RedirectStandardOutput "$taskLog.out" -RedirectStandardError "$taskLog.err"
for($attempt=0;$attempt -lt 15 -and !$process.HasExited;$attempt++){
    & $adb -s $target shell uiautomator dump /data/local/tmp/install-ui.xml | Out-Null
    $raw=(& $adb -s $target shell cat /data/local/tmp/install-ui.xml) -join "`n"
    try{[xml]$xml=$raw; foreach($node in $xml.SelectNodes('//node')){
        if($node.package -eq 'com.android.packageinstaller' -and $node.text -eq '继续安装' -and $node.enabled -eq 'true' -and $node.bounds -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]'){
            & $adb -s $target shell input tap ([int](($Matches[1]-as[int])+($Matches[3]-as[int]))/2) ([int](($Matches[2]-as[int])+($Matches[4]-as[int]))/2)
        }
    }}catch{}
    $process.Refresh()
}
if(!$process.WaitForExit(10000)){throw 'Installer did not finish'}
$output=Get-Content "$taskLog.out" -Raw
if($process.ExitCode -ne 0 -or $output -notmatch 'Success'){throw ($output+(Get-Content "$taskLog.err" -Raw))}
& $adb -s $target shell am force-stop com.android.packageinstaller
& $adb -s $target shell input keyevent 19
Write-Output $output
