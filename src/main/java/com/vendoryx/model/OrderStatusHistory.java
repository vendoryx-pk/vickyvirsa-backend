package com.vendoryx.model;

import java.sql.Timestamp;

public class OrderStatusHistory {
    private int historyId;
    private int orderId;
    private String oldStatus;
    private String newStatus;
    private Integer changedByUserId;
    private String comments;
    private Timestamp changedAt;

    public OrderStatusHistory() {}

    public int getHistoryId() {
        return historyId;
    }

    public void setHistoryId(int historyId) {
        this.historyId = historyId;
    }

    public int getOrderId() {
        return orderId;
    }

    public void setOrderId(int orderId) {
        this.orderId = orderId;
    }

    public String getOldStatus() {
        return oldStatus;
    }

    public void setOldStatus(String oldStatus) {
        this.oldStatus = oldStatus;
    }

    public String getNewStatus() {
        return newStatus;
    }

    public void setNewStatus(String newStatus) {
        this.newStatus = newStatus;
    }

    public Integer getChangedByUserId() {
        return changedByUserId;
    }

    public void setChangedByUserId(Integer changedByUserId) {
        this.changedByUserId = changedByUserId;
    }

    public String getComments() {
        return comments;
    }

    public void setComments(String comments) {
        this.comments = comments;
    }

    public Timestamp getChangedAt() {
        return changedAt;
    }

    public void setChangedAt(Timestamp changedAt) {
        this.changedAt = changedAt;
    }
}
