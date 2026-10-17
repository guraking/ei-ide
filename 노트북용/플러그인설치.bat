@echo off
chcp 949 >nul
rem EI IDE 를 새로 설치하거나 판을 올린 뒤 한 번 실행한다.
rem 본체에 들어 있지 않은 플러그인(한국어 언어 팩 · Claude Code · One Dark 테마)을 사용자 플러그인 폴더에 넣고,
rem 예전에 사용자 폴더에 따로 넣었던 플러그인(서버 창 · Synchronize · 롬복 · 화면 묶음)은 본체와 겹치지 않게 치운다.
rem 옆의 plugins 폴더에 없는 것은 내려받는다: 한국어 팩은 GitHub 릴리스 「plugins」, Claude Code 는 JetBrains 마켓.
rem 옆의 jdk · gradle · drivers 폴더에 있는 것은 사용자 폴더에 풀어 둔다(인터넷 없이). 이미 있으면 건너뛴다.
rem   jdk\*.zip      → %USERPROFILE%\.jdks\<압축 속 폴더>  (EI IDE 가 켤 때 SDK 목록에 자동 등록)
rem   gradle\*.zip   → Gradle wrapper 가 같은 판을 찾는 자리 (%USERPROFILE%\.gradle\wrapper\dists)
rem   drivers\*.jar  → %USERPROFILE%\.m2\repository (데이터베이스 창이 여기서 드라이버를 찾는다)
setlocal
set "DEST=%APPDATA%\JetBrains\EIIDE2026.2\plugins"
set "SRC=%~dp0plugins"
set "KO_ZIP=https://github.com/guraking/ei-ide/releases/download/plugins/localization-ko-262.10968.63.zip"
set "CC_ZIP=https://plugins.jetbrains.com/pluginManager?action=download&id=com.anthropic.code.plugin&build=IC-262.10968.63"
rem Gradle wrapper 의 배포판 폴더 이름은 distributionUrl 의 MD5 를 36진수로 쓴 값이다. 판을 바꾸면 함께 바꾼다.
set "GRADLE_VER=8.14.6"
set "GRADLE_HASH=aynuqaj6iwevsbr5c5qdwq08s"
set "OD_ZIP=https://plugins.jetbrains.com/pluginManager?action=download&id=com.markskelton.one-dark-theme&build=IC-262.10968.63"

tasklist /fi "imagename eq idea64.exe" | find /i "idea64.exe" >nul && (
  echo EI IDE 를 먼저 종료한 뒤 다시 실행하세요.
  pause
  exit /b 1
)
if not exist "%SRC%" mkdir "%SRC%"

if not exist "%SRC%\localization-ko" (
  echo 한국어 언어 팩을 내려받습니다...
  curl -L -o "%TEMP%\ko.zip" "%KO_ZIP%" && tar -xf "%TEMP%\ko.zip" -C "%SRC%" || (
    echo 한국어 언어 팩을 받지 못했습니다. 인터넷 연결을 확인하세요.
    pause
    exit /b 1
  )
)
if not exist "%SRC%\claude-code-jetbrains-plugin" (
  echo Claude Code 플러그인을 내려받습니다...
  curl -L -o "%TEMP%\cc.zip" "%CC_ZIP%" && tar -xf "%TEMP%\cc.zip" -C "%SRC%" || (
    echo Claude Code 플러그인을 받지 못했습니다. 인터넷 연결을 확인하세요.
    pause
    exit /b 1
  )
)

if not exist "%SRC%\one-dark-theme" (
  echo One Dark 테마를 내려받습니다...
  curl -L -o "%TEMP%\od.zip" "%OD_ZIP%" && tar -xf "%TEMP%\od.zip" -C "%SRC%" || echo One Dark 테마는 받지 못했습니다. 건너뜁니다.
)

if not exist "%DEST%" mkdir "%DEST%"
for %%f in ("%DEST%\boot-servers*.jar" "%DEST%\kinggura-sync*.jar" "%DEST%\kinggura-perspectives*.jar" "%DEST%\ei-db*.jar") do if exist %%f (del /q %%f && echo 치움: %%~nxf)
if exist "%DEST%\lombok" (rmdir /s /q "%DEST%\lombok" && echo 치움: lombok)
xcopy /E /I /Y "%SRC%" "%DEST%" >nul || (
  echo 복사에 실패했습니다: %DEST%
  pause
  exit /b 1
)
echo 플러그인 설치 완료: %DEST%

set "JDKS=%USERPROFILE%\.jdks"
if not exist "%JDKS%" mkdir "%JDKS%"
for %%z in ("%~dp0jdk\*.zip") do call :jdk "%%~fz"

set "GW=%USERPROFILE%\.gradle\wrapper\dists\gradle-%GRADLE_VER%-bin\%GRADLE_HASH%"
if exist "%~dp0gradle\gradle-%GRADLE_VER%-bin.zip" (
  if exist "%GW%\gradle-%GRADLE_VER%-bin.zip.ok" (
    echo Gradle %GRADLE_VER% 있음
  ) else (
    mkdir "%GW%" 2>nul
    copy /y "%~dp0gradle\gradle-%GRADLE_VER%-bin.zip" "%GW%\" >nul && tar -xf "%GW%\gradle-%GRADLE_VER%-bin.zip" -C "%GW%" && type nul > "%GW%\gradle-%GRADLE_VER%-bin.zip.ok" && echo Gradle %GRADLE_VER% 설치: %GW%\gradle-%GRADLE_VER% || echo Gradle 을 풀지 못했습니다.
  )
)

set "M2=%USERPROFILE%\.m2\repository"
call :driver com\oracle\database\jdbc ojdbc11
call :driver com\mysql mysql-connector-j
call :driver org\postgresql postgresql

echo.
echo EI IDE 를 켜고 설정에서 Language and Region 을 Korean 으로 고르세요.
pause
exit /b 0

rem JDK zip 하나를 .jdks 에 푼다. 압축 속 맨 위 폴더(jdk-17.0.20.1+1 등)가 이미 있으면 건너뛴다.
:jdk
set "JN="
for /f "delims=/" %%n in ('tar -tf "%~1"') do if not defined JN set "JN=%%n"
if not defined JN (echo JDK 압축을 읽지 못했습니다: %~nx1& goto :eof)
if exist "%JDKS%\%JN%\bin\java.exe" (echo JDK 있음: %JN%& goto :eof)
echo JDK 를 풉니다: %JN% ...
tar -xf "%~1" -C "%JDKS%" && echo JDK 설치: %JDKS%\%JN% || echo JDK 를 풀지 못했습니다: %~nx1
goto :eof

rem drivers 폴더의 <artifact>-<판>.jar 를 ~/.m2/repository/<group>/<artifact>/<판>/ 에 둔다. %1 group(\ 구분) %2 artifact
:driver
for %%j in ("%~dp0drivers\%2-*.jar") do call :driverone "%%~fj" %1 %2
goto :eof

:driverone
set "V=%~n1"
call set "V=%%V:%3-=%%"
set "D=%M2%\%2\%3\%V%"
if exist "%D%\%~nx1" (echo DB 드라이버 있음: %~nx1& goto :eof)
mkdir "%D%" 2>nul
copy /y "%~1" "%D%\" >nul && echo DB 드라이버: %~nx1 || echo DB 드라이버를 복사하지 못했습니다: %~nx1
goto :eof
