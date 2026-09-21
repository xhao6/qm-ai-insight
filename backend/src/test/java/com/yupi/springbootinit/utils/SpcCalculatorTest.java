package com.yupi.springbootinit.utils;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class SpcCalculatorTest {

    @Test
    public void testControlLimitsWithTrendData() {
        // n=5：A2=0.577, D3=0, D4=2.114
        // 5 个子组，值 = 10 + 组号*2，使每子组均值递增（10.8→18.8）、极差恒为 2
        // 控制限 = 14.8 ± 0.577×2 = [13.646, 15.954] → 10.8/12.8 低于 LCL、16.8/18.8 高于 UCL
        // 共 4 个出界点（教科书式趋势检出案例）
        List<List<Double>> subgroups = Arrays.asList(
                Arrays.asList(10.0, 12.0, 10.0, 12.0, 10.0), // mean 10.8, range 2
                Arrays.asList(12.0, 14.0, 12.0, 14.0, 12.0), // mean 12.8, range 2
                Arrays.asList(14.0, 16.0, 14.0, 16.0, 14.0), // mean 14.8, range 2
                Arrays.asList(16.0, 18.0, 16.0, 18.0, 16.0), // mean 16.8, range 2
                Arrays.asList(18.0, 20.0, 18.0, 20.0, 18.0)); // mean 18.8, range 2
        SpcCalculator.Result r = SpcCalculator.compute(subgroups, 5);
        assertEquals(14.8, r.getXBarCenter(), 1e-9);   // X̄̄ = (10.8+12.8+14.8+16.8+18.8)/5
        assertEquals(2.0, r.getRBar(), 1e-9);          // R̄ = 2
        assertEquals(14.8 + 0.577 * 2.0, r.getXBarUcl(), 1e-9);
        assertEquals(14.8 - 0.577 * 2.0, r.getXBarLcl(), 1e-9);
        assertEquals(2.114 * 2.0, r.getRUcl(), 1e-9);
        assertEquals(0.0, r.getRLcl(), 1e-9);
        // 趋势数据必然检出 4 个 X̄ 出界点（index 0/1 低于 LCL，3/4 高于 UCL）
        assertEquals(4, r.getOutOfControl().size());
        assertEquals(0, r.getOutOfControl().get(0).getIndex());
        assertEquals("xbar", r.getOutOfControl().get(0).getType());
    }

    @Test
    public void testOutOfControlDetection() {
        // 极差恒为 0 → R̄=0 → X̄ 控制限 = X̄̄ = 23.33（无带宽）→ 三个子组全部出界
        List<List<Double>> subgroups = Arrays.asList(
                Arrays.asList(10.0, 10.0, 10.0, 10.0, 10.0), // mean 10 -> 出界（< 23.33）
                Arrays.asList(10.0, 10.0, 10.0, 10.0, 10.0), // mean 10 -> 出界
                Arrays.asList(50.0, 50.0, 50.0, 50.0, 50.0)); // mean 50 -> 出界（> 23.33）
        SpcCalculator.Result r = SpcCalculator.compute(subgroups, 5);
        assertEquals(3, r.getOutOfControl().size());
        assertEquals(0, r.getOutOfControl().get(0).getIndex());
        assertEquals("xbar", r.getOutOfControl().get(0).getType());
    }
}
