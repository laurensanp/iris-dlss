@echo off
rem Builds irisdlss.dll and the probe with MSVC (Visual Studio 2022).
rem The project lives in a deep folder; MSVC cannot open headers past MAX_PATH, so the build runs
rem on a temporary drive letter (subst) that is removed again afterwards.
setlocal
for /f "usebackq delims=" %%I in (`"%ProgramFiles(x86)%\Microsoft Visual Studio\Installer\vswhere.exe" -latest -property installationPath`) do set VSDIR=%%I
call "%VSDIR%\VC\Auxiliary\Build\vcvars64.bat" >nul 2>nul
where cl >nul 2>nul || (echo MSVC not found & exit /b 1)

set DRIVE=
for %%D in (W V U T S R Q P) do if not defined DRIVE if not exist %%D:\ set DRIVE=%%D:
if not defined DRIVE (echo no free drive letter & exit /b 1)
subst %DRIVE% "%~dp0."
pushd %DRIVE%\
call :build
set RESULT=%ERRORLEVEL%
popd
subst %DRIVE% /d
exit /b %RESULT%

:build
if not exist out mkdir out
set JDK=%JAVA_HOME%
if not defined JDK (echo set JAVA_HOME to a JDK 25 & exit /b 1)
if not exist third_party\ngx\include (echo run fetch-deps.ps1 first & exit /b 1)
set INC=/I third_party\ngx\include /I third_party\vulkan-headers\include /I "%JDK%\include" /I "%JDK%\include\win32"
cl /nologo /std:c++17 /O2 /MD /EHsc /W3 /DUNICODE %INC% /LD src\bridge.cpp src\jni.cpp /Fo:out\ /Fe:out\irisdlss.dll ^
   /link third_party\ngx\lib\nvsdk_ngx_d.lib user32.lib advapi32.lib || exit /b 1
cl /nologo /std:c++17 /O2 /MD /EHsc /W3 /DUNICODE src\probe.cpp /Fo:out\ /Fe:out\dlss_probe.exe ^
   /link out\irisdlss.lib user32.lib || exit /b 1
copy /y third_party\ngx\bin\nvngx_dlss.dll out\ >nul
copy /y third_party\ngx\bin\nvngx_dlssg.dll out\ >nul
echo BUILD OK
exit /b 0
