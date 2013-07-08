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
package org.apache.hadoop.hbase.regionserver;

import static org.junit.Assert.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.hadoop.hbase.HBaseTestingUtility;
import org.apache.hadoop.hbase.HColumnDescriptor;
import org.apache.hadoop.hbase.HRegionInfo;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.MiniHBaseCluster;
import org.apache.hadoop.hbase.client.HBaseAdmin;
import org.apache.hadoop.hbase.client.HTable;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.ResultScanner;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.master.HMaster;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.MediumTests;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * Tests that need to spin up a cluster testing an {@link HRegion}.  Use
 * {@link TestHRegion} if you don't need a cluster, if you can test w/ a
 * standalone {@link HRegion}.
 */
@Category(MediumTests.class)
public class TestHRegionOnCluster {
  private static final HBaseTestingUtility TEST_UTIL = new HBaseTestingUtility();

  @Test (timeout=180000)
  public void testDataCorrectnessReplayingRecoveredEdits() throws Exception {
    final int NUM_MASTERS = 1;
    final int NUM_RS = 3;
    TEST_UTIL.startMiniCluster(NUM_MASTERS, NUM_RS);

    try {
      final byte[] TABLENAME = Bytes
          .toBytes("testDataCorrectnessReplayingRecoveredEdits");
      final byte[] FAMILY = Bytes.toBytes("family");
      MiniHBaseCluster cluster = TEST_UTIL.getHBaseCluster();
      HMaster master = cluster.getMaster();

      // Create table
      HTableDescriptor desc = new HTableDescriptor(TABLENAME);
      desc.addFamily(new HColumnDescriptor(FAMILY));
      HBaseAdmin hbaseAdmin = TEST_UTIL.getHBaseAdmin();
      hbaseAdmin.createTable(desc);

      assertTrue(hbaseAdmin.isTableAvailable(TABLENAME));

      // Put data: r1->v1
      HTable table = new HTable(TEST_UTIL.getConfiguration(), TABLENAME);
      putDataAndVerify(table, "r1", FAMILY, "v1", 1);

      // Move region to target server
      HRegionInfo regionInfo = table.getRegionLocation("r1").getRegionInfo();
      int originServerNum = cluster.getServerWith(regionInfo.getRegionName());
      HRegionServer originServer = cluster.getRegionServer(originServerNum);
      int targetServerNum = (originServerNum + 1) % NUM_RS;
      HRegionServer targetServer = cluster.getRegionServer(targetServerNum);
      assertFalse(originServer.equals(targetServer));

      do {
        Thread.sleep(10);
      } while (!originServer.getServerName().equals(
              cluster.getMaster().getAssignmentManager().getRegionServerOfRegion(regionInfo)));

      hbaseAdmin.move(regionInfo.getEncodedNameAsBytes(),
          Bytes.toBytes(targetServer.getServerName().getServerName()));

      do {
        Thread.sleep(10);
      } while (cluster.getServerWith(regionInfo.getRegionName()) == originServerNum ||
          !targetServer.getServerName().equals(
              cluster.getMaster().getAssignmentManager().getRegionServerOfRegion(regionInfo)));

      // Put data: r2->v2
      putDataAndVerify(table, "r2", FAMILY, "v2", 2);

      // Move region to origin server
      hbaseAdmin.move(regionInfo.getEncodedNameAsBytes(),
          Bytes.toBytes(originServer.getServerName().getServerName()));
      do {
        Thread.sleep(1);
      } while (cluster.getServerWith(regionInfo.getRegionName()) == targetServerNum);

      // Put data: r3->v3
      putDataAndVerify(table, "r3", FAMILY, "v3", 3);

      // Kill target server
      targetServer.kill();
      cluster.getRegionServerThreads().get(targetServerNum).join();
      // Wait until finish processing of shutdown
      while (master.getServerManager().areDeadServersInProgress()) {
        Thread.sleep(5);
      }
      // Kill origin server
      originServer.kill();
      cluster.getRegionServerThreads().get(originServerNum).join();

      // Put data: r4->v4
      putDataAndVerify(table, "r4", FAMILY, "v4", 4);

    } finally {
      TEST_UTIL.shutdownMiniCluster();
    }
  }

  private void putDataAndVerify(HTable table, String row, byte[] family,
      String value, int verifyNum) throws IOException {
    System.out.println("=========Putting data :" + row);
    Put put = new Put(Bytes.toBytes(row));
    put.add(family, Bytes.toBytes("q1"), Bytes.toBytes(value));
    table.put(put);
    ResultScanner resultScanner = table.getScanner(new Scan());
    List<Result> results = new ArrayList<Result>();
    while (true) {
      Result r = resultScanner.next();
      if (r == null)
        break;
      results.add(r);
    }
    resultScanner.close();
    if (results.size() != verifyNum) {
      System.out.println(results);
    }
    assertEquals(verifyNum, results.size());
  }

  @org.junit.Rule
  public org.apache.hadoop.hbase.ResourceCheckerJUnitRule cu =
    new org.apache.hadoop.hbase.ResourceCheckerJUnitRule();
}
