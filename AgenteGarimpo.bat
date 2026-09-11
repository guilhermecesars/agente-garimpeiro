@echo off
echo ==========================================
echo Compilando e iniciando o Agente Garimpeiro...
echo ==========================================

if exist config.local.bat (
    call .\config.local.bat
) else (
    echo AVISO: config.local.bat nao encontrado. Copie config.local.bat.example
    echo e preencha sua RAPIDAPI_KEY antes de continuar.
)

if not exist bin mkdir bin

javac -encoding UTF-8 -d bin src\com\garimpo\*.java

if %errorlevel% neq 0 (
    echo.
    echo Erro ao compilar o Java. Verifique o codigo.
    pause
    exit /b
)

java -cp bin com.garimpo.Main
pause