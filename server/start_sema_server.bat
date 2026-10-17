@echo off
title Sema server (keep this window open while testing)
rem Starts the Sema speech server (best Amharic model) and a free Cloudflare tunnel,
rem then opens a QR code page. Scan it with your phone to use Sema Voice in any browser.
cd /d "G:\BUSSINESS IDEAS\SEMAVOICE"
set PYTHONIOENCODING=utf-8
"C:\Users\Tedz\scoop\apps\miniconda3\current\python.exe" serverun_with_tunnel.py
pause
