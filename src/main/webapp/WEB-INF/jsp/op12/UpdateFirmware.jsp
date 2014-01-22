<%@ include file="/WEB-INF/jsp/00-header.jsp" %>
<div class="left-menu">
<ul>
	<li><a href="${servletPath}/ChangeAvailability">Change Availability</a></li>
	<li><a href="${servletPath}/ChangeConfiguration">Change Configuration</a></li>
	<li><a href="${servletPath}/ClearCache">Clear Cache</a></li>
	<li><a href="${servletPath}/GetDiagnostics">Get Diagnostics</a></li>
	<li><a href="${servletPath}/RemoteStartTransaction">Remote Start Transaction</a></li>
	<li><a href="${servletPath}/RemoteStopTransaction">Remote Stop Transaction</a></li>
	<li><a href="${servletPath}/Reset">Reset</a></li>
	<li><a href="${servletPath}/UnlockConnector">Unlock Connector</a></li>
	<li><a class="highlight" href="${servletPath}/UpdateFirmware">Update Firmware</a></li>
</ul>
</div>
<div class="op-content">
<form method="POST" action="${servletPath}/UpdateFirmware">
<%@ include file="00-cp-multiple.jsp" %>
<section><span>Parameters</span></section>
<table>
<tr><td>Location (URI):</td><td><input type="text" name="location"></td></tr>
<tr><td>Retries (integer):</td><td><input type="number" min="0" name="retries"></td></tr>
<tr><td>Retry Interval (integer):</td><td><input type="number" min="0" name="retryInterval"></td></tr>
<tr><td>Retrieve Date (ex: 2011-12-21 11:33):</td><td><input type="datetime" name="retrieveDate"></td></tr>
</table>
<div class="submit-button"><input type="submit" value="Perform"></div>
</form>
</div>
<%@ include file="/WEB-INF/jsp/00-footer.jsp" %>