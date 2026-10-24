@echo off
chcp 949 >nul
rem EI IDE 를 새로 설치하거나 판을 올린 뒤 한 번 실행한다.
rem 본체에 들어 있지 않은 플러그인(Claude Code · One Dark 테마)을 사용자 플러그인 폴더에 넣고,
rem 예전에 사용자 폴더에 따로 넣었던 플러그인(서버 창 · Synchronize · 롬복 · 화면 묶음 · 한국어 언어 팩)은 본체와 겹치지 않게 치운다.
rem 옆의 plugins 폴더에 없는 것은 JetBrains 마켓에서 내려받는다. 한국어 언어 팩은 메뉴 · 단추를 영어로 깎은 판이 본체에 들어 있다(kit\ko\apply_ko.py).
rem 옆의 jdk · gradle · drivers 폴더에 있는 것은 사용자 폴더에 풀어 둔다(인터넷 없이). 이미 있으면 건너뛴다.
rem   jdk\*.zip      → %USERPROFILE%\.jdks\<압축 속 폴더>  (EI IDE 가 켤 때 SDK 목록에 자동 등록)
rem   gradle\*.zip   → Gradle wrapper 가 같은 판을 찾는 자리 (%USERPROFILE%\.gradle\wrapper\dists)
rem   drivers\*.jar  → %USERPROFILE%\.m2\repository (데이터베이스 창이 여기서 드라이버를 찾는다)
rem 내려받기 · 풀기는 옆의 임시 폴더(.partial)에서 하고, 다 됐을 때만 제자리로 옮긴다. 도중에 실패하면 다음 실행에서 처음부터 다시 한다.
rem 경로는 괄호 · & 가 들어 있어도 되게 늘 따옴표로 감싼다. 지연 확장은 쓰지 않는다.
setlocal
set "HERE=%~dp0"
set "DEST=%APPDATA%\JetBrains\EIIDE2026.2\plugins"
set "SRC=%HERE%plugins"
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

call :fetch claude-code-jetbrains-plugin "%CC_ZIP%" "Claude Code 플러그인" || goto :netfail
call :fetch one-dark-theme "%OD_ZIP%" "One Dark 테마" || echo One Dark 테마는 건너뜁니다.

if not exist "%DEST%" mkdir "%DEST%"
rem 예전에 사용자 폴더에 넣었던 플러그인은 jar 하나이거나 같은 이름 폴더다. 지금은 본체에 들어 있으므로 둘 다 치운다.
for %%n in (boot-servers kinggura-sync kinggura-perspectives ei-db lombok localization-ko) do call :remove_old %%n
rem 예전에 옆 plugins 폴더로 받아 둔 깎지 않은 한국어 팩은 설치하지 않고 옆으로 옮겨 둔다(빌드 때 kit\ko\apply_ko.py 가 깎는 원본).
if exist "%SRC%\localization-ko\" if not exist "%HERE%localization-ko\" move "%SRC%\localization-ko" "%HERE%localization-ko" >nul
if exist "%SRC%\localization-ko\" rmdir /s /q "%SRC%\localization-ko"
rem 판이 바뀌면 jar 이름도 바뀌어 덮어쓰기만으로는 옛 jar 가 남는다. 넣을 플러그인 폴더는 먼저 통째로 지운다.
for /d %%p in ("%SRC%\*") do if exist "%DEST%\%%~nxp\" (rmdir /s /q "%DEST%\%%~nxp" || goto :copyfail)
rem rmdir 은 잠긴 파일이 있어 일부만 지워도 성공으로 끝날 수 있어 남았는지 다시 본다.
for /d %%p in ("%SRC%\*") do if exist "%DEST%\%%~nxp\" goto :copyfail
xcopy /E /I /Y "%SRC%" "%DEST%" >nul || goto :copyfail
echo 플러그인 설치 완료: "%DEST%"

set "JDKS=%USERPROFILE%\.jdks"
if not exist "%JDKS%" mkdir "%JDKS%"
for %%z in ("%HERE%jdk\*.zip") do call :jdk "%%~fz"

call :gradle

set "M2=%USERPROFILE%\.m2\repository"
call :driver com\oracle\database\jdbc ojdbc11
call :driver com\mysql mysql-connector-j
call :driver org\postgresql postgresql

echo.
echo EI IDE 를 켜고 설정에서 Language and Region 을 Korean 으로 고르세요.
pause
exit /b 0

:netfail
echo 인터넷 연결을 확인한 뒤 다시 실행하세요.
pause
exit /b 1

:copyfail
echo 플러그인 폴더를 바꾸지 못했습니다: "%DEST%"
echo EI IDE 가 완전히 꺼졌는지 확인한 뒤 다시 실행하세요.
pause
exit /b 1

rem 플러그인 zip 을 내려받아 임시 폴더에 풀고, 압축 속 폴더가 다 풀렸을 때만 plugins 로 옮긴다.
rem %1 압축 속 맨 위 폴더 이름  %2 주소  %3 화면에 보일 이름. 이미 있으면 건너뛴다. 실패하면 errorlevel 1.
:fetch
if exist "%SRC%\%~1\" exit /b 0
echo %~3 을(를) 내려받습니다...
set "FP=%HERE%.partial"
set "FZ=%TEMP%\eiide-%~1.zip"
if exist "%FP%" rmdir /s /q "%FP%"
mkdir "%FP%" || goto :fetchfail
curl -fL -o "%FZ%" "%~2" || goto :fetchfail
tar -xf "%FZ%" -C "%FP%" || goto :fetchfail
if not exist "%FP%\%~1\" goto :fetchfail
move "%FP%\%~1" "%SRC%\" >nul || goto :fetchfail
rmdir /s /q "%FP%"
del /q "%FZ%"
exit /b 0
:fetchfail
echo %~3 을(를) 받지 못했습니다.
if exist "%FP%" rmdir /s /q "%FP%"
if exist "%FZ%" del /q "%FZ%"
exit /b 1

rem %1 이름의 옛 플러그인(폴더 %1 · jar %1*.jar)을 사용자 플러그인 폴더에서 지운다.
:remove_old
if exist "%DEST%\%~1\" (rmdir /s /q "%DEST%\%~1" && echo 치움: %~1)
for %%f in ("%DEST%\%~1*.jar") do (del /q "%%~f" && echo 치움: %%~nxf)
goto :eof

rem JDK zip 하나를 .jdks 에 푼다. 압축 속 맨 위 폴더(jdk-17.0.20.1+1 등)에 java.exe 가 이미 있으면 건너뛴다.
rem java.exe 가 없는 같은 이름 폴더는 예전에 반쯤 풀린 것이므로 지우고 다시 둔다. 임시 폴더는 .jdks 밖(옆)에 둬서 SDK 로 잡히지 않게 한다.
:jdk
set "JN="
for /f "delims=/" %%n in ('tar -tf "%~1"') do if not defined JN set "JN=%%n"
if not defined JN goto :jdkunreadable
if exist "%JDKS%\%JN%\bin\java.exe" goto :jdkhave
echo JDK 를 풉니다: "%JN%" ...
set "JP=%JDKS%.partial"
if exist "%JP%" rmdir /s /q "%JP%"
mkdir "%JP%" || goto :jdkfail
tar -xf "%~1" -C "%JP%" || goto :jdkfail
if not exist "%JP%\%JN%\bin\java.exe" goto :jdkfail
if exist "%JDKS%\%JN%" rmdir /s /q "%JDKS%\%JN%"
move "%JP%\%JN%" "%JDKS%\" >nul || goto :jdkfail
rmdir /s /q "%JP%"
echo JDK 설치: "%JDKS%\%JN%"
goto :eof
:jdkhave
echo JDK 있음: "%JN%"
goto :eof
:jdkunreadable
echo JDK 압축을 읽지 못했습니다: "%~nx1"
goto :eof
:jdkfail
echo JDK 를 풀지 못했습니다: "%~nx1"
if exist "%JP%" rmdir /s /q "%JP%"
goto :eof

rem gradle 폴더의 배포판 zip 을 wrapper 자리에 푼다. wrapper 는 zip 옆의 .ok 표시가 있어야 다 풀린 것으로 보므로 .ok 는 맨 마지막에 만든다.
rem 임시 폴더는 배포판 폴더 밖(옆)에 둔다. wrapper 는 배포판 폴더 안에 폴더가 하나뿐이어야 한다.
:gradle
set "GZ=%HERE%gradle\gradle-%GRADLE_VER%-bin.zip"
set "GW=%USERPROFILE%\.gradle\wrapper\dists\gradle-%GRADLE_VER%-bin\%GRADLE_HASH%"
set "GP=%GW%.partial"
if not exist "%GZ%" goto :eof
if exist "%GW%\gradle-%GRADLE_VER%-bin.zip.ok" goto :gradlehave
if exist "%GP%" rmdir /s /q "%GP%"
mkdir "%GP%" || goto :gradlefail
tar -xf "%GZ%" -C "%GP%" || goto :gradlefail
if not exist "%GP%\gradle-%GRADLE_VER%\bin\" goto :gradlefail
if not exist "%GW%" mkdir "%GW%"
if exist "%GW%\gradle-%GRADLE_VER%" rmdir /s /q "%GW%\gradle-%GRADLE_VER%"
move "%GP%\gradle-%GRADLE_VER%" "%GW%\" >nul || goto :gradlefail
copy /y "%GZ%" "%GW%\" >nul || goto :gradlefail
rmdir /s /q "%GP%"
type nul > "%GW%\gradle-%GRADLE_VER%-bin.zip.ok"
echo Gradle %GRADLE_VER% 설치: "%GW%\gradle-%GRADLE_VER%"
goto :eof
:gradlehave
echo Gradle %GRADLE_VER% 있음
goto :eof
:gradlefail
echo Gradle 을 풀지 못했습니다.
if exist "%GP%" rmdir /s /q "%GP%"
goto :eof

rem drivers 폴더의 <artifact>-<판>.jar 를 ~/.m2/repository/<group>/<artifact>/<판>/ 에 둔다. %1 group(\ 구분) %2 artifact
:driver
for %%j in ("%HERE%drivers\%2-*.jar") do call :driverone "%%~fj" %1 %2
goto :eof

rem 복사는 .part 이름으로 한 뒤 바꿔서, 도중에 끊겨도 반쪽 jar 가 「있음」으로 잡히지 않게 한다.
:driverone
set "V=%~n1"
call set "V=%%V:%3-=%%"
set "D=%M2%\%2\%3\%V%"
if exist "%D%\%~nx1" goto :driverhave
mkdir "%D%" 2>nul
copy /y "%~1" "%D%\%~nx1.part" >nul && move /y "%D%\%~nx1.part" "%D%\%~nx1" >nul && echo DB 드라이버: "%~nx1" || echo DB 드라이버를 복사하지 못했습니다: "%~nx1"
goto :eof
:driverhave
echo DB 드라이버 있음: "%~nx1"
goto :eof
