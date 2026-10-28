package com.vendoryx.model;

public class VisitDay {
    private int dayId;
    private String dayName;
    private int dayOrder;

    public VisitDay() {}

    public VisitDay(int dayId, String dayName, int dayOrder) {
        this.dayId = dayId;
        this.dayName = dayName;
        this.dayOrder = dayOrder;
    }

    public int getDayId() {
        return dayId;
    }

    public void setDayId(int dayId) {
        this.dayId = dayId;
    }

    public String getDayName() {
        return dayName;
    }

    public void setDayName(String dayName) {
        this.dayName = dayName;
    }

    public int getDayOrder() {
        return dayOrder;
    }

    public void setDayOrder(int dayOrder) {
        this.dayOrder = dayOrder;
    }
}
