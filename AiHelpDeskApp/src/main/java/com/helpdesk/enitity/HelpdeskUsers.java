package com.helpdesk.enitity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "Helpdesk_user")
public class HelpdeskUsers {

	@Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long userId;

    @Column(
        name = "user_name",
        unique = true,
        nullable = false,
        length = 30
    )
    private String username;

    @Enumerated(EnumType.STRING)
    @Column(name = "user_dept", length = 30)
    private DepartmentNames dept;

    public HelpdeskUsers() {
    }

    public HelpdeskUsers(String username, DepartmentNames dept) {
        this.username = username;
        this.dept = dept;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public DepartmentNames getDept() {
        return dept;
    }

    public void setDept(DepartmentNames dept) {
        this.dept = dept;
    }

}
