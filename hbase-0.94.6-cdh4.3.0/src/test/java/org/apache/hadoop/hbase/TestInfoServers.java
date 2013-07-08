/**
 * Copyright 2007 The Apache Software Foundation
 *
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
package org.apache.hadoop.hbase;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.net.URL;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.hbase.client.HTable;
import org.apache.hadoop.hbase.util.Bytes;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import static org.junit.Assert.assertTrue;

/**
 * Testing, info servers are disabled.  This test enables then and checks that
 * they serve pages.
 */
@Category(MediumTests.class)
public class TestInfoServers {
  static final Log LOG = LogFactory.getLog(TestInfoServers.class);
  private final static HBaseTestingUtility UTIL = new HBaseTestingUtility();

  @BeforeClass
  public static void beforeClass() throws Exception {
    // The info servers do not run in tests by default.
    // Set them to ephemeral ports so they will start
    UTIL.getConfiguration().setInt("hbase.master.info.port", 0);
    UTIL.getConfiguration().setInt("hbase.regionserver.info.port", 0);
    UTIL.getConfiguration().setBoolean("hbase.master.ui.readonly", true);
    UTIL.startMiniCluster();
  }

  @AfterClass
  public static void afterClass() throws Exception {
    UTIL.shutdownMiniCluster();
  }

  /**
   * @throws Exception
   */
  @Test
  public void testInfoServersRedirect() throws Exception {
    // give the cluster time to start up
    new HTable(UTIL.getConfiguration(), ".META.").close();
    int port = UTIL.getHBaseCluster().getMaster().getInfoServer().getPort();
    assertContainsContent(new URL("http://localhost:" + port +
        "/index.html"), "master-status");
    port = UTIL.getHBaseCluster().getRegionServerThreads().get(0).getRegionServer().
      getInfoServer().getPort();
    assertContainsContent(new URL("http://localhost:" + port +
        "/index.html"), "rs-status");
  }

  /**
   * Test that the status pages in the minicluster load properly.
   *
   * This is somewhat a duplicate of TestRSStatusServlet and
   * TestMasterStatusServlet, but those are true unit tests
   * whereas this uses a cluster.
   */
  @Test
  public void testInfoServersStatusPages() throws Exception {
    // give the cluster time to start up
    new HTable(UTIL.getConfiguration(), ".META.").close();
    int port = UTIL.getHBaseCluster().getMaster().getInfoServer().getPort();
    assertContainsContent(new URL("http://localhost:" + port +
        "/master-status"), "META");
    port = UTIL.getHBaseCluster().getRegionServerThreads().get(0).getRegionServer().
      getInfoServer().getPort();
    assertContainsContent(new URL("http://localhost:" + port +
        "/rs-status"), "META");
  }

  @Test
  public void testMasterServerReadOnly() throws Exception {
    String sTableName = "testMasterServerReadOnly";
    byte[] tableName = Bytes.toBytes(sTableName);
    byte[] cf = Bytes.toBytes("d");
    UTIL.createTable(tableName, cf);
    new HTable(UTIL.getConfiguration(), tableName).close();
    int port = UTIL.getHBaseCluster().getMaster().getInfoServer().getPort();
    assertDoesNotContainContent(
      new URL("http://localhost:" + port + "/table.jsp?name=" + sTableName + "&action=split&key="),
      "Table action request accepted");
    assertDoesNotContainContent(
      new URL("http://localhost:" + port + "/table.jsp?name=" + sTableName),
      "Actions:");
  }

  private void assertContainsContent(final URL u, final String expected)
  throws IOException {
    LOG.info("Testing " + u.toString() + " has " + expected);
    String content = getUrlContent(u);
    assertTrue("expected=" + expected + ", content=" + content,
      content.contains(expected));
  }



  private void assertDoesNotContainContent(final URL u, final String expected)
      throws IOException {
    LOG.info("Testing " + u.toString() + " has " + expected);
    String content = getUrlContent(u);
    assertTrue("Does Not Contain =" + expected + ", content=" + content,
        !content.contains(expected));
  }

  private String getUrlContent(URL u) throws IOException {
    java.net.URLConnection c = u.openConnection();
    c.connect();
    StringBuilder sb = new StringBuilder();
    BufferedInputStream bis = new BufferedInputStream(c.getInputStream());
    byte [] bytes = new byte[1024];
    for (int read = -1; (read = bis.read(bytes)) != -1;) {
      sb.append(new String(bytes, 0, read));
    }
    bis.close();
    String content = sb.toString();
    return content;
  }

  @org.junit.Rule
  public org.apache.hadoop.hbase.ResourceCheckerJUnitRule cu =
    new org.apache.hadoop.hbase.ResourceCheckerJUnitRule();
}

