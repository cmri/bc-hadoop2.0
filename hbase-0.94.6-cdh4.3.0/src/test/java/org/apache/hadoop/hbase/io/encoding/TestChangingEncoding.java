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

import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseTestingUtility;
import org.apache.hadoop.hbase.HColumnDescriptor;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.LargeTests;
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.HBaseAdmin;
import org.apache.hadoop.hbase.client.HTable;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.regionserver.HRegionServer;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.Threads;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * Tests changing data block encoding settings of a column family.
 */
@Category(LargeTests.class)
public class TestChangingEncoding {

  private static final Log LOG = LogFactory.getLog(TestChangingEncoding.class);

  static final String CF = "EncodingTestCF";
  static final byte[] CF_BYTES = Bytes.toBytes(CF);

  private static final int NUM_ROWS_PER_BATCH = 100;
  private static final int NUM_COLS_PER_ROW = 20;

  private static final HBaseTestingUtility TEST_UTIL =
      new HBaseTestingUtility();
  private static final Configuration conf = TEST_UTIL.getConfiguration();

  private static final int TIMEOUT_MS = 240000;

  private HBaseAdmin admin;
  private HColumnDescriptor hcd;

  private String tableName;
  private static final List<DataBlockEncoding> ENCODINGS_TO_ITERATE =
      createEncodingsToIterate();

  private static final List<DataBlockEncoding> createEncodingsToIterate() {
    List<DataBlockEncoding> encodings = new ArrayList<DataBlockEncoding>(
        Arrays.asList(DataBlockEncoding.values()));
    encodings.add(DataBlockEncoding.NONE);
    return Collections.unmodifiableList(encodings);
  }

  /** A zero-based index of the current batch of test data being written */
  private int numBatchesWritten;

  private void prepareTest(String testId) throws IOException {
    tableName = "test_table_" + testId;
    HTableDescriptor htd = new HTableDescriptor(tableName);
    hcd = new HColumnDescriptor(CF);
    htd.addFamily(hcd);
    admin.createTable(htd);
    numBatchesWritten = 0;
  }

  @BeforeClass
  public static void setUpBeforeClass() throws Exception {
    // Use a small flush size to create more HFiles.
    conf.setInt(HConstants.HREGION_MEMSTORE_FLUSH_SIZE, 1024 * 1024);
    TEST_UTIL.startMiniCluster();
  }

  @AfterClass
  public static void tearDownAfterClass() throws Exception {
    TEST_UTIL.shutdownMiniCluster();
  }

  @Before
  public void setUp() throws Exception {
    admin = new HBaseAdmin(conf);
  }

  @After
  public void tearDown() throws IOException {
    admin.close();
  }

  private static byte[] getRowKey(int batchId, int i) {
    return Bytes.toBytes("batch" + batchId + "_row" + i);
  }

  private static byte[] getQualifier(int j) {
    return Bytes.toBytes("col" + j);
  }

  private static byte[] getValue(int batchId, int i, int j) {
    return Bytes.toBytes("value_for_" + Bytes.toString(getRowKey(batchId, i))
        + "_col" + j);
  }

  static void writeTestDataBatch(Configuration conf, String tableName,
      int batchId) throws Exception {
    LOG.debug("Writing test data batch " + batchId);
    HTable table = new HTable(conf, tableName);
    for (int i = 0; i < NUM_ROWS_PER_BATCH; ++i) {
      Put put = new Put(getRowKey(batchId, i));
      for (int j = 0; j < NUM_COLS_PER_ROW; ++j) {
        put.add(CF_BYTES, getQualifier(j),
            getValue(batchId, i, j));
        table.put(put);
      }
    }
    table.close();
  }

  static void verifyTestDataBatch(Configuration conf, String tableName,
      int batchId) throws Exception {
    LOG.debug("Verifying test data batch " + batchId);
    HTable table = new HTable(conf, tableName);
    for (int i = 0; i < NUM_ROWS_PER_BATCH; ++i) {
      Get get = new Get(getRowKey(batchId, i));
      Result result = table.get(get);
      for (int j = 0; j < NUM_COLS_PER_ROW; ++j) {
        KeyValue kv = result.getColumnLatest(CF_BYTES, getQualifier(j));
        assertEquals(Bytes.toStringBinary(getValue(batchId, i, j)),
            Bytes.toStringBinary(kv.getValue()));
      }
    }
    table.close();
  }

  private void writeSomeNewData() throws Exception {
    writeTestDataBatch(conf, tableName, numBatchesWritten);
    ++numBatchesWritten;
  }

  private void verifyAllData() throws Exception {
    for (int i = 0; i < numBatchesWritten; ++i) {
      verifyTestDataBatch(conf, tableName, i);
    }
  }

  private void setEncodingConf(DataBlockEncoding encoding,
      boolean encodeOnDisk) throws IOException {
    LOG.debug("Setting CF encoding to " + encoding + " (ordinal="
        + encoding.ordinal() + "), encodeOnDisk=" + encodeOnDisk);
    admin.disableTable(tableName);
    hcd.setDataBlockEncoding(encoding);
    hcd.setEncodeOnDisk(encodeOnDisk);
    admin.modifyColumn(tableName, hcd);
    admin.enableTable(tableName);
  }

  @Test(timeout=TIMEOUT_MS)
  public void testChangingEncoding() throws Exception {
    prepareTest("ChangingEncoding");
    for (boolean encodeOnDisk : new boolean[]{false, true}) {
      for (DataBlockEncoding encoding : ENCODINGS_TO_ITERATE) {
        setEncodingConf(encoding, encodeOnDisk);
        writeSomeNewData();
        verifyAllData();
      }
    }
  }

  @Test(timeout=TIMEOUT_MS)
  public void testChangingEncodingWithCompaction() throws Exception {
    prepareTest("ChangingEncodingWithCompaction");
    for (boolean encodeOnDisk : new boolean[]{false, true}) {
      for (DataBlockEncoding encoding : ENCODINGS_TO_ITERATE) {
        setEncodingConf(encoding, encodeOnDisk);
        writeSomeNewData();
        verifyAllData();
        compactAndWait();
        verifyAllData();
      }
    }
  }

  @Test(timeout=TIMEOUT_MS)
  public void testFlippingEncodeOnDisk() throws Exception {
    prepareTest("FlippingEncodeOnDisk");
    // The focus of this test case is to flip the "encoding on disk" flag,
    // so we only try a couple of encodings.
    DataBlockEncoding[] encodings = new DataBlockEncoding[] {
        DataBlockEncoding.NONE, DataBlockEncoding.FAST_DIFF };
    for (DataBlockEncoding encoding : encodings) {
      boolean[] flagValues;
      if (encoding == DataBlockEncoding.NONE) {
        // encodeOnDisk does not matter when not using encoding.
        flagValues =
            new boolean[] { HColumnDescriptor.DEFAULT_ENCODE_ON_DISK };
      } else {
        flagValues = new boolean[] { false, true, false, true };
      }
      for (boolean encodeOnDisk : flagValues) {
        setEncodingConf(encoding, encodeOnDisk);
        writeSomeNewData();
        verifyAllData();
        compactAndWait();
        verifyAllData();
      }
    }
  }

  private void compactAndWait() throws IOException, InterruptedException {
    LOG.debug("Compacting table " + tableName);
    admin.majorCompact(tableName);
    Threads.sleepWithoutInterrupt(500);
    HRegionServer rs = TEST_UTIL.getMiniHBaseCluster().getRegionServer(0);
    while (rs.compactSplitThread.getCompactionQueueSize() > 0) {
      Threads.sleep(50);
    }
    LOG.debug("Compaction queue size reached 0, continuing");
  }

  @Test
  public void testCrazyRandomChanges() throws Exception {
    prepareTest("RandomChanges");
    Random rand = new Random(2934298742974297L);
    for (int i = 0; i < 20; ++i) {
      int encodingOrdinal = rand.nextInt(DataBlockEncoding.values().length);
      DataBlockEncoding encoding = DataBlockEncoding.values()[encodingOrdinal];
      setEncodingConf(encoding, rand.nextBoolean());
      writeSomeNewData();
      verifyAllData();
    }
  }

}
