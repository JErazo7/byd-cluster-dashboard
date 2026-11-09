#!/usr/bin/env python3
"""Serve themes/ para pré-visualizar as skins no navegador (PC ou celular).

Igual a `python3 -m http.server`, mas manda `Cache-Control: no-store`: sem isso o
navegador guarda o index.html e as imagens e continua mostrando a versão antiga
depois de editar a skin.

    python3 tools/preview-server.py [porta]     # padrão 8765
"""
import functools
import http.server
import os
import socketserver
import sys

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8765
ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "themes")


class Handler(http.server.SimpleHTTPRequestHandler):
    extensions_map = {
        **http.server.SimpleHTTPRequestHandler.extensions_map,
        ".js": "text/javascript",
        ".jpg": "image/jpeg",
        ".png": "image/png",
        ".webp": "image/webp",
    }

    def end_headers(self):
        self.send_header("Cache-Control", "no-store, must-revalidate")
        self.send_header("Pragma", "no-cache")
        self.send_header("Expires", "0")
        super().end_headers()

    def log_message(self, fmt, *args):
        sys.stderr.write("%s %s\n" % (self.address_string(), fmt % args))


class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = True


if __name__ == "__main__":
    handler = functools.partial(Handler, directory=ROOT)
    with Server(("0.0.0.0", PORT), handler) as httpd:
        print("temas em http://0.0.0.0:%d/  (raiz: %s)" % (PORT, os.path.normpath(ROOT)))
        httpd.serve_forever()
