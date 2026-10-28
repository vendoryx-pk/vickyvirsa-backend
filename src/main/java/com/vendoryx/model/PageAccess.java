package com.vendoryx.model;

public class PageAccess {
    private String pageName;
    private String routePath;
    private String icon;

    public PageAccess(String pageName, String routePath, String icon) {
        this.pageName = pageName;
        this.routePath = routePath;
        this.icon = icon;
    }

    public String getPageName() {
        return pageName;
    }

    public String getRoutePath() {
        return routePath;
    }

    public String getIcon() {
        return icon;
    }
}
