package com.yupi.springbootinit.model.vo;

import lombok.Data;

import java.util.List;

/**
 * 质量洞察返回结构
 */
public class QualityVO {

    @Data
    public static class YieldPoint {
        private String date;
        private Double yield;
        private Double target;
    }

    @Data
    public static class OeePoint {
        private String date;
        private Double oee;
        private Double availability;
        private Double performance;
        private Double quality;
    }

    @Data
    public static class DefectItem {
        private String defectCode;
        private String defectName;
        private Integer qty;
        private Double pct;
    }

    @Data
    public static class SpcResult {
        private List<SpcPoint> xBar;
        private List<SpcPoint> rPoints;
        private Double xBarUcl;
        private Double xBarLcl;
        private Double xBarCenter;
        private Double rUcl;
        private Double rLcl;
        private Double rCenter;
        private List<OutOfControlPoint> outOfControl;
    }

    @Data
    public static class SpcPoint {
        private String date;
        private Double value;
    }

    @Data
    public static class OutOfControlPoint {
        private String date;
        private Integer index;
        private String type;
    }

    @Data
    public static class ProcessDefectMatrix {
        private List<String> processes;
        private List<String> defects;
        private List<List<Integer>> matrix;
    }

    @Data
    public static class Summary {
        private Double yield;
        private Double oee;
        private Integer defectTotal;
        private Double yieldDelta;
        private Double oeeDelta;
    }
}
