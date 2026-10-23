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
<s:if test="hasQr">
<h3>Mã VietQR chuyển khoản</h3>
<div class="box qr">
  <img src="<s:url action='orderQr'><s:param name='code' value='order.code'/></s:url>" alt="Mã VietQR đơn <s:property value='order.code'/>" width="270" height="270">
  <table class="detail">
    <tr><th>Ngân hàng (BIN)</th><td><s:property value="qrAccount.bin"/></td></tr>
    <tr><th>Số tài khoản</th><td><s:property value="qrAccount.account"/></td></tr>
    <tr><th>Chủ tài khoản</th><td><s:property value="qrAccount.accountName"/></td></tr>
    <tr><th>Số tiền</th><td><s:text name="format.money"><s:param value="order.amount"/></s:text></td></tr>
    <tr><th>Nội dung</th><td><s:property value="order.code"/></td></tr>
  </table>
  <p class="hint">Khách quét bằng app ngân hàng: số tiền và nội dung đã điền sẵn, đừng sửa nội dung để tiền tự khớp với đơn.</p>
</div>
</s:if>
<h3>Cập nhật trạng thái</h3>
<s:form action="updateStatus" method="post" cssClass="box">
  <s:hidden name="code" value="%{order.code}"/>
  Trạng thái mới: <s:select name="newStatus" list="statuses" value="%{order.status}"/>
  Mã vận đơn: <s:textfield name="trackingCode" value="%{order.trackingCode}"/>
  <s:submit value="Lưu"/>
</s:form>
<%@ include file="_footer.jspf" %>
