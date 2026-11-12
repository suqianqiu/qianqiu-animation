param([string]$InputDirectory=$PSScriptRoot,[string]$Pattern='*.jpg',[string]$OutputFile='ocr.json',[int]$Limit=0)
$ErrorActionPreference='Stop'
Add-Type -AssemblyName System.Runtime.WindowsRuntime
[void][Windows.Storage.StorageFile,Windows.Storage,ContentType=WindowsRuntime]
[void][Windows.Storage.Streams.IRandomAccessStream,Windows.Storage.Streams,ContentType=WindowsRuntime]
[void][Windows.Graphics.Imaging.BitmapDecoder,Windows.Graphics.Imaging,ContentType=WindowsRuntime]
[void][Windows.Graphics.Imaging.SoftwareBitmap,Windows.Graphics.Imaging,ContentType=WindowsRuntime]
[void][Windows.Media.Ocr.OcrEngine,Windows.Foundation,ContentType=WindowsRuntime]
[void][Windows.Media.Ocr.OcrResult,Windows.Foundation,ContentType=WindowsRuntime]
$script:asTask=[System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object { $_.Name -eq 'AsTask' -and $_.IsGenericMethod -and $_.GetGenericArguments().Length -eq 1 -and $_.GetParameters().Length -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' } | Select-Object -First 1
function Await($operation,[Type]$type){$task=$script:asTask.MakeGenericMethod($type).Invoke($null,@($operation));$task.Wait();return $task.Result}
$engine=[Windows.Media.Ocr.OcrEngine]::TryCreateFromUserProfileLanguages()
if($null -eq $engine){throw 'No installed local OCR language'}
$outputPath=Join-Path $InputDirectory $OutputFile
$rows=New-Object System.Collections.Generic.List[Object]
$done=@{}
if(Test-Path -LiteralPath $outputPath){foreach($row in (Get-Content -LiteralPath $outputPath -Raw -Encoding UTF8 | ConvertFrom-Json)){if(!$row.error){$done[$row.file]=$row;$rows.Add($row)}}}
$files=@(Get-ChildItem -LiteralPath $InputDirectory -Filter $Pattern -File | Sort-Object Name)
if($Limit -gt 0){$files=@($files | Select-Object -First $Limit)}
foreach($path in $files){
    if($done.ContainsKey($path.Name)){continue}
    $stream=$null;$bitmap=$null
    try{
        $file=Await ([Windows.Storage.StorageFile]::GetFileFromPathAsync($path.FullName)) ([Windows.Storage.StorageFile])
        $stream=Await ($file.OpenAsync([Windows.Storage.FileAccessMode]::Read)) ([Windows.Storage.Streams.IRandomAccessStream])
        $decoder=Await ([Windows.Graphics.Imaging.BitmapDecoder]::CreateAsync($stream)) ([Windows.Graphics.Imaging.BitmapDecoder])
        $bitmap=Await ($decoder.GetSoftwareBitmapAsync([Windows.Graphics.Imaging.BitmapPixelFormat]::Bgra8,[Windows.Graphics.Imaging.BitmapAlphaMode]::Premultiplied)) ([Windows.Graphics.Imaging.SoftwareBitmap])
        $result=Await ($engine.RecognizeAsync($bitmap)) ([Windows.Media.Ocr.OcrResult])
        $rows.Add([pscustomobject]@{file=$path.Name;text=$result.Text;language=$engine.RecognizerLanguage.LanguageTag})
    }catch{$rows.Add([pscustomobject]@{file=$path.Name;error=$_.Exception.Message})}
    finally{if($null -ne $bitmap){$bitmap.Dispose()};if($null -ne $stream){$stream.Dispose()}}
    if($rows.Count%20 -eq 0){Write-Output ('OCR '+$rows.Count+'/'+$files.Count)}
    [IO.File]::WriteAllText($outputPath,($rows | ConvertTo-Json -Depth 5),[Text.UTF8Encoding]::new($false))
}
Write-Output ('OCR complete '+$rows.Count+' images')
