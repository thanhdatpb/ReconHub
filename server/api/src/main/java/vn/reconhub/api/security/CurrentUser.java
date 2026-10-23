package vn.reconhub.api.security;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import vn.reconhub.core.domain.AppUser;
import vn.reconhub.core.repo.AppUserRepository;
import vn.reconhub.core.workflow.WorkflowException;

/** The signed-in user, looked up from the token's subject (the user id). */
@Component
public class CurrentUser {
    private final AppUserRepository users;

    public CurrentUser(AppUserRepository users) {
        this.users = users;
    }

    public AppUser get() {
        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (!(principal instanceof Jwt jwt)) throw new WorkflowException(WorkflowException.Kind.FORBIDDEN, "Chưa đăng nhập");
        return users.findById(jwt.getSubject())
                .orElseThrow(() -> new WorkflowException(WorkflowException.Kind.FORBIDDEN, "Tài khoản không còn tồn tại"));
    }
}
