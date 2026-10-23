"""Generate ReconHub sample data: OMS orders, partner settlement files and the ground truth.

Each partner file follows that partner's own format (column names, date and number formats, CSV or XLSX,
Windows-1258 for the bank). A small, controlled share of lines carries a realistic problem (typo in the
transfer description, short payment, unannounced fee change, missing or late payout, duplicate statement...).
truth.csv records, for every line, the orders it really belongs to, so matching quality can be measured.

Usage:
    python generate.py --out /data [--today 2026-10-22] [--days 30] [--seed 20261022] [--force]
"""
from __future__ import annotations

import argparse
import csv
import io
import random
import unicodedata
from dataclasses import dataclass, field
from datetime import date, datetime, time, timedelta, timezone
from pathlib import Path

from openpyxl import Workbook

TZ = timezone(timedelta(hours=7))

LAST_NAMES = ["Nguyễn", "Trần", "Lê", "Phạm", "Hoàng", "Vũ", "Đặng", "Bùi", "Đỗ", "Ngô", "Dương", "Lý", "Phan", "Võ", "Trịnh",
              "Mai", "Hồ", "Cao", "Tạ", "Lương", "Đinh", "Chu", "Kiều", "La", "Quách"]
MIDDLE = ["Thị", "Văn", "Minh", "Thu", "Đức", "Ngọc", "Quang", "Gia", "Khánh", "Thanh", "Hoàng", "Mỹ", "Anh", "Bảo", "Hải", "Thùy"]
FIRST = ["Lan", "Hùng", "Mai", "Tuấn", "Trang", "Anh", "Hân", "Huy", "Hương", "Bảo", "Linh", "Tâm", "Yến", "Long", "Duyên",
         "Thư", "Phúc", "Ngọc", "Châu", "Ngân", "Danh", "Hà", "Việt", "Uyên", "Kiệt", "Thảo", "Vinh", "Dung", "Nam", "Khoa"]
ALNUM = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

CARRIERS = ("GHN", "GHTK")
EXPECTED_HOUR = {"VCB": 7, "GHN": 8, "GHTK": 9, "MOMO": 10, "SHOPEE": 11, "TIKTOK": 11}


def fold(s: str) -> str:
    """Nguyễn Thị Lan -> NGUYEN THI LAN, the way banks print names."""
    n = unicodedata.normalize("NFD", s)
    n = "".join(c for c in n if unicodedata.category(c) != "Mn").replace("đ", "d").replace("Đ", "D")
    return " ".join(n.upper().split())


def half_up(numerator: int, denominator: int) -> int:
    return (numerator + denominator // 2) // denominator


def expected_fee(source: str, order: "Order", gross: int) -> int:
    """Same arithmetic as FeeCalculator.java."""
    if source == "GHN":
        return 22_000 + max(5_000, half_up(order.cod * 5, 1_000)) + (8_000 if order.remote_flag else 0)
    if source == "GHTK":
        return 30_000 if order.amount > 900_000 else 20_000
    if source == "SHOPEE":
        return half_up(order.amount * 400, 10_000) + half_up(order.amount * 491, 10_000)
    if source == "TIKTOK":
        return half_up(order.amount * 5, 100)
    if source == "MOMO":
        return half_up(gross, 100)
    return 0


def vnd_dots(n: int) -> str:
    return f"{n:,}".replace(",", ".")


def to_cp1258(text: str) -> bytes:
    """Windows-1258 has precomposed vowels (â ê ô ă ơ ư) but separate tone marks, like old Vietnamese bank systems."""
    out = []
    for ch in unicodedata.normalize("NFD", text):
        if out and unicodedata.combining(ch) and ch in "̛̂̆":
            out[-1] = unicodedata.normalize("NFC", out[-1] + ch)
        else:
            out.append(ch)
    return "".join(out).encode("cp1258", errors="replace")


@dataclass
class Order:
    code: str
    channel: str
    channel_ref: str
    customer: str
    phone: str
    amount: int
    cod: int
    pay_via: str
    tracking: str
    status: str
    remote_area: bool
    remote_flag: bool
    created: datetime
    delivered: datetime | None = None
    note: str = ""


@dataclass
class Line:
    source: str
    ref: str
    values: list
    truth_orders: list[str]
    anomaly: str = "NONE"


@dataclass
class PartnerFile:
    source: str
    name: str
    business_date: date
    lines: list[Line] = field(default_factory=list)
    broken_dates: bool = False


class Generator:
    def __init__(self, today: date, days: int, seed: int):
        self.r = random.Random(seed)
        self.today = today
        self.now = datetime.combine(today, time(9, 30), TZ)
        self.days = days
        self.first_file_day = today - timedelta(days=days)
        self.orders: list[Order] = []
        self.files: dict[tuple[str, date, str], PartnerFile] = {}
        self.customers = [f"{self.r.choice(LAST_NAMES)} {self.r.choice(MIDDLE)} {self.r.choice(FIRST)}" for _ in range(220)]

    # ------------------------------------------------------------------ helpers
    def alnum(self, n: int) -> str:
        return "".join(self.r.choice(ALNUM) for _ in range(n))

    def digits(self, n: int) -> str:
        return "".join(str(self.r.randint(0, 9)) for _ in range(n))

    def at(self, d: date, h: int, m: int = 0, s: int = 0) -> datetime:
        return datetime.combine(d, time(h, m, s), TZ)

    def file(self, source: str, d: date, suffix: str = "") -> PartnerFile:
        ext = {"GHN": "xlsx", "SHOPEE": "xlsx"}.get(source, "csv")
        prefix = {"GHN": "GHN_DOISOAT_", "GHTK": "GHTK_COD_", "SHOPEE": "Shopee_Income_", "TIKTOK": "TTS_Settlement_",
                  "VCB": "VCB_SaoKe_", "MOMO": "MOMO_Settlement_"}[source]
        key = (source, d, suffix)
        if key not in self.files:
            self.files[key] = PartnerFile(source, f"{prefix}{d:%Y%m%d}{suffix}.{ext}", d)
        return self.files[key]

    def has_file(self, source: str, d: date) -> bool:
        if d < self.first_file_day or d > self.today:
            return False
        if source in ("GHTK", "MOMO") and d == self.today:
            return False  # these arrive later in the day
        if source == "SHOPEE":
            return d.isoweekday() in (1, 4)
        if source == "TIKTOK":
            return d.isoweekday() == 2
        return True

    # ------------------------------------------------------------------ orders
    def make_orders(self) -> None:
        start = self.first_file_day - timedelta(days=3)
        d = start
        while d <= self.today:
            per_day: dict[date, int] = {}
            for _ in range(self.r.randint(150, 230)):
                created = self.at(d, self.r.randint(7, 22), self.r.randint(0, 59), self.r.randint(0, 59))
                if created > self.now:
                    continue
                seq = per_day.get(d, 0) + 1
                per_day[d] = seq
                channel = self.r.choices(["WEBSITE", "SHOPEE", "TIKTOK"], [40, 38, 22])[0]
                if channel == "SHOPEE":
                    pay_via = "SHOPEE"
                elif channel == "TIKTOK":
                    pay_via = "TIKTOK"
                else:
                    pay_via = self.r.choices(["GHN", "GHTK", "VCB", "MOMO"], [32, 24, 32, 12])[0]
                amount = self.r.randint(129, 1890) * 1000
                cod = amount if pay_via in CARRIERS else 0
                tracking = f"GHN{self.alnum(8)}" if pay_via == "GHN" else f"S2188{self.digits(5)}" if pay_via == "GHTK" else ""
                channel_ref = (f"{created:%y%m%d}{self.alnum(8)}" if channel == "SHOPEE"
                               else f"5781{self.digits(14)}" if channel == "TIKTOK" else "")
                remote = pay_via == "GHN" and self.r.random() < 0.07
                o = Order(code=f"DH{created:%y%m%d}{seq:04d}", channel=channel, channel_ref=channel_ref,
                          customer=self.r.choice(self.customers), phone=f"09{self.digits(2)}***{self.digits(3)}",
                          amount=amount, cod=cod, pay_via=pay_via, tracking=tracking, status="NEW",
                          remote_area=remote, remote_flag=remote and self.r.random() > 0.25, created=created)
                self.set_delivery(o)
                self.orders.append(o)
            d += timedelta(days=1)

    def set_delivery(self, o: Order) -> None:
        if o.pay_via in CARRIERS and self.r.random() < 0.04:
            o.status = "CANCELLED"
            return
        hours = self.r.randint(30, 80) if o.pay_via in ("SHOPEE", "TIKTOK") else self.r.randint(20, 60)
        delivered = o.created + timedelta(hours=hours, minutes=self.r.randint(0, 59))
        if delivered > self.now:
            o.status = "SHIPPING"
            return
        if o.pay_via in CARRIERS and self.r.random() < 0.03:
            o.status = "RETURNED"
            o.note = "Khách từ chối nhận hàng"
            return
        o.status = "DELIVERED"
        o.delivered = delivered

    # ------------------------------------------------------------------ partner lines
    def make_carrier_lines(self) -> None:
        for o in self.orders:
            if o.pay_via not in CARRIERS or o.status != "DELIVERED":
                continue
            day = o.delivered.date() + timedelta(days=1)
            if not self.has_file(o.pay_via, day):
                continue
            roll = self.r.random()
            late = False
            if roll < 0.015:
                if roll < 0.006 and self.has_file(o.pay_via, day + timedelta(days=9)):
                    # paid nine days late: UNPAID_ORDER is raised first, then closed when the money arrives
                    day, late, roll = day + timedelta(days=9), True, 1.0
                else:
                    continue  # payout never sent -> UNPAID_ORDER after 7 days
            fee = expected_fee(o.pay_via, o, o.cod)
            anomaly, note, paid = "NONE", "Giao thành công" if o.pay_via == "GHN" else "Đã đối soát", o.cod
            if o.pay_via == "GHN" and o.remote_area:
                note = "Phụ phí vùng xa"
                if not o.remote_flag:
                    fee += 8_000  # OMS did not flag the address -> FEE_DIFF
                    anomaly = "REMOTE_NOT_FLAGGED"
            if o.pay_via == "GHN" and day >= self.today - timedelta(days=10) and o.cod > 1_500_000:
                fee += 2_000  # unannounced fee change on large COD
                anomaly = "FEE_CHANGE"
            if roll < 0.025:
                paid = o.cod - self.r.randint(5, 90) * 1000
                anomaly = "SHORT_PAY"
                note = "" if o.pay_via == "GHN" else "Đã đối soát"
            elif roll < 0.032:
                paid = o.cod - (o.cod * 4 // 10) // 1000 * 1000
                anomaly = "PARTIAL_DELIVERY"
                note = "Giao 1 phần - KH trả lại 1 sp"
            elif roll < 0.035:
                paid = o.cod - 500
                anomaly = "ROUNDING"
            if late and anomaly == "NONE":
                anomaly = "LATE_PAYOUT"
            net = paid - fee
            if o.pay_via == "GHN":
                values = [o.tracking, o.code, f"{day:%d/%m/%Y}", vnd_dots(o.cod), vnd_dots(fee), vnd_dots(net), note]
                if self.r.random() < 0.004:
                    values[5] = vnd_dots(net).replace("0", "O", 1)
                    anomaly = "ROW_ERROR"
            else:
                stamp = (o.delivered + timedelta(hours=4)).strftime("%d-%m-%Y %H:%M")
                values = [o.tracking, o.code, stamp, str(o.cod), str(fee), str(net), note]
                if self.r.random() < 0.004:
                    values[5] = "N/A"
                    anomaly = "ROW_ERROR"
            self.file(o.pay_via, day).lines.append(Line(o.pay_via, o.tracking, values, [o.code], anomaly))
            if o.pay_via == "GHTK" and self.r.random() < 0.006:
                nxt = day + timedelta(days=1)
                if self.has_file("GHTK", nxt):
                    self.file("GHTK", nxt).lines.append(Line("GHTK", o.tracking, list(values), [o.code], "DUPLICATE_LINE"))

    def make_marketplace_lines(self) -> None:
        for o in self.orders:
            if o.pay_via not in ("SHOPEE", "TIKTOK") or o.status != "DELIVERED":
                continue
            day = o.delivered.date() + timedelta(days=2)
            while not (day.isoweekday() in ((1, 4) if o.pay_via == "SHOPEE" else (2,))):
                day += timedelta(days=1)
            if not self.has_file(o.pay_via, day):
                continue
            roll = self.r.random()
            if roll < 0.01:
                continue  # missing payout
            fee = expected_fee(o.pay_via, o, o.amount)
            anomaly, paid = "NONE", o.amount
            if roll < 0.02:
                fee += half_up(o.amount, 100)  # charged one more percent
                anomaly = "FEE_OVERCHARGE"
            elif roll < 0.027:
                paid = o.amount - self.r.randint(10, 40) * 1000  # voucher deducted twice
                anomaly = "SHORT_PAY"
            net = paid - fee
            if o.pay_via == "SHOPEE":
                fixed = half_up(o.amount * 400, 10_000)
                values = [o.channel_ref, f"{day:%Y-%m-%d}", o.amount, fixed, fee - fixed, fee, net]
            else:
                values = [o.channel_ref, f"{day:%Y/%m/%d} 03:{self.r.randint(10, 40):02d}:{self.r.randint(0, 59):02d}", o.amount, fee, net]
            self.file(o.pay_via, day).lines.append(Line(o.pay_via, o.channel_ref, values, [o.code], anomaly))

    def make_bank_lines(self) -> None:
        bank_orders = [o for o in self.orders if o.pay_via in ("VCB", "MOMO")]
        used: set[str] = set()
        for o in bank_orders:
            if o.code in used:
                continue
            txn = o.created + timedelta(minutes=self.r.randint(5, 20 * 60))
            day = txn.date() + timedelta(days=1)
            if txn > self.now or not self.has_file(o.pay_via, day):
                continue
            roll = self.r.random()
            if roll < 0.02:
                continue  # customer never paid
            name = fold(o.customer)
            codes, amount, anomaly = [o.code], o.amount, "NONE"
            ref_col = ""
            if o.pay_via == "VCB":
                if roll < 0.10:
                    desc, anomaly = f"{name} chuyen tien", "NO_CODE"
                elif roll < 0.16:
                    desc, anomaly = f"CK {self.typo(o.code)} {name}", "TYPO_CODE"
                elif roll < 0.20:
                    desc, anomaly = f"IBFT {fold(self.r.choice(self.customers))} ck tien hang", "OTHER_PAYER"
                elif roll < 0.24:
                    other = self.find_same_day_partner(o, bank_orders, used)
                    if other is not None:
                        used.add(other.code)
                        codes = [o.code, other.code]
                        amount = o.amount + other.amount
                        if self.r.random() < 0.6:
                            desc, anomaly = f"tt don {o.code[-4:]} va {other.code[-4:]} {name}", "MULTI_ORDER"
                        else:
                            desc, anomaly = f"{name} chuyen tien 2 don", "MULTI_ORDER_NO_CODE"
                    else:
                        desc = f"CK {o.code} {name}"
                elif roll < 0.27:
                    amount = o.amount - self.r.randint(10, 50) * 1000
                    desc, anomaly = f"CK {o.code} {name}", "SHORT_PAY"
                else:
                    desc = self.r.choice([f"CK {o.code} {name}", f"MBVCB.{self.digits(7)}.{name}.thanh toan don {o.code}",
                                          f"{o.code} {name} thanh toan", f"IBFT {name} chuyen tien don {o.code}"])
                values = [txn, f"FT{txn:%y}{txn.timetuple().tm_yday:03d}{self.digits(6)}", amount, desc]
            else:
                fee = half_up(amount, 100)
                if roll < 0.15:
                    desc, anomaly = f"Chuyen tien tu {fold(o.customer).title()}", "NO_CODE"
                elif roll < 0.19:
                    desc, anomaly = f"Thanh toan don {self.typo(o.code)}", "TYPO_CODE"
                else:
                    desc, ref_col = f"Thanh toan don {o.code}", o.code
                values = [txn, f"482{self.digits(8)}", amount, fee, desc, ref_col]
            self.file(o.pay_via, day).lines.append(Line(o.pay_via, values[1], values, codes, anomaly))
            used.add(o.code)
        # Money that belongs to no order: a supplier refund.
        for d in sorted({f.business_date for f in self.files.values() if f.source == "VCB"})[::9]:
            txn = self.at(d - timedelta(days=1), 15, self.r.randint(0, 59))
            amount = self.r.randint(200, 900) * 1000
            vals = [txn, f"FT{txn:%y}{txn.timetuple().tm_yday:03d}{self.digits(6)}", amount, "HOAN TIEN NCC CONG TY MAY AN PHAT"]
            self.file("VCB", d).lines.append(Line("VCB", vals[1], vals, [], "NO_ORDER"))

    def find_same_day_partner(self, o: Order, pool: list[Order], used: set[str]) -> Order | None:
        for other in pool:
            if (other is not o and other.code not in used and other.pay_via == o.pay_via
                    and abs((other.created - o.created).total_seconds()) < 6 * 3600):
                other.customer = o.customer
                return other
        return None

    def typo(self, code: str) -> str:
        v = self.r.randint(0, 2)
        if v == 0 and "1" in code[2:]:
            i = code.rindex("1")
            return code[:i] + "I" + code[i + 1:]
        if v == 1 and "0" in code[2:]:
            i = code.rindex("0")
            return code[:i] + "O" + code[i + 1:]
        return f"{code[:7]} {code[7:]}"

    # ------------------------------------------------------------------ special files
    def special_files(self) -> list[tuple[str, bytes]]:
        extra = []
        # A GHTK file sent with a broken date format, then corrected the same day.
        broken_day = self.today - timedelta(days=2)
        key = ("GHTK", broken_day, "")
        if key in self.files:
            good = self.files[key]
            v2 = PartnerFile("GHTK", good.name.replace(".csv", "_v2.csv"), broken_day, list(good.lines))
            good.broken_dates = True
            self.files[("GHTK", broken_day, "_v2")] = v2
        # A bank statement whose date range overlaps the previous day: the last lines appear twice.
        for d in (self.today - timedelta(days=11), self.today - timedelta(days=4)):
            prev = self.files.get(("VCB", d - timedelta(days=1), ""))
            if prev and prev.lines:
                extra_file = self.file("VCB", d, "_bosung")
                for ln in prev.lines[-4:]:
                    extra_file.lines.append(Line("VCB", ln.ref, list(ln.values), ln.truth_orders, "DUPLICATE_STATEMENT"))
        return extra

    # ------------------------------------------------------------------ writers
    def render(self, f: PartnerFile) -> bytes:
        if f.source == "GHN":
            return self.xlsx(["Mã vận đơn", "Mã đơn shop", "Ngày đối soát", "Tiền thu hộ", "Tổng phí", "Tiền trả shop", "Ghi chú"],
                             [ln.values for ln in f.lines])
        if f.source == "SHOPEE":
            return self.xlsx(["Mã đơn hàng", "Ngày hoàn thành thanh toán", "Giá gốc", "Phí cố định", "Phí thanh toán", "Tổng phí",
                              "Tổng tiền đã thanh toán"], [ln.values for ln in f.lines])
        if f.source == "GHTK":
            rows = []
            for ln in f.lines:
                v = list(ln.values)
                if f.broken_dates:
                    dt = datetime.strptime(v[2], "%d-%m-%Y %H:%M")
                    v[2] = f"{dt:%d-%m-%Y} {dt.hour:02d}h{dt.minute:02d}"
                rows.append(v)
            return self.csv(["Mã đơn hàng", "Mã đơn KH", "Ngày", "COD", "Phí ship", "Thực nhận", "Trạng thái"], rows, ",", "utf-8")
        if f.source == "TIKTOK":
            return self.csv(["Order ID", "Settlement time", "Order amount", "Total fees", "Total settlement amount"],
                            [ln.values for ln in f.lines], ",", "utf-8")
        if f.source == "MOMO":
            rows = [[v[1], f"{v[0]:%Y-%m-%d %H:%M:%S}", v[2], v[3], v[4], v[5]] for v in (ln.values for ln in f.lines)]
            return self.csv(["Mã giao dịch", "Thời gian", "Số tiền", "Phí", "Nội dung", "Mã tham chiếu đối tác"], rows, ",", "utf-8")
        # VCB: semicolon separated, dotted amounts, Windows-1258, debit rows mixed in.
        rows, balance = [], 182_000_000 + self.r.randint(0, 9_000_000)
        lines = sorted(f.lines, key=lambda ln: ln.values[0])
        for ln in lines:
            txn, ref, amount, desc = ln.values
            balance += amount
            rows.append([f"{txn:%d/%m/%Y %H:%M:%S}", ref, "", vnd_dots(amount), vnd_dots(balance), desc])
        for i in range(2):
            t = self.at(f.business_date - timedelta(days=1), 23, 50 + i)
            balance -= 11_000
            rows.append([f"{t:%d/%m/%Y %H:%M:%S}", f"FT{t:%y}{t.timetuple().tm_yday:03d}{self.digits(6)}", vnd_dots(11_000), "",
                         vnd_dots(balance), "PHI DICH VU SMS BANKING"])
        buf = io.StringIO()
        w = csv.writer(buf, delimiter=";", lineterminator="\r\n")
        w.writerow(["Ngày giao dịch", "Số tham chiếu", "Số tiền ghi nợ", "Số tiền ghi có", "Số dư", "Mô tả"])
        w.writerows(rows)
        return to_cp1258(buf.getvalue())

    @staticmethod
    def xlsx(headers: list[str], rows: list[list]) -> bytes:
        wb = Workbook()
        ws = wb.active
        ws.title = "DoiSoat"
        ws.append(headers)
        for r in rows:
            ws.append(r)
        out = io.BytesIO()
        wb.save(out)
        return out.getvalue()

    @staticmethod
    def csv(headers: list[str], rows: list[list], delimiter: str, encoding: str) -> bytes:
        buf = io.StringIO()
        w = csv.writer(buf, delimiter=delimiter, lineterminator="\n")
        w.writerow(headers)
        w.writerows(rows)
        return buf.getvalue().encode(encoding)

    # ------------------------------------------------------------------ run
    def run(self, out: Path) -> dict:
        self.make_orders()
        self.make_carrier_lines()
        self.make_marketplace_lines()
        self.make_bank_lines()
        self.special_files()

        (out / "seed").mkdir(parents=True, exist_ok=True)
        (out / "inbox").mkdir(parents=True, exist_ok=True)
        (out / "truth").mkdir(parents=True, exist_ok=True)

        with open(out / "seed" / "orders.csv", "w", newline="", encoding="utf-8") as fh:
            w = csv.writer(fh)
            w.writerow(["code", "channel", "channel_ref", "customer_name", "phone", "amount", "cod_amount", "pay_via",
                        "tracking_code", "status", "remote_area", "created_at", "delivered_at", "note"])
            for o in sorted(self.orders, key=lambda x: x.created):
                w.writerow([o.code, o.channel, o.channel_ref, o.customer, o.phone, o.amount, o.cod, o.pay_via, o.tracking, o.status,
                            str(o.remote_flag).lower(), o.created.isoformat(), o.delivered.isoformat() if o.delivered else "", o.note])

        truth_rows = []
        resent = None
        for (source, d, suffix), f in sorted(self.files.items(), key=lambda kv: (kv[0][1], EXPECTED_HOUR[kv[0][0]], kv[0][2])):
            if not f.lines:
                continue
            data = self.render(f)
            (out / "inbox" / f.name).write_bytes(data)
            if source == "VCB" and d == self.today - timedelta(days=6) and not suffix:
                resent = (f.name.replace(".csv", "_gui_lai.csv"), data)
            for ln in f.lines:
                truth_rows.append([source, f.name, ln.ref, ";".join(ln.truth_orders), ln.anomaly])
        if resent:
            (out / "inbox" / resent[0]).write_bytes(resent[1])  # identical bytes -> rejected by checksum

        with open(out / "truth" / "truth.csv", "w", newline="", encoding="utf-8") as fh:
            w = csv.writer(fh)
            w.writerow(["source", "file", "external_ref", "true_orders", "anomaly"])
            w.writerows(truth_rows)

        anomalies: dict[str, int] = {}
        for row in truth_rows:
            anomalies[row[4]] = anomalies.get(row[4], 0) + 1
        return {"orders": len(self.orders), "files": sum(1 for f in self.files.values() if f.lines) + (1 if resent else 0),
                "lines": len(truth_rows), "anomalies": anomalies}


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--out", default="/data")
    p.add_argument("--today", help="anchor date YYYY-MM-DD; defaults to today in Asia/Ho_Chi_Minh")
    p.add_argument("--days", type=int, default=30)
    p.add_argument("--seed", type=int, default=20261022)
    p.add_argument("--force", action="store_true", help="regenerate even if data already exists")
    args = p.parse_args()

    out = Path(args.out)
    marker = out / ".seeded"
    if marker.exists() and not args.force:
        print(f"Sample data already present in {out} ({marker.read_text().strip()}); use --force to regenerate.")
        return
    today = date.fromisoformat(args.today) if args.today else datetime.now(TZ).date()
    stats = Generator(today, args.days, args.seed).run(out)
    marker.write_text(f"generated for {today} with seed {args.seed}\n")
    print(f"Generated {stats['orders']} orders, {stats['files']} files, {stats['lines']} payment lines into {out}")
    for k, v in sorted(stats["anomalies"].items(), key=lambda kv: -kv[1]):
        print(f"  {k:<22} {v}")


if __name__ == "__main__":
    main()
