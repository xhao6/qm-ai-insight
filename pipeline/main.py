"""CLI 入口：python3 main.py --days 90 --seed 42 --reset"""
import argparse
import datetime
import random

import generator
from db import get_connection, init_db

RESET_TABLES = ["prod_spc_sample", "prod_defect_record", "prod_daily_metrics",
                "prod_process", "prod_line"]


def main():
    parser = argparse.ArgumentParser(description="制造质量模拟数据生成器")
    parser.add_argument("--days", type=int, default=90)
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--reset", action="store_true", help="清空 prod_* 表后重新生成")
    parser.add_argument("--end", type=str, default=None, help="结束日期 YYYY-MM-DD，默认今天")
    args = parser.parse_args()

    rng = random.Random(args.seed)
    conn = get_connection()
    init_db(conn)
    if args.reset:
        with conn.cursor() as cursor:
            for t in RESET_TABLES:
                cursor.execute("TRUNCATE TABLE %s" % t)
        conn.commit()
    else:
        with conn.cursor() as cursor:
            cursor.execute("SELECT COUNT(*) FROM prod_daily_metrics")
            if cursor.fetchone()[0] > 0:
                print("警告：库中已有数据，继续将重复插入（建议 --reset 重新生成）")
    end = datetime.date.today() if not args.end else datetime.date.fromisoformat(args.end)
    start = end - datetime.timedelta(days=args.days - 1)

    line_ids, process_ids = generator.generate_line_meta(conn, rng)
    generator.generate_daily_metrics(conn, rng, start, end, line_ids)
    generator.generate_defects(conn, rng, start, end, line_ids, process_ids)
    generator.generate_spc(conn, rng, start, end, process_ids)

    with conn.cursor() as cursor:
        cursor.execute("SELECT COUNT(*) FROM prod_daily_metrics")
        metrics = cursor.fetchone()[0]
        cursor.execute("SELECT COUNT(*) FROM prod_defect_record")
        defects = cursor.fetchone()[0]
        cursor.execute("SELECT COUNT(*) FROM prod_spc_sample")
        spc = cursor.fetchone()[0]
    conn.close()
    print(f"generated: metrics={metrics} defects={defects} spc_samples={spc} "
          f"(days={args.days} seed={args.seed})")


if __name__ == "__main__":
    main()
