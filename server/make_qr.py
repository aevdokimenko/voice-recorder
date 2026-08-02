#!/usr/bin/env python3
"""Generate an enrollment QR code for LR.

    pip install "qrcode[pil]"
    python3 make_qr.py https://lr.example.com <enrollment-token>

Writes lr-enroll.png and also prints the code to the terminal.
"""
import sys

import qrcode

if len(sys.argv) != 3:
    sys.exit("usage: make_qr.py <https://host> <enrollment-token>")

host, token = sys.argv[1].rstrip("/"), sys.argv[2]
payload = f"{host}/api/v1/enroll?token={token}"

qr = qrcode.QRCode(border=2)
qr.add_data(payload)
qr.make(fit=True)
qr.print_ascii(invert=True)
qr.make_image().save("lr-enroll.png")
print(f"\n{payload}\n\nwrote lr-enroll.png")
