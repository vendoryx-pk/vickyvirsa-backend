package com.vendoryx.model;

import java.util.List;

public class User {
    private int userId;
    private String username;
    private String fullName;
    private String email;
    private String role;
    private List<PageAccess> allowedPages;

    public User() {}

    public User(int userId, String username, String fullName, String email, String role) {
        this.userId = userId;
        this.username = username;
        this.fullName = fullName;
        this.email = email;
        this.role = role;
    }

    public int getUserId() {
        return userId;
    }

    public void setUserId(int userId) {
        this.userId = userId;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public List<PageAccess> getAllowedPages() {
        return allowedPages;
    }

    public void setAllowedPages(List<PageAccess> allowedPages) {
        this.allowedPages = allowedPages;
    }
}
