#!/usr/bin/env python3
"""
tools/launch_showcase.py
===============================================================================
Interactive Desktop Showcase & 3D Visualizer Launcher
Intelligent Dead Reckoning System (SIH #26168 - ISRO / Department of Space)

Spins up a lightweight local server and opens the 3D trajectory visualizer
in your default browser for jury presentation and field audit inspection.
===============================================================================
"""

from __future__ import annotations

import http.server
import socketserver
import threading
import webbrowser
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
VISUALIZER_DIR = ROOT / "tools" / "visualizer"
PORT = 8080


class QuietHandler(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=str(VISUALIZER_DIR), **kwargs)

    def log_message(self, format, *args):
        # Suppress routine GET request noise
        pass


def launch_server() -> None:
    socketserver.TCPServer.allow_reuse_address = True
    with socketserver.TCPServer(("", PORT), QuietHandler) as httpd:
        url = f"http://localhost:{PORT}/index.html"
        print("===============================================================================")
        print("  INTELLIGENT DEAD RECKONING (SIH #26168) - DESKTOP SHOWCASE LAUNCHER")
        print("===============================================================================")
        print(f"  [>] Serving 3D Satellite Visualizer at: {url}")
        print("  [>] Press Ctrl+C at any time to stop the showcase server.")
        print("===============================================================================")

        threading.Timer(1.0, lambda: webbrowser.open(url)).start()

        try:
            httpd.serve_forever()
        except KeyboardInterrupt:
            print("\n[IDR] Showcase server stopped.")


if __name__ == "__main__":
    launch_server()
