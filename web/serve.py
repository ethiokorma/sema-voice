import http.server, mimetypes, functools, sys
mimetypes.add_type('application/manifest+json', '.webmanifest')
mimetypes.add_type('text/javascript', '.mjs')
mimetypes.add_type('application/octet-stream', '.onnx')
class H(http.server.SimpleHTTPRequestHandler):
    def end_headers(self):
        self.send_header('Cache-Control', 'no-cache')
        if '--coi' in sys.argv:
            self.send_header('Cross-Origin-Opener-Policy', 'same-origin')
            self.send_header('Cross-Origin-Embedder-Policy', 'credentialless')
        super().end_headers()
    def log_message(self, *a): pass
http.server.ThreadingHTTPServer(('127.0.0.1', 8081 if '--coi' in sys.argv else 8080), functools.partial(H, directory=sys.argv[1])).serve_forever()
