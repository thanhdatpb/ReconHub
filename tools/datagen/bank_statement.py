"""Writes a small Vietcombank statement that pays the given orders, to try the upload flow by hand.

The file is what the bank sends: Windows-1258 with decomposed tone marks, ';' separated, dd/MM/yyyy HH:mm:ss.

Usage: python bank_statement.py DH2610240001:388000 [DH2610240002:259000 ...] [--out VCB_SaoKe_20261024_chieu.csv]
"""
from __future__ import annotations

import argparse
import random
from datetime import datetime

from generate import TZ, to_cp1258, vnd_dots


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("payments", nargs="+", help="ORDER_CODE:AMOUNT, e.g. DH2610240001:388000")
    p.add_argument("--out", help="file name; defaults to VCB_SaoKe_<today>_tay.csv")
    args = p.parse_args()

    now = datetime.now(TZ)
    rows = ["Ngày giao dịch;Số tham chiếu;Số tiền ghi nợ;Số tiền ghi có;Số dư;Mô tả"]
    balance = 150_000_000
    for i, item in enumerate(args.payments):
        code, amount = item.split(":")
        balance += int(amount)
        ref = f"FT{now:%y%j}{random.randint(100000, 999999)}"
        rows.append(f"{now:%d/%m/%Y %H:%M}:{i:02d};{ref};;{vnd_dots(int(amount))};{vnd_dots(balance)};CK {code} thanh toan don hang")
    out = args.out or f"VCB_SaoKe_{now:%Y%m%d}_tay.csv"
    with open(out, "wb") as fh:
        fh.write(to_cp1258("\r\n".join(rows) + "\r\n"))
    print(f"Wrote {out} with {len(args.payments)} transfer(s)")


if __name__ == "__main__":
    main()
