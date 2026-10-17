# Sema server: the best Amharic model in any browser

Runs Ethio-ASR (ONNX int8, 22% word error on FLEURS) natively on this PC. Phones, iPhones and
other computers use it through the Sema Voice web app. This needs internet, not an offline model
on the phone.

## Start

Double-click `start_sema_server.bat`, or run `python server/run_with_tunnel.py`.

1. The speech server starts on `127.0.0.1:8765`.
2. A free Cloudflare quick tunnel gives it a public `https://<random>.trycloudflare.com` address.
   No account is needed.
3. A QR code page opens. Scan it on the phone. The web app opens already connected, with
   "Sema server" as the engine.

Keep the window open while testing. The address changes on every start, so scan the new QR code
each time.

## Privacy and security

* Every transcription needs the access key in `server/.token`. It is created on first start and
  is git-ignored. Delete the file to rotate the key.
* The key travels in the link's `#fragment`, which browsers never send to GitHub Pages.
* Audio is processed in memory. It is never written to disk, and requests are not logged.
* Audio passes through Cloudflare's network on its way to this PC. Quick tunnels are meant for
  testing. For a permanent setup, use a named Cloudflare tunnel (free account) or a small VPS.

## Speed

On the 4-core Xeon, transcribing takes about 1.1 times the length of the speech. So 10 s of
speech takes about 11 s, plus upload time. One request runs at a time.
