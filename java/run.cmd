@echo off
rem Runs the Java version. Build it first with build.cmd.
rem The working directory is set to the repository root, so both the Python and
rem the Java version share the same library.db file.
setlocal
set "HERE=%~dp0"
set "VERSION=3.42.0.0"
set "JAR=%HERE%lib\sqlite-jdbc-%VERSION%.jar"

if not exist "%HERE%out\library\App.class" (
    echo [run] not built yet - building now...
    call "%HERE%build.cmd" || exit /b 1
)

pushd "%HERE%.."
java -cp "%HERE%out;%JAR%" library.App %*
set "CODE=%ERRORLEVEL%"
popd
exit /b %CODE%
