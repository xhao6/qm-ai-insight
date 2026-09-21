"""口径校验：良率/OEE/SPC 公式/相关性/季节因子（纯计算，不连库）"""
import random
import statistics

import generator
from config import (SEASON_FACTOR, DEFECT_PROCESS_MATRIX, PROCESS_DEFECT_WEIGHT,
                    LINES, COUNTING_PARAM_PREFIXES)


def test_clamp_yield_bounds():
    assert generator.clamp_yield(0.90) == 0.945
    assert generator.clamp_yield(0.99) == 0.99
    assert generator.clamp_yield(0.999) == 0.995


def test_yield_rate_within_baseline():
    rng = random.Random(1)
    rates = [generator.clamp_yield(generator.YIELD_BASE + rng.gauss(0, 0.008))
             for _ in range(200)]
    assert min(rates) >= 0.945
    assert max(rates) <= 0.995
    # 用中位数而非均值，避免钳位偏置造成 flaky
    assert statistics.median(rates) >= 0.96


def test_month_factor_length_and_range():
    assert len(SEASON_FACTOR) == 12
    assert min(SEASON_FACTOR) >= 0.85
    assert max(SEASON_FACTOR) <= 1.10


def test_defect_matrix_rows_sum_to_one():
    for (line, process), weights in DEFECT_PROCESS_MATRIX.items():
        assert abs(sum(weights) - 1.0) < 1e-9, (line, process)


def test_defect_matrix_covers_all_processes():
    """矩阵必须覆盖每条线的全部工序（组合键，防跨线同名覆盖）"""
    expected = {(ln["name"], proc[0]) for ln in LINES for proc in ln["processes"]}
    assert expected <= set(DEFECT_PROCESS_MATRIX.keys()), \
        expected - set(DEFECT_PROCESS_MATRIX.keys())


def test_process_defect_weight_rows_sum_to_one():
    for line in LINES:
        weights = PROCESS_DEFECT_WEIGHT[line["name"]]
        assert len(weights) == len(line["processes"]), line["name"]
        assert abs(sum(weights) - 1.0) < 1e-9, line["name"]


def test_spc_spec_covers_only_variable_processes():
    """SPC_SPEC 覆盖全部计量工序（与 config.COUNTING_PARAM_PREFIXES 同源判定）"""
    all_names = {(ln["name"], proc[0]) for ln in LINES for proc in ln["processes"]}
    variable = {(ln["name"], proc[0]) for ln in LINES for proc in ln["processes"]
                if not proc[1].startswith(COUNTING_PARAM_PREFIXES)}
    assert set(generator.SPC_SPEC.keys()) == variable
    for spec in generator.SPC_SPEC.values():
        assert spec["lsl"] < spec["target"] < spec["usl"]


def test_allocate_invariant():
    """最大余数法分配：任意权重下 sum(结果) == total（缺陷两口径一致性的根基）"""
    assert generator._allocate(58, [0.35, 0.30, 0.20, 0.15]) == [20, 17, 12, 9]
    assert generator._allocate(0, [0.5, 0.5]) == [0, 0]
    assert sum(generator._allocate(7, [0.33, 0.33, 0.34])) == 7
    assert sum(generator._allocate(100, [0.25] * 4)) == 100


def test_spc_subgroup_size_constant():
    assert generator.SPC_SUBGROUP_SIZE == 5
