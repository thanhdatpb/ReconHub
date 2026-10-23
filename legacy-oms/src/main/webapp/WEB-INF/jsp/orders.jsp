<%@ include file="_header.jspf" %>
<h2>Danh sách đơn hàng <small>(<s:text name="format.int"><s:param value="total"/></s:text> đơn)</small></h2>
<s:form action="orders" method="get" cssClass="box">
  Tìm: <s:textfield name="search.q" placeholder="Mã đơn, tên khách, mã vận đơn, SĐT"/>
  Trạng thái: <s:select name="search.status" list="{'NEW','SHIPPING','DELIVERED','CANCELLED','RETURNED'}" headerKey="" headerValue="-- Tất cả --"/>
  Thanh toán qua: <s:select name="search.payVia" list="{'GHN','GHTK','VCB','MOMO','SHOPEE','TIKTOK'}" headerKey="" headerValue="-- Tất cả --"/>
  <s:submit value="Tìm"/>
</s:form>
<p class="counts">
  <s:iterator value="statusCounts"><span class="st st-<s:property value="status"/>"><s:property value="status"/>: <s:text name="format.int"><s:param value="total"/></s:text></span> </s:iterator>
</p>
<table class="grid">
  <tr><th>Mã đơn</th><th>Ngày tạo</th><th>Kênh</th><th>Khách hàng</th><th>SĐT</th><th class="r">Giá trị</th><th>Thanh toán</th><th>Mã vận đơn</th><th>Trạng thái</th></tr>
  <s:iterator value="orders">
    <tr>
      <td><a href="<s:url action='order'><s:param name='code' value='code'/></s:url>"><s:property value="code"/></a></td>
      <td><s:date name="createdAt" format="dd/MM/yyyy HH:mm"/></td>
      <td><s:property value="channel"/></td>
      <td><s:property value="customerName"/></td>
      <td><s:property value="phone"/></td>
      <td class="r"><s:text name="format.money"><s:param value="amount"/></s:text></td>
      <td><s:property value="payVia"/></td>
      <td><s:property value="trackingCode"/></td>
      <td><span class="st st-<s:property value='status'/>"><s:property value="status"/></span></td>
    </tr>
  </s:iterator>
  <s:if test="orders.isEmpty()"><tr><td colspan="9" class="empty">Không có đơn nào.</td></tr></s:if>
</table>
<p class="pager">
  Trang <s:property value="search.page"/> / <s:property value="pages"/>
  <s:if test="search.page > 1">
    · <a href="<s:url action='orders'><s:param name='search.q' value='search.q'/><s:param name='search.status' value='search.status'/><s:param name='search.payVia' value='search.payVia'/><s:param name='search.page' value='search.page - 1'/></s:url>">« Trước</a>
  </s:if>
  <s:if test="search.page < pages">
    · <a href="<s:url action='orders'><s:param name='search.q' value='search.q'/><s:param name='search.status' value='search.status'/><s:param name='search.payVia' value='search.payVia'/><s:param name='search.page' value='search.page + 1'/></s:url>">Sau »</a>
  </s:if>
</p>
<%@ include file="_footer.jspf" %>
