package com.yupi.springbootinit.utils;

import java.util.ArrayList;
import java.util.List;

/**
 * SPC X̄-R 控制图计算（口径见 docs/data-model.md）
 * 子组大小 n 支持 2-5，常数表：A2/D3/D4
 */
public class SpcCalculator {

    private static final double[] A2 = {0, 0, 1.880, 1.023, 0.729, 0.577};
    private static final double[] D3 = {0, 0, 0, 0, 0, 0};
    private static final double[] D4 = {0, 0, 3.267, 2.574, 2.282, 2.114};

    public static Result compute(List<List<Double>> subgroups, int subgroupSize) {
        if (subgroups == null || subgroups.isEmpty()) {
            throw new IllegalArgumentException("subgroups must not be empty");
        }
        if (subgroupSize < 2 || subgroupSize > 5) {
            throw new IllegalArgumentException("subgroupSize must be 2..5");
        }
        List<Double> xBars = new ArrayList<>();
        List<Double> ranges = new ArrayList<>();
        for (List<Double> group : subgroups) {
            double sum = 0;
            double min = Double.MAX_VALUE;
            double max = -Double.MAX_VALUE;
            for (Double v : group) {
                sum += v;
                min = Math.min(min, v);
                max = Math.max(max, v);
            }
            xBars.add(sum / group.size());
            ranges.add(max - min);
        }
        double xBarCenter = xBars.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double rBar = ranges.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double xBarUcl = xBarCenter + A2[subgroupSize] * rBar;
        double xBarLcl = xBarCenter - A2[subgroupSize] * rBar;
        double rUcl = D4[subgroupSize] * rBar;
        double rLcl = D3[subgroupSize] * rBar;

        List<OutOfControl> out = new ArrayList<>();
        for (int i = 0; i < xBars.size(); i++) {
            if (xBars.get(i) > xBarUcl || xBars.get(i) < xBarLcl) {
                out.add(new OutOfControl(i, "xbar"));
            }
            if (ranges.get(i) > rUcl || ranges.get(i) < rLcl) {
                out.add(new OutOfControl(i, "range"));
            }
        }
        return new Result(xBarCenter, rBar, xBarUcl, xBarLcl, rUcl, rLcl, out);
    }

    public static class Result {
        private final double xBarCenter, rBar, xBarUcl, xBarLcl, rUcl, rLcl;
        private final List<OutOfControl> outOfControl;

        public Result(double xBarCenter, double rBar, double xBarUcl, double xBarLcl,
                      double rUcl, double rLcl, List<OutOfControl> outOfControl) {
            this.xBarCenter = xBarCenter;
            this.rBar = rBar;
            this.xBarUcl = xBarUcl;
            this.xBarLcl = xBarLcl;
            this.rUcl = rUcl;
            this.rLcl = rLcl;
            this.outOfControl = outOfControl;
        }

        public double getXBarCenter() { return xBarCenter; }
        public double getRBar() { return rBar; }
        public double getXBarUcl() { return xBarUcl; }
        public double getXBarLcl() { return xBarLcl; }
        public double getRUcl() { return rUcl; }
        public double getRLcl() { return rLcl; }
        public List<OutOfControl> getOutOfControl() { return outOfControl; }
    }

    public static class OutOfControl {
        private final int index;
        private final String type;

        public OutOfControl(int index, String type) {
            this.index = index;
            this.type = type;
        }

        public int getIndex() { return index; }
        public String getType() { return type; }
    }
}
