<%--
/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
--%>
<%@ page contentType="text/html;charset=UTF-8"
  import="java.io.IOException"
  import="org.apache.hadoop.conf.Configuration"
  import="org.apache.hadoop.hbase.client.HBaseAdmin"
  import="org.apache.hadoop.hbase.client.HConnection"
  import="org.apache.hadoop.hbase.client.HConnectionManager"
  import="org.apache.hadoop.hbase.HRegionInfo"
  import="org.apache.hadoop.hbase.zookeeper.ZKUtil"
  import="org.apache.hadoop.hbase.zookeeper.ZooKeeperWatcher"
  import="org.apache.hadoop.hbase.HBaseConfiguration"
  import="org.apache.hadoop.hbase.master.HMaster"
  import="org.apache.hadoop.hbase.HConstants"%><%
  HMaster master = (HMaster)getServletContext().getAttribute(HMaster.MASTER);
  ZooKeeperWatcher watcher = master.getZooKeeperWatcher();
%>

<?xml version="1.0" encoding="UTF-8" ?>
<!-- Commenting out DOCTYPE so our blue outline shows on hadoop 0.20.205.0, etc.
     See tail of HBASE-2110 for explaination.
<!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.0 Transitional//EN"
  "http://www.w3.org/TR/xhtml1/DTD/xhtml1-transitional.dtd">
-->
<html xmlns="http://www.w3.org/1999/xhtml">
<head><meta http-equiv="Content-Type" content="text/html;charset=UTF-8"/>
<title>ZooKeeper Dump</title>
<link rel="stylesheet" type="text/css" href="/static/hbase.css" />
</head>
<body>
<a id="logo" href="http://hbase.org"><img src="/static/hbase_logo.png" alt="HBase Logo" title="HBase Logo" /></a>
<h1 id="page_title">ZooKeeper Dump</h1>
<p id="links_menu"><a href="/master.jsp">Master</a>, <a href="/logs/">Local logs</a>, <a href="/stacks">Thread Dump</a>, <a href="/logLevel">Log Level</a></p>
<hr id="head_rule" />
<pre>
<%= ZKUtil.dump(watcher) %>
</pre>

</body>
</html>
