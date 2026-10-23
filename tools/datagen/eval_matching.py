"""Scores ReconHub's automatic matching against the ground truth the generator wrote.

Reads /data/truth/truth.csv and the database, then reports:

* precision of automatic matches (EXACT, AMOUNT_DATE, MANY_TO_ONE): of the lines the rules matched on their own,
  how many went to exactly the right orders. This is the number that must stay at 100%: a wrong automatic match
  moves money to the wrong customer and nobody looks at it again.
* automation rate: share of all lines matched correctly without a person, overall and per source.
* where each kind of planted anomaly ended up (matched, which exception type, still new).

Usage: python eval_matching.py --truth /data/truth/truth.csv --db postgresql://reconhub:reconhub@localhost:5432/reconhub
"""
from __future__ import annotations

import argparse
import csv
import sys
from collections import Counter, defaultdict

import psycopg

AUTOMATIC = {"EXACT", "AMOUNT_DATE", "MANY_TO_ONE"}

LINES_SQL = """
select f.file_name, l.external_ref, l.status, g.method,
       (select string_agg(distinct i.order_code, ';' order by i.order_code)
          from match_items i where i.group_id = g.id and i.order_code is not null) as orders,
       (select string_agg(distinct e.type, ',')
          from exception_payments ep join recon_exceptions e on e.id = ep.exception_id
         where ep.payment_line_id = l.id) as exception_types
from payment_lines l
join import_files f on f.id = l.file_id
left join match_groups g on g.id = l.match_group_id
"""


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--truth", default="/data/truth/truth.csv")
    p.add_argument("--db", default="postgresql://reconhub:reconhub@localhost:5432/reconhub")
    p.add_argument("--min-precision", type=float, default=1.0, help="exit with status 1 below this precision")
    args = p.parse_args()

    with open(args.truth, newline="", encoding="utf-8") as fh:
        truth = {(r["file"], r["external_ref"]): r for r in csv.DictReader(fh)}

    with psycopg.connect(args.db) as conn:
        rows = conn.execute(LINES_SQL).fetchall()

    auto = correct_auto = 0
    correct_by_source: Counter = Counter()
    wrong: list[tuple] = []
    outcome_by_anomaly: dict[str, Counter] = defaultdict(Counter)
    seen = set()
    for file_name, ref, status, method, orders, exc_types in rows:
        t = truth.get((file_name, ref))
        if t is None:
            continue
        seen.add((file_name, ref))
        expected = ";".join(sorted(o for o in t["true_orders"].split(";") if o))
        if method in AUTOMATIC:
            auto += 1
            if orders == expected:
                correct_auto += 1
                correct_by_source[t["source"]] += 1
            else:
                wrong.append((file_name, ref, t["anomaly"], method, orders, expected))
        if status == "MATCHED":
            label = f"matched {method}" + (" (wrong orders)" if orders != expected else "")
        elif status == "EXCEPTION" or exc_types:
            label = f"exception {exc_types}"
        else:
            label = status.lower()
        if exc_types and status == "MATCHED":
            label += f" + {exc_types}"
        outcome_by_anomaly[t["anomaly"]][label] += 1

    for key, t in truth.items():
        if key not in seen:
            outcome_by_anomaly[t["anomaly"]]["not loaded (row error or failed file)"] += 1

    with_orders = sum(1 for t in truth.values() if t["true_orders"])
    precision = correct_auto / auto if auto else 1.0
    print(f"Lines in truth:            {len(truth)}")
    print(f"Lines loaded:              {len(seen)}")
    print(f"Automatic matches:         {auto}")
    print(f"  correct:                 {correct_auto}")
    print(f"  precision:               {precision:.2%}")
    print(f"Automation rate:           {correct_auto / with_orders:.2%} of {with_orders} lines that belong to orders")
    with_orders_by_source = Counter(t["source"] for t in truth.values() if t["true_orders"])
    for source, n in sorted(with_orders_by_source.items()):
        print(f"  {source:<23} {correct_by_source[source] / n:.2%} of {n}")
    print()
    print("Outcome by planted anomaly:")
    for anomaly, outcomes in sorted(outcome_by_anomaly.items(), key=lambda kv: -sum(kv[1].values())):
        total = sum(outcomes.values())
        parts = ", ".join(f"{label} {n}" for label, n in outcomes.most_common())
        print(f"  {anomaly:<22} {total:>5}  {parts}")
    if wrong:
        print()
        print("Wrong automatic matches:")
        for w in wrong[:30]:
            print("  file=%s ref=%s anomaly=%s method=%s got=%s expected=%s" % w)
    return 0 if precision >= args.min_precision else 1


if __name__ == "__main__":
    sys.exit(main())
