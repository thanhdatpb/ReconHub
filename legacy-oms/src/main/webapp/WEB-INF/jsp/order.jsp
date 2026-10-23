<%@ include file="_header.jspf" %>
<h2>Đơn <s:property value="order.code"/> <span class="st st-<s:property value='order.status'/>"><s:property value="order.status"/></span></h2>
<table class="detail box">
  <tr><th>Kênh bán</th><td><s:property value="order.channel"/> <s:property value="order.channelRef"/></td></tr>
  <tr><th>Khách hàng</th><td><s:property value="order.customerName"/> · <s:property value="order.phone"/></td></tr>
  <tr><th>Giá trị đơn</th><td><s:text name="format.money"><s:param value="order.amount"/></s:text></td></tr>
  <tr><th>Thu hộ (COD)</th><td><s:text name="format.money"><s:param value="order.codAmount"/></s:text></td></tr>
  <tr><th>Thanh toán qua</th><td><s:property value="order.payVia"/></td></tr>
  <tr><th>Mã vận đơn</th><td><s:property value="order.trackingCode"/></td></tr>
  <tr><th>Vùng xa</th><td><s:if test="order.remoteArea">Có</s:if><s:else>Không</s:else></td></tr>
  <tr><th>Ngày tạo</th><td><s:date name="order.createdAt" format="dd/MM/yyyy HH:mm"/></td></tr>
  <tr><th>Giao thành công</th><td><s:date name="order.deliveredAt" format="dd/MM/yyyy HH:mm"/></td></tr>
  <tr><th>Ghi chú</th><td><s:property value="order.note"/></td></tr>
  <tr><th>Tiền đã về (ReconHub)</th><td>
    <s:if test="payment != null">Đã khớp: <s:property value="payment.source_code"/> <s:property value="payment.external_ref"/> (<s:property value="payment.method"/>)</s:if>
    <s:else><span class="muted">Chưa có tiền về</span></s:else>
  </td></tr>
</table>
<h3>Cập nhật trạng thái</h3>
<s:form action="updateStatus" method="post" cssClass="box">
  <s:hidden name="code" value="%{order.code}"/>
  Trạng thái mới: <s:select name="newStatus" list="statuses" value="%{order.status}"/>
  Mã vận đơn: <s:textfield name="trackingCode" value="%{order.trackingCode}"/>
  <s:submit value="Lưu"/>
</s:form>
<%@ include file="_footer.jspf" %>
