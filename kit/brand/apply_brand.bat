@echo off
rem EI IDE 그림(아이콘 · 시작 화면 · 설치 프로그램)을 소스에 넣는다. 새로 받은 소스에 한 번 실행한다.
set "SRC=%~dp0..\..\intellij-community"
copy /Y "%~dp0win\*" "%SRC%\build\idea-community-images\win\" >nul || goto fail
copy /Y "%~dp0linux\*" "%SRC%\build\idea-community-images\linux\" >nul || goto fail
copy /Y "%~dp0res\*" "%SRC%\community-resources\resources\" >nul || goto fail
echo 그림 적용 완료
exit /b 0
:fail
echo 그림 복사에 실패했습니다. intellij-community 폴더가 있는지 확인하세요.
exit /b 1
