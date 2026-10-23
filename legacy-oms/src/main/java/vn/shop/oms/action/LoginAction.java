package vn.shop.oms.action;

import com.opensymphony.xwork2.ActionContext;
import com.opensymphony.xwork2.ActionSupport;
import vn.shop.oms.db.Db;
import vn.shop.oms.web.LoginInterceptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

public class LoginAction extends ActionSupport {
    private String username;
    private String password;

    /** Shows the form. */
    public String form() {
        return INPUT;
    }

    @Override
    public String execute() {
        Map<String, Object> staff = Db.tx(m -> m.staff(username == null ? "" : username.trim()));
        if (staff == null || !sha256(password).equals(staff.get("password_sha256"))) {
            addActionError("Sai tên đăng nhập hoặc mật khẩu");
            return INPUT;
        }
        ActionContext.getContext().getSession().put(LoginInterceptor.USER, staff.get("full_name"));
        return SUCCESS;
    }

    public String logout() {
        ActionContext.getContext().getSession().remove(LoginInterceptor.USER);
        return "login";
    }

    private static String sha256(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest((s == null ? "" : s).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public void setPassword(String password) { this.password = password; }
}
