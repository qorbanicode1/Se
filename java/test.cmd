@echo off
rem Runs the end-to-end smoke test on a temporary database.
setlocal
set "HERE=%~dp0"
set "VERSION=3.42.0.0"
set "JAR=%HERE%lib\sqlite-jdbc-%VERSION%.jar"

if not exist "%HERE%out\library\SmokeTest.class" (
    call "%HERE%build.cmd" || exit /b 1
)

pushd "%HERE%.."
java -cp "%HERE%out;%JAR%" library.SmokeTest %*
set "CODE=%ERRORLEVEL%"
popd
exit /b %CODE%
