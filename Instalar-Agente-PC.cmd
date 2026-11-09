@echo off
rem Instalador interativo: a janela precisa mostrar o codigo de pareamento.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0pc\solicitar-instalacao.ps1"
