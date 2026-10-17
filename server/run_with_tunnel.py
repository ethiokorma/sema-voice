"""One-click Sema server: starts the speech server on this PC, opens a free Cloudflare quick tunnel
(https://<random>.trycloudflare.com, no account needed) and shows a QR code. Scanning it on a
phone opens the Sema Voice web app already connected to this server.

    python server/run_with_tunnel.py

Stop with Ctrl+C (or close the window). The tunnel address changes on every start; the access key
stays the same (server/.token). Anyone without the key gets "401 bad or missing access key".
"""
import os
import re
import shutil
import subprocess
import sys
import threading
import time
import urllib.parse
import urllib.request
import webbrowser

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
APP_URL = "https://ethiokorma.github.io/sema-voice/"
PORT = 8765


def find_cloudflared() -> str:
    exe = shutil.which("cloudflared")
    if exe:
        return exe
    cand = os.path.expanduser(r"~\scoop\shims\cloudflared.exe")
    if os.path.exists(cand):
        return cand
    sys.exit("cloudflared not found. Install it with:  scoop install cloudflared   (or winget install Cloudflare.cloudflared)")


def wait_health(url: str, timeout: float) -> bool:
    end = time.time() + timeout
    while time.time() < end:
        try:
            with urllib.request.urlopen(url, timeout=5) as r:
                if r.status == 200:
                    return True
        except Exception:
            time.sleep(1)
    return False


def write_connect_page(link: str, tunnel: str) -> str:
    import segno

    svg = segno.make(link, error="m").svg_inline(scale=8, dark="#0E1525", light="#FFFFFF")
    page = os.path.join(ROOT, "server", "connect.html")
    html = f"""<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Sema server: connect</title>
<style>body{{font-family:system-ui,sans-serif;background:#F6F7FB;color:#0E1525;margin:0;padding:32px 16px;text-align:center}}
.card{{background:#fff;border-radius:20px;max-width:460px;margin:auto;padding:28px;box-shadow:0 6px 24px #0001}}
code{{word-break:break-all;font-size:12px;color:#667085}} a{{color:#4F46E5}}</style>
<div class="card"><h1>Scan to connect</h1>
<p>Open your phone camera and scan this code. Sema Voice opens already connected to this computer.</p>
{svg}
<p><a href="{link}">Or open the link on this computer</a></p>
<p><code>{tunnel}</code></p>
<p style="color:#667085;font-size:13px">Keep this PC and the server window running while you test. The address changes each time you start the server.</p></div>"""
    open(page, "w", encoding="utf-8").write(html)
    return page


def main():
    py = sys.executable
    srv = subprocess.Popen([py, os.path.join(ROOT, "server", "asr_server.py"), "--port", str(PORT)],
                           stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding="utf-8")
    key = None
    for line in srv.stdout:
        print("[server]", line.rstrip())
        if line.startswith("Access key:"):
            key = line.split(":", 1)[1].strip()
            break
    if not key:
        sys.exit("speech server failed to start")
    threading.Thread(target=lambda: [print("[server]", l.rstrip()) for l in srv.stdout], daemon=True).start()
    if not wait_health(f"http://127.0.0.1:{PORT}/v1/health", 120):
        srv.kill()
        sys.exit("speech server did not answer")

    cf = subprocess.Popen([find_cloudflared(), "tunnel", "--no-autoupdate", "--url", f"http://127.0.0.1:{PORT}"],
                          stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding="utf-8")
    tunnel = None
    t0 = time.time()
    for line in cf.stdout:
        m = re.search(r"https://[a-z0-9-]+\.trycloudflare\.com", line)
        if m:
            tunnel = m.group(0)
            break
        if time.time() - t0 > 90:
            break
    if not tunnel:
        srv.kill(); cf.kill()
        sys.exit("Cloudflare tunnel did not start (check the internet connection)")
    threading.Thread(target=lambda: [None for _ in cf.stdout], daemon=True).start()

    print("Waiting for the public address to come online…", flush=True)
    online = wait_health(tunnel + "/v1/health", 90)
    link = APP_URL + "#" + urllib.parse.urlencode({"server": tunnel, "key": key})
    page = write_connect_page(link, tunnel)
    print("\n" + "=" * 70)
    print(" Sema server is " + ("ONLINE" if online else "starting (address may need a minute)"))
    print(" Public address :", tunnel)
    print(" Phone link     :", link)
    print(" QR code page   :", page)
    print(" Press Ctrl+C to stop.")
    print("=" * 70 + "\n", flush=True)
    webbrowser.open("file:///" + page.replace("\\", "/"))
    try:
        while srv.poll() is None and cf.poll() is None:
            time.sleep(1)
    except KeyboardInterrupt:
        pass
    finally:
        for p in (cf, srv):
            if p.poll() is None:
                p.terminate()
        print("Stopped.")


if __name__ == "__main__":
    main()
