@echo off
rem Hypatia launcher for Windows. Keep it next to hypatia.jar; to open .tex files from
rem Explorer or a PDF viewer, call it as:  hypatia.cmd --line 12 "C:\path\file.tex"
rem javaw starts Hypatia without a console window.
start "" javaw -jar "%~dp0hypatia.jar" %*
