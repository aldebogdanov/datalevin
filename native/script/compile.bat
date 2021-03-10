
if "%GRAALVM_HOME%"=="" (
    echo Please set GRAALVM_HOME
    exit /b
)

set JAVA_HOME=%GRAALVM_HOME%\bin
set PATH=%GRAALVM_HOME%\bin;%PATH%

call %GRAALVM_HOME%\bin\gu.cmd install native-image

set PWD=%cd%
set CPATH=%PWD%\src\c

call ..\lein.bat do clean, uberjar
if %errorlevel% neq 0 exit /b %errorlevel%

cd %CPATH%

mkdir build
cd build

cmake .. ^
    -G "NMake Makefiles" ^
    -DCMAKE_BUILD_TYPE:STRING=RELEASE ^
    -DCMAKE_INSTALL_PREFIX=%CPATH% ^
    -DCLOSE_WARNING=on ^
    -DBUILD_TEST=off ^
    -DBUILD_SHARED_LIBS=off
nmake install

call %GRAALVM_HOME%\bin\native-image.cmd ^
  "-jar" "target/main.uberjar.jar" ^
  "-H:Name=dtlv" ^
  "-H:+ReportExceptionStackTraces" ^
  "-H:ConfigurationFileDirectories=config" ^
  "-J-Dclojure.spec.skip-macros=true" ^
  "-J-Dclojure.compiler.direct-linking=true" ^
  "-H:CLibraryPath=%CPATH%" ^
  "-H:NativeLinkerOption=legacy_stdio_definitions.lib" ^
  "--initialize-at-build-time"  ^
  "-H:Log=registerResource:" ^
  "--report-unsupported-elements-at-runtime" ^
  "--allow-incomplete-classpath" ^
  "--no-fallback" ^
  "--native-image-info" ^
  "--verbose" ^
  "-J-Xmx6g" ^
  dtlv

if %errorlevel% neq 0 exit /b %errorlevel%

echo Creating zip archive
jar -cMf dtlv-windows-amd64.zip dtlv.exe
