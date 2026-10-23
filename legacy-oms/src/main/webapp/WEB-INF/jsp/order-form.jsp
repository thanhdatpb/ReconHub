<%@ include file="_header.jspf" %>
<h2>Tạo đơn mới</h2>
<s:form action="createOrder" method="post" cssClass="box">
  <table class="form">
    <tr><td>Kênh bán</td><td><s:select name="order.channel" list="channels"/></td></tr>
    <tr><td>Mã đơn trên sàn</td><td><s:textfield name="order.channelRef"/></td></tr>
    <tr><td>Tên khách</td><td><s:textfield name="order.customerName"/> <s:fielderror fieldName="order.customerName"/></td></tr>
    <tr><td>Số điện thoại</td><td><s:textfield name="order.phone"/></td></tr>
    <tr><td>Giá trị đơn (₫)</td><td><s:textfield name="order.amount"/> <s:fielderror fieldName="order.amount"/></td></tr>
    <tr><td>Thanh toán qua</td><td><s:select name="order.payVia" list="payVias"/> <span class="hint">GHN/GHTK là thu hộ COD</span></td></tr>
    <tr><td>Mã vận đơn</td><td><s:textfield name="order.trackingCode"/></td></tr>
    <tr><td>Địa chỉ vùng xa</td><td><s:checkbox name="order.remoteArea"/></td></tr>
    <tr><td>Ghi chú</td><td><s:textfield name="order.note" size="50"/></td></tr>
    <tr><td></td><td><s:submit value="Tạo đơn"/></td></tr>
  </table>
</s:form>
<%@ include file="_footer.jspf" %>
