@echo off
chcp 949 >nul
rem EI IDE 를 새로 설치하거나 판을 올린 뒤 한 번 실행한다.
rem 본체에 들어 있지 않은 플러그인(한국어 언어 팩)을 사용자 플러그인 폴더에 넣고,
rem 예전에 사용자 폴더에 따로 넣었던 플러그인(서버 창 · Synchronize · 롬복 · 화면 묶음)은 본체와 겹치지 않게 치운다.
set "DEST=%APPDATA%\JetBrains\EIIDE2026.2\plugins"
if not exist "%~dp0plugins" (
  echo plugins 폴더가 이 파일 옆에 없습니다.
  pause
  exit /b 1
)
tasklist /fi "imagename eq idea64.exe" | find /i "idea64.exe" >nul && (
  echo EI IDE 를 먼저 종료한 뒤 다시 실행하세요.
  pause
  exit /b 1
)
if not exist "%DEST%" mkdir "%DEST%"
for %%f in ("%DEST%\boot-servers*.jar" "%DEST%\kinggura-sync*.jar" "%DEST%\kinggura-perspectives*.jar") do if exist %%f (del /q %%f && echo 치움: %%~nxf)
if exist "%DEST%\lombok" (rmdir /s /q "%DEST%\lombok" && echo 치움: lombok)
xcopy /E /I /Y "%~dp0plugins" "%DEST%" >nul || (
  echo 복사에 실패했습니다: %DEST%
  pause
  exit /b 1
)
echo 플러그인 설치 완료: %DEST%
echo EI IDE 를 켜고 설정에서 Language and Region 을 Korean 으로 고르세요.
pause
