"""核心生成逻辑：按 docs/data-model.md 口径生成制造质量数据并写库"""
import datetime
import random

from config import (LINES, SEASON_FACTOR, DEFECT_TYPES, DEFECT_PROCESS_MATRIX,
                    PROCESS_DEFECT_WEIGHT, COUNTING_PARAM_PREFIXES)
from db import get_connection

YIELD_BASE = 0.965          # 良率基线（口径：>=95%）
AVAILABILITY_RANGE = (0.90, 0.98)
PERFORMANCE_RANGE = (0.88, 0.96)
IDEAL_CYCLE_MIN = 0.75      # 理论节拍（分钟/件）——产能 = available_time / ideal_cycle
SPC_SUBGROUP_SIZE = 5
SPC_SIGMA = 0.020           # 参数相对波动 sigma（占 target 比例）

# 工序 SPC 规格（仅计量型工序；计数型参数工序不适用 X̄-R 图，不入表）
# 键 = (产线, 工序) 组合，避免跨线同名工序覆盖
SPC_SPEC = {}
for _line in LINES:
    for _pname, _param, _target, _usl, _lsl in _line["processes"]:
        if _param.startswith(COUNTING_PARAM_PREFIXES):
            continue  # 计数型（缺陷数/件数），不适用计量控制图
        SPC_SPEC[(_line["name"], _pname)] = {"param": _param, "target": _target,
                                             "usl": _usl, "lsl": _lsl}


def _month_factor(date):
    return SEASON_FACTOR[date.month - 1]


def clamp_yield(y):
    """良率钳位到口径区间 [94.5%, 99.5%]（目标 95%，允许日度波动）"""
    return min(0.995, max(0.945, y))


def generate_line_meta(conn, rng):
    """产线与工序（含 SPC 参数规格限），返回 line_id/process_id 映射"""
    line_ids, process_ids = {}, {}
    with conn.cursor() as cursor:
        for line in LINES:
            cursor.execute("INSERT INTO prod_line (name, plant) VALUES (%s, %s)",
                           (line["name"], line["plant"]))
            line_id = cursor.lastrowid
            line_ids[line["name"]] = line_id
            for seq, (pname, pparam, target, usl, lsl) in enumerate(line["processes"], start=1):
                cursor.execute(
                    "INSERT INTO prod_process (line_id, name, seq, param_name, param_target, param_usl, param_lsl) "
                    "VALUES (%s, %s, %s, %s, %s, %s, %s)",
                    (line_id, pname, seq, pparam, target, usl, lsl))
                process_ids[(line["name"], pname)] = cursor.lastrowid
    conn.commit()
    return line_ids, process_ids


def generate_daily_metrics(conn, rng, start, end, line_ids):
    """每日指标：产量由产能推导（available/ideal_cycle × 可用率 × 性能），
    保证后端重算的 OEE 三分量落在口径区间；季节因子作用于计划量（需求侧）"""
    days = (end - start).days + 1
    with conn.cursor() as cursor:
        for i in range(days):
            day = start + datetime.timedelta(days=i)
            factor = _month_factor(day)
            for line_name, line_id in line_ids.items():
                available_time = 24 * 60 if day.weekday() < 6 else 16 * 60  # 周日短班（weekday()==6 为周日）
                capacity = available_time / IDEAL_CYCLE_MIN                  # 理论产能（件）
                availability = rng.uniform(*AVAILABILITY_RANGE)
                performance = rng.uniform(*PERFORMANCE_RANGE)
                output_qty = int(capacity * availability * performance)
                plan_qty = int(output_qty * factor * rng.uniform(1.0, 1.03))  # 计划=需求侧（含季节），低产月可低于实际
                yield_rate = clamp_yield(YIELD_BASE + rng.gauss(0, 0.008))
                good_qty = int(output_qty * yield_rate)
                defect_qty = output_qty - good_qty
                run_time = int(available_time * availability)
                cursor.execute(
                    "INSERT INTO prod_daily_metrics (stat_date, line_id, plan_qty, output_qty, good_qty, "
                    "defect_qty, available_time_min, run_time_min, ideal_cycle_min, actual_cycle_min) "
                    "VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s)",
                    (day, line_id, plan_qty, output_qty, good_qty, defect_qty,
                     available_time, run_time, IDEAL_CYCLE_MIN, IDEAL_CYCLE_MIN / performance))
    conn.commit()


def _allocate(total, weights):
    """最大余数法：把 total 按权重分配为整数，保证 sum(结果) == total（消除 int 截断偏差）"""
    if total <= 0:
        return [0] * len(weights)
    raw = [total * w for w in weights]
    result = [int(x) for x in raw]
    remainder = total - sum(result)
    if remainder > 0:
        idxs = sorted(range(len(raw)), key=lambda i: raw[i] - result[i], reverse=True)
        for i in idxs[:remainder]:
            result[i] += 1
    return result


def generate_defects(conn, rng, start, end, line_ids, process_ids):
    """缺陷记录：与日指标 defect_qty 严格同口径（两级最大余数法分摊，qty 之和 == defect_qty），
    按 缺陷-工序权重矩阵 注入相关性"""
    with conn.cursor() as cur:
        cur.execute(
            "SELECT stat_date, line_id, defect_qty FROM prod_daily_metrics "
            "WHERE stat_date BETWEEN %s AND %s", (start, end))
        defect_by_day_line = {(row[0], row[1]): row[2] for row in cur.fetchall()}
    with conn.cursor() as cursor:
        for (day, line_id), defect_qty in defect_by_day_line.items():
            line_name = next(n for n, i in line_ids.items() if i == line_id)
            processes = [ln["processes"] for ln in LINES if ln["name"] == line_name][0]
            proc_weights = PROCESS_DEFECT_WEIGHT[line_name]
            proc_quotas = _allocate(defect_qty, proc_weights)          # 第 1 级：分到工序
            for proc_tuple, proc_qty in zip(processes, proc_quotas):
                pname = proc_tuple[0]
                if proc_qty <= 0:
                    continue
                process_id = process_ids[(line_name, pname)]
                weights = DEFECT_PROCESS_MATRIX[(line_name, pname)]
                type_quotas = _allocate(proc_qty, weights)             # 第 2 级：分到缺陷类型
                for (code, name, severity), qty in zip(DEFECT_TYPES, type_quotas):
                    if qty <= 0:
                        continue
                    cursor.execute(
                        "INSERT INTO prod_defect_record (stat_date, line_id, process_id, defect_code, "
                        "defect_name, qty, severity) VALUES (%s, %s, %s, %s, %s, %s, %s)",
                        (day, line_id, process_id, code, name, qty, severity))
    conn.commit()


def generate_spc(conn, rng, start, end, process_ids):
    """SPC 样本：每计量工序每天 1 个子组（n=5），围绕 param_target 波动；
    ~2% 子组整体偏移 +8σ 制造可检出出界点（X̄ 控制限 ≈ ±1.34σ，8σ >> 1.34σ 可靠越限）"""
    days = (end - start).days + 1
    with conn.cursor() as cursor:
        for i in range(days):
            day = start + datetime.timedelta(days=i)
            for (line_name, pname), process_id in process_ids.items():
                spec = SPC_SPEC.get((line_name, pname))
                if spec is None:
                    continue  # 计数型质检工序不产 SPC 样本
                target = spec["target"]
                sigma = max(0.01, target * SPC_SIGMA)
                shift = 8.0 * sigma if rng.random() < 0.02 else 0.0
                for sample_no in range(1, SPC_SUBGROUP_SIZE + 1):
                    value = round(target + rng.gauss(0, sigma) + shift, 4)
                    cursor.execute(
                        "INSERT INTO prod_spc_sample (stat_date, process_id, sample_no, value) "
                        "VALUES (%s, %s, %s, %s)",
                        (day, process_id, sample_no, value))
    conn.commit()
