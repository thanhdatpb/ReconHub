<%@ include file="_header.jspf" %>
<h2>Đăng nhập</h2>
<s:form action="doLogin" method="post" cssClass="box narrow">
  <table class="form">
    <tr><td>Tài khoản</td><td><s:textfield name="username" autocomplete="username"/></td></tr>
    <tr><td>Mật khẩu</td><td><s:password name="password" autocomplete="current-password"/></td></tr>
    <tr><td></td><td><s:submit value="Đăng nhập"/></td></tr>
  </table>
  <p class="hint">Tài khoản demo: sale / sale123</p>
</s:form>
<%@ include file="_footer.jspf" %>
