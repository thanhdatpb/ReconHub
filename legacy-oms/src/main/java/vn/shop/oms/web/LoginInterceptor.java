package vn.shop.oms.web;

import com.opensymphony.xwork2.ActionInvocation;
import com.opensymphony.xwork2.interceptor.AbstractInterceptor;

import java.util.Map;

/** Sends anyone without a session to the login page. */
public class LoginInterceptor extends AbstractInterceptor {
    public static final String USER = "omsUser";

    @Override
    public String intercept(ActionInvocation invocation) throws Exception {
        Map<String, Object> session = invocation.getInvocationContext().getSession();
        if (session == null || session.get(USER) == null) return "login";
        return invocation.invoke();
    }
}
