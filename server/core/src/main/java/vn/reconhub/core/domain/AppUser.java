package vn.reconhub.core.domain;

import jakarta.persistence.*;
import vn.reconhub.core.domain.Enums.Role;

@Entity
@Table(name = "app_users")
public class AppUser {
    @Id
    private String id;
    private String username;
    private String passwordHash;
    private String fullName;
    @Enumerated(EnumType.STRING)
    private Role role;
    private String title;
    private String email;

    protected AppUser() {}

    public String getId() { return id; }
    public String getUsername() { return username; }
    public String getPasswordHash() { return passwordHash; }
    public String getFullName() { return fullName; }
    public Role getRole() { return role; }
    public String getTitle() { return title; }
    public String getEmail() { return email; }
}
