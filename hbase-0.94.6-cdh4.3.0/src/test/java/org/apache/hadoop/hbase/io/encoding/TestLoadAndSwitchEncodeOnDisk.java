/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. The ASF
 * licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package org.apache.hadoop.hbase.io.encoding;

import java.util.Arrays;
import java.util.Collection;

import org.apache.hadoop.hbase.HColumnDescriptor;
import org.apache.hadoop.hbase.MediumTests;
import org.apache.hadoop.hbase.client.HBaseAdmin;
import org.apache.hadoop.hbase.io.hfile.CacheConfig;
import org.apache.hadoop.hbase.io.hfile.Compression;
import org.apache.hadoop.hbase.regionserver.HRegionServer;
import org.apache.hadoop.hbase.util.TestMiniClusterLoadSequential;
import org.apache.hadoop.hbase.util.Threads;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runners.Parameterized.Parameters;

/**
 * Uses the load tester
 */
@Category(MediumTests.class)
public class TestLoadAndSwitchEncodeOnDisk extends
    TestMiniClusterLoadSequential {

  /** We do not alternate the multi-put flag in this test. */
  private static final boolean USE_MULTI_PUT = true;

  /** Un-parameterize the test */
  @Parameters
  public static Collection<Object[]> parameters() {
    return Arrays.asList(new Object[][]{ new Object[0] });
  }

  public TestLoadAndSwitchEncodeOnDisk() {
    super(USE_MULTI_PUT, DataBlockEncoding.PREFIX);
    conf.setBoolean(CacheConfig.CACHE_BLOCKS_ON_WRITE_KEY, true);
  }

  protected int numKeys() {
    return 3000;
  }

  @Test(timeout=TIMEOUT_MS)
  public void loadTest() throws Exception {
    HBaseAdmin admin = new HBaseAdmin(conf);

    compression = Compression.Algorithm.GZ; // used for table setup
    super.loadTest();

    HColumnDescriptor hcd = getColumnDesc(admin);
    System.err.println("\nDisabling encode-on-disk. Old column descriptor: " +
        hcd + "\n");
    admin.disableTable(TABLE);
    hcd.setEncodeOnDisk(false);
    admin.modifyColumn(TABLE, hcd);

    System.err.println("\nRe-enabling table\n");
    admin.enableTable(TABLE);

    System.err.println("\nNew column descriptor: " +
        getColumnDesc(admin) + "\n");

    System.err.println("\nCompacting the table\n");
    admin.majorCompact(TABLE);
    // Wait until compaction completes
    Threads.sleepWithoutInterrupt(5000);
    HRegionServer rs = TEST_UTIL.getMiniHBaseCluster().getRegionServer(0);
    while (rs.compactSplitThread.getCompactionQueueSize() > 0) {
      Threads.sleep(50);
    }

    System.err.println("\nDone with the test, shutting down the cluster\n");
  }

}
