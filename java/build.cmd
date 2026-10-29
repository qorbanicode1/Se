@echo off
rem Compiles the Java version of the library system into java\out.
rem Requires a JDK (8 or newer) on PATH. Fetches sqlite-jdbc once if it is missing.
setlocal
set "HERE=%~dp0"
set "LIB=%HERE%lib"
set "OUT=%HERE%out"
set "VERSION=3.42.0.0"
set "JAR=%LIB%\sqlite-jdbc-%VERSION%.jar"
set "URL=https://repo1.maven.org/maven2/org/xerial/sqlite-jdbc/%VERSION%/sqlite-jdbc-%VERSION%.jar"

where javac >nul 2>nul
if errorlevel 1 (
    echo [build] javac was not found on PATH. Install a JDK ^(8 or newer^) and try again.
    exit /b 1
)

if exist "%JAR%" goto :have_jar

echo [build] sqlite-jdbc %VERSION% not found, downloading...
if not exist "%LIB%" mkdir "%LIB%"
powershell -NoProfile -ExecutionPolicy Bypass -Command "$ProgressPreference='SilentlyContinue'; try { Invoke-WebRequest -UseBasicParsing -Uri '%URL%' -OutFile '%JAR%' } catch { Write-Host $_.Exception.Message; exit 1 }"
if exist "%JAR%" goto :have_jar

echo [build] PowerShell download did not work, trying python...
python -c "import urllib.request,sys; urllib.request.urlretrieve(sys.argv[1], sys.argv[2])" "%URL%" "%JAR%" 2>nul
if exist "%JAR%" goto :have_jar

echo [build] download failed.
echo         Download the file manually and put it here: %JAR%
echo         %URL%
exit /b 1

:have_jar
if exist "%OUT%" rd /s /q "%OUT%"
mkdir "%OUT%"

set "LIST=%TEMP%\library-java-sources.txt"
dir /b /s "%HERE%src\*.java" > "%LIST%"
javac -encoding UTF-8 -d "%OUT%" -cp "%JAR%" @"%LIST%"
if errorlevel 1 (
    echo [build] compilation failed.
    exit /b 1
)

echo [build] OK - classes in %OUT%
