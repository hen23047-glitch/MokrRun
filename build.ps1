# 随心跑 (MokrRun) build script
# aapt2 -> javac -> d8 -> assemble -> zipalign -> apksigner
# versionCode auto increments (stored in version.txt)
$ErrorActionPreference = "Continue"

$root   = "C:\MokrRun"
$tools  = "C:\mokrtools"
$app    = "$root\app"
$out    = "$root\build"
$res    = "$app\src\main\res"
$javad  = "$app\src\main\java"
$assets = "$app\src\main\assets"
$manifest = "$app\AndroidManifest.xml"
$verFile = "$root\version.txt"

if (Test-Path $verFile) {
    $prev = [int]((Get-Content $verFile -Raw).Trim())
} else {
    $prev = 0
}
$verCode = $prev + 1
$verName = "1.0.$verCode"
$stamp   = (Get-Date).ToString("yyyyMMdd-HHmm")
Set-Content -Path $verFile -Value $verCode -Encoding ascii
Write-Host ("version: $verName (code=$verCode, prev=$prev)") -ForegroundColor Yellow

$bctPath = "$javad\com\dsh\mokr\BuildConfigTag.java"
$bct = "package com.dsh.mokr;`r`n" +
       "public final class BuildConfigTag {`r`n" +
       "    public static final String VERSION = `"$verName`";`r`n" +
       "    public static final int CODE = $verCode;`r`n" +
       "    public static final String BUILD = `"$stamp`";`r`n" +
       "    private BuildConfigTag() {}`r`n" +
       "}`r`n"
[System.IO.File]::WriteAllText($bctPath, $bct, (New-Object System.Text.ASCIIEncoding))

$jdkBin  = (Get-ChildItem "$tools\jdk" -Filter javac.exe -Recurse | Select-Object -First 1).DirectoryName
$javaExe = "$jdkBin\java.exe"
$javac   = "$jdkBin\javac.exe"
$bt      = "$tools\bt\android-14"
$aapt2   = "$bt\aapt2.exe"
$zipalign= "$bt\zipalign.exe"
$apksignerJar = "$bt\lib\apksigner.jar"
$d8jar   = "C:\mokrtools\r8\r8.jar"
$androidJar = "$tools\android.jar"
$keystore = "$root\dsh.jks"

function Step($n, $msg) { Write-Host ("== [{0}/7] {1} ==" -f $n, $msg) -ForegroundColor Cyan }

Step 1 "clean"
if (Test-Path $out) { Remove-Item $out -Recurse -Force }
New-Item -ItemType Directory -Force -Path "$out\gen", "$out\classes", "$out\dex" | Out-Null

Step 2 "aapt2 compile"
& $aapt2 compile --dir $res -o "$out\res.zip" 2>&1 | ForEach-Object { Write-Host $_ }
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed ($LASTEXITCODE)" }

# inject version into the manifest: aapt2 --version-* is overridden by manifest attrs
$m = [System.IO.File]::ReadAllText($manifest)
$m = $m -replace 'android:versionCode="\d+"', ('android:versionCode="' + $verCode + '"')
$m = $m -replace 'android:versionName="[^"]*"', ('android:versionName="' + $verName + '"')
[System.IO.File]::WriteAllText($manifest, $m, (New-Object System.Text.UTF8Encoding($false)))

Step 3 "aapt2 link"
& $aapt2 link -o "$out\base.apk" -I $androidJar --manifest $manifest --java "$out\gen" --min-sdk-version 26 --target-sdk-version 36 --version-code $verCode --version-name $verName --auto-add-overlay "$out\res.zip" 2>&1 | ForEach-Object { Write-Host $_ }
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed ($LASTEXITCODE)" }

Step 4 "javac"
$srcs = @()
$srcs += Get-ChildItem $javad -Recurse -Filter *.java | ForEach-Object { $_.FullName }
$srcs += Get-ChildItem "$out\gen" -Recurse -Filter *.java | ForEach-Object { $_.FullName }
& $javac --release 8 -g -nowarn -classpath $androidJar -d "$out\classes" $srcs 2>&1 | ForEach-Object { Write-Host $_ }
if ($LASTEXITCODE -ne 0) { throw "javac failed ($LASTEXITCODE)" }

Step 5 "d8"
$classes = Get-ChildItem "$out\classes\com" -Recurse -Filter *.class | ForEach-Object { $_.FullName }
& $javaExe -cp $d8jar com.android.tools.r8.D8 --release --min-api 26 --lib $androidJar --output "$out\dex" $classes 2>&1 | ForEach-Object { Write-Host $_ }
if ($LASTEXITCODE -ne 0) { throw "d8 failed ($LASTEXITCODE)" }

Step 6 "assemble + align + sign"
$assemble = @'
import zipfile, os
base = r"C:\MokrRun\build\base.apk"
dex  = r"C:\MokrRun\build\dex\classes.dex"
assets = r"C:\MokrRun\app\src\main\assets"
outf = r"C:\MokrRun\build\unsigned.apk"
zin = zipfile.ZipFile(base)
zout = zipfile.ZipFile(outf, "w", zipfile.ZIP_DEFLATED)
for it in zin.infolist():
    data = zin.read(it.filename)
    zi = zipfile.ZipInfo(it.filename, date_time=it.date_time)
    zi.compress_type = it.compress_type
    zi.external_attr = it.external_attr
    zout.writestr(zi, data)
zi = zipfile.ZipInfo("classes.dex", date_time=(2024,1,1,0,0,0))
zi.compress_type = zipfile.ZIP_DEFLATED
zout.writestr(zi, open(dex,"rb").read())
for name in sorted(os.listdir(assets)):
    p = os.path.join(assets, name)
    if os.path.isfile(p):
        zi = zipfile.ZipInfo("assets/" + name, date_time=(2024,1,1,0,0,0))
        zi.compress_type = zipfile.ZIP_DEFLATED
        zout.writestr(zi, open(p,"rb").read())
zout.close()
print("assembled", os.path.getsize(outf))
'@
$assemble | Out-File -FilePath "$out\assemble.py" -Encoding ascii
python "$out\assemble.py" 2>&1 | ForEach-Object { Write-Host $_ }

& $zipalign -p -f 4 "$out\unsigned.apk" "$out\aligned.apk" 2>&1 | ForEach-Object { Write-Host $_ }
if ($LASTEXITCODE -ne 0) { throw "zipalign failed ($LASTEXITCODE)" }

& $javaExe -jar $apksignerJar sign --ks $keystore --ks-pass pass:dshpass --key-pass pass:dshpass --out "$out\MokrRun.apk" "$out\aligned.apk" 2>&1 | ForEach-Object { Write-Host $_ }
if ($LASTEXITCODE -ne 0) { throw "apksigner failed ($LASTEXITCODE)" }

& $javaExe -jar $apksignerJar verify "$out\MokrRun.apk" 2>&1 | ForEach-Object { Write-Host $_ }

Step 7 "done"
Copy-Item "$out\MokrRun.apk" "$root\MokrRun-$verName-$stamp.apk" -Force
Write-Host ("built MokrRun-$verName-$stamp.apk") -ForegroundColor Green
Get-Item "$out\MokrRun.apk" | Select-Object FullName, Length
