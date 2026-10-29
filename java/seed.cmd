@echo off
rem Fills library.db with random demo data (3 employees, 20 students, 20 books, 20 loans).
rem WARNING: it deletes the existing library.db first.
setlocal
set "HERE=%~dp0"
set "VERSION=3.42.0.0"
set "JAR=%HERE%lib\sqlite-jdbc-%VERSION%.jar"

if not exist "%HERE%out\library\SeedData.class" (
    call "%HERE%build.cmd" || exit /b 1
)

pushd "%HERE%.."
java -cp "%HERE%out;%JAR%" library.SeedData %*
set "CODE=%ERRORLEVEL%"
popd
exit /b %CODE%
