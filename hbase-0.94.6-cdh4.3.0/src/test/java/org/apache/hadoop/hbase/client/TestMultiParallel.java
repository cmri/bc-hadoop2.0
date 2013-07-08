/*
 * Copyright 2009 The Apache Software Foundation
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
package org.apache.hadoop.hbase.client;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadPoolExecutor;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.hbase.*;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.JVMClusterUtil;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import static org.junit.Assert.*;

@Category(MediumTests.class)
public class TestMultiParallel {
  private static final Log LOG = LogFactory.getLog(TestMultiParallel.class);
  private static final HBaseTestingUtility UTIL = new HBaseTestingUtility();
  private static final byte[] VALUE = Bytes.toBytes("value");
  private static final byte[] QUALIFIER = Bytes.toBytes("qual");
  private static final String FAMILY = "family";
  private static final String TEST_TABLE = "multi_test_table";
  private static final byte[] BYTES_FAMILY = Bytes.toBytes(FAMILY);
  private static final byte[] ONE_ROW = Bytes.toBytes("xxx");
  private static final byte [][] KEYS = makeKeys();

  private static final int slaves = 2; // also used for testing HTable pool size

  @BeforeClass public static void beforeClass() throws Exception {
    UTIL.startMiniCluster(slaves);
    HTable t = UTIL.createTable(Bytes.toBytes(TEST_TABLE), Bytes.toBytes(FAMILY));
    UTIL.createMultiRegions(t, Bytes.toBytes(FAMILY));
    UTIL.waitTableAvailable(Bytes.toBytes(TEST_TABLE), 15 * 1000);
    t.close();
  }

  @AfterClass public static void afterClass() throws Exception {
    UTIL.shutdownMiniCluster();
  }

  @Before public void before() throws IOException {
    LOG.info("before");
    if (UTIL.ensureSomeRegionServersAvailable(slaves)) {
      // Distribute regions
      UTIL.getMiniHBaseCluster().getMaster().balance();
    }
    LOG.info("before done");
  }

  private static byte[][] makeKeys() {
    byte [][] starterKeys = HBaseTestingUtility.KEYS;
    // Create a "non-uniform" test set with the following characteristics:
    // a) Unequal number of keys per region

    // Don't use integer as a multiple, so that we have a number of keys that is
    // not a multiple of the number of regions
    int numKeys = (int) ((float) starterKeys.length * 10.33F);

    List<byte[]> keys = new ArrayList<byte[]>();
    for (int i = 0; i < numKeys; i++) {
      int kIdx = i % starterKeys.length;
      byte[] k = starterKeys[kIdx];
      byte[] cp = new byte[k.length + 1];
      System.arraycopy(k, 0, cp, 0, k.length);
      cp[k.length] = new Integer(i % 256).byteValue();
      keys.add(cp);
    }

    // b) Same duplicate keys (showing multiple Gets/Puts to the same row, which
    // should work)
    // c) keys are not in sorted order (within a region), to ensure that the
    // sorting code and index mapping doesn't break the functionality
    for (int i = 0; i < 100; i++) {
      int kIdx = i % starterKeys.length;
      byte[] k = starterKeys[kIdx];
      byte[] cp = new byte[k.length + 1];
      System.arraycopy(k, 0, cp, 0, k.length);
      cp[k.length] = new Integer(i % 256).byteValue();
      keys.add(cp);
    }
    return keys.toArray(new byte [][] {new byte [] {}});
  }


  /**
   * This is for testing the active number of threads that were used while
   * doing a batch operation. It inserts one row per region via the batch
   * operation, and then checks the number of active threads.
   * For HBASE-3553
   * @throws IOException
   * @throws InterruptedException
   * @throws NoSuchFieldException
   * @throws SecurityException
   */
  @Test(timeout=300000) 
  public void testActiveThreadsCount() throws Exception{
    HTable table = new HTable(UTIL.getConfiguration(), TEST_TABLE);
    List<Row> puts = constructPutRequests(); // creates a Put for every region
    table.batch(puts);
    Field poolField = table.getClass().getDeclaredField("pool");
    poolField.setAccessible(true);
    ThreadPoolExecutor tExecutor = (ThreadPoolExecutor) poolField.get(table);
    assertEquals(slaves, tExecutor.getLargestPoolSize());
    table.close();
  }

  @Test(timeout=300000) 
  public void testBatchWithGet() throws Exception {
    LOG.info("test=testBatchWithGet");
    HTable table = new HTable(UTIL.getConfiguration(), TEST_TABLE);

    // load test data
    List<Row> puts = constructPutRequests();
    table.batch(puts);

    // create a list of gets and run it
    List<Row> gets = new ArrayList<Row>();
    for (byte[] k : KEYS) {
      Get get = new Get(k);
      get.addColumn(BYTES_FAMILY, QUALIFIER);
      gets.add(get);
    }
    Result[] multiRes = new Result[gets.size()];
    table.batch(gets, multiRes);

    // Same gets using individual call API
    List<Result> singleRes = new ArrayList<Result>();
    for (Row get : gets) {
      singleRes.add(table.get((Get) get));
    }

    // Compare results
    Assert.assertEquals(singleRes.size(), multiRes.length);
    for (int i = 0; i < singleRes.size(); i++) {
      Assert.assertTrue(singleRes.get(i).containsColumn(BYTES_FAMILY, QUALIFIER));
      KeyValue[] singleKvs = singleRes.get(i).raw();
      KeyValue[] multiKvs = multiRes[i].raw();
      for (int j = 0; j < singleKvs.length; j++) {
        Assert.assertEquals(singleKvs[j], multiKvs[j]);
        Assert.assertEquals(0, Bytes.compareTo(singleKvs[j].getValue(), multiKvs[j]
            .getValue()));
      }
    }
    table.close();
  }

  @Test
  public void testBadFam() throws Exception {
    LOG.info("test=testBadFam");
    HTable table = new HTable(UTIL.getConfiguration(), TEST_TABLE);

    List<Row> actions = new ArrayList<Row>();
    Put p = new Put(Bytes.toBytes("row1"));
    p.add(Bytes.toBytes("bad_family"), Bytes.toBytes("qual"), Bytes.toBytes("value"));
    actions.add(p);
    p = new Put(Bytes.toBytes("row2"));
    p.add(BYTES_FAMILY, Bytes.toBytes("qual"), Bytes.toBytes("value"));
    actions.add(p);

    // row1 and row2 should be in the same region.

    Object [] r = new Object[actions.size()];
    try {
      table.batch(actions, r);
      fail();
    } catch (RetriesExhaustedWithDetailsException ex) {
      LOG.debug(ex);
      // good!
      assertFalse(ex.mayHaveClusterIssues());
    }
    assertEquals(2, r.length);
    assertTrue(r[0] instanceof Throwable);
    assertTrue(r[1] instanceof Result);
    table.close();
  }

  /**
   * Only run one Multi test with a forced RegionServer abort. Otherwise, the
   * unit tests will take an unnecessarily long time to run.
   *
   * @throws Exception
   */
  @Test (timeout=300000) 
  public void testFlushCommitsWithAbort() throws Exception {
    LOG.info("test=testFlushCommitsWithAbort");
    doTestFlushCommits(true);
  }

  @Test (timeout=300000)
  public void testFlushCommitsNoAbort() throws Exception {
    LOG.info("test=testFlushCommitsNoAbort");
    doTestFlushCommits(false);
  }

  /**
   * Set table auto flush to false and test flushing commits
   * @param doAbort true if abort one regionserver in the testing
   * @throws Exception
   */
  private void doTestFlushCommits(boolean doAbort) throws Exception {
    // Load the data
    LOG.info("get new table");
    HTable table = new HTable(UTIL.getConfiguration(), TEST_TABLE);
    table.setAutoFlush(false);
    table.setWriteBufferSize(10 * 1024 * 1024);

    LOG.info("constructPutRequests");
    List<Row> puts = constructPutRequests();
    for (Row put : puts) {
      table.put((Put) put);
    }
    LOG.info("puts");
    table.flushCommits();
    int liveRScount = UTIL.getMiniHBaseCluster().getLiveRegionServerThreads()
        .size();
    assert liveRScount > 0;
    JVMClusterUtil.RegionServerThread liveRS = UTIL.getMiniHBaseCluster()
        .getLiveRegionServerThreads().get(0);
    if (doAbort) {
      LOG.info("Aborted=" + UTIL.getMiniHBaseCluster().abortRegionServer(0));

      // If we waiting for no regions being online after we abort the server, we
      // could ensure the master has re-assigned the regions on killed server
      // after putting keys successfully, it means the server we abort is dead
      // and detected by matser
      while (liveRS.getRegionServer().getNumberOfOnlineRegions() != 0) {
        Thread.sleep(100);
      }
      while (UTIL.getMiniHBaseCluster().getLiveRegionServerThreads().size() == liveRScount) {
        Thread.sleep(100);
      }

      // try putting more keys after the abort. same key/qual... just validating
      // no exceptions thrown
      puts = constructPutRequests();
      for (Row put : puts) {
        table.put((Put) put);
      }

      table.flushCommits();
    }

    LOG.info("validating loaded data");
    validateLoadedData(table);

    // Validate server and region count
    List<JVMClusterUtil.RegionServerThread> liveRSs =
      UTIL.getMiniHBaseCluster().getLiveRegionServerThreads();
    int count = 0;
    for (JVMClusterUtil.RegionServerThread t: liveRSs) {
      count++;
      LOG.info("Count=" + count + ", Alive=" + t.getRegionServer());
    }
    LOG.info("Count=" + count);
    Assert.assertEquals("Server count=" + count + ", abort=" + doAbort,
        (doAbort ? (liveRScount - 1) : liveRScount), count);
    for (JVMClusterUtil.RegionServerThread t: liveRSs) {
      int regions = t.getRegionServer().getOnlineRegions().size();
      Assert.assertTrue("Count of regions=" + regions, regions > 10);
    }
    table.close();
    LOG.info("done");
  }

  @Test (timeout=300000)
  public void testBatchWithPut() throws Exception {
    LOG.info("test=testBatchWithPut");
    HTable table = new HTable(UTIL.getConfiguration(), TEST_TABLE);

    // put multiple rows using a batch
    List<Row> puts = constructPutRequests();

    Object[] results = table.batch(puts);
    validateSizeAndEmpty(results, KEYS.length);

    if (true) {
      int liveRScount = UTIL.getMiniHBaseCluster().getLiveRegionServerThreads()
          .size();
      assert liveRScount > 0;
      JVMClusterUtil.RegionServerThread liveRS = UTIL.getMiniHBaseCluster()
          .getLiveRegionServerThreads().get(0);
      liveRS.getRegionServer().abort("Aborting for tests",
          new Exception("testBatchWithPut"));

      puts = constructPutRequests();
      results = table.batch(puts);
      validateSizeAndEmpty(results, KEYS.length);
    }

    validateLoadedData(table);
    table.close();
  }

  @Test(timeout=300000) 
  public void testBatchWithDelete() throws Exception {
    LOG.info("test=testBatchWithDelete");
    HTable table = new HTable(UTIL.getConfiguration(), TEST_TABLE);

    // Load some data
    List<Row> puts = constructPutRequests();
    Object[] results = table.batch(puts);
    validateSizeAndEmpty(results, KEYS.length);

    // Deletes
    List<Row> deletes = new ArrayList<Row>();
    for (int i = 0; i < KEYS.length; i++) {
      Delete delete = new Delete(KEYS[i]);
      delete.deleteFamily(BYTES_FAMILY);
      deletes.add(delete);
    }
    results = table.batch(deletes);
    validateSizeAndEmpty(results, KEYS.length);

    // Get to make sure ...
    for (byte[] k : KEYS) {
      Get get = new Get(k);
      get.addColumn(BYTES_FAMILY, QUALIFIER);
      Assert.assertFalse(table.exists(get));
    }
    table.close();
  }

  @Test(timeout=300000)
  public void testHTableDeleteWithList() throws Exception {
    LOG.info("test=testHTableDeleteWithList");
    HTable table = new HTable(UTIL.getConfiguration(), TEST_TABLE);

    // Load some data
    List<Row> puts = constructPutRequests();
    Object[] results = table.batch(puts);
    validateSizeAndEmpty(results, KEYS.length);

    // Deletes
    ArrayList<Delete> deletes = new ArrayList<Delete>();
    for (int i = 0; i < KEYS.length; i++) {
      Delete delete = new Delete(KEYS[i]);
      delete.deleteFamily(BYTES_FAMILY);
      deletes.add(delete);
    }
    table.delete(deletes);
    Assert.assertTrue(deletes.isEmpty());

    // Get to make sure ...
    for (byte[] k : KEYS) {
      Get get = new Get(k);
      get.addColumn(BYTES_FAMILY, QUALIFIER);
      Assert.assertFalse(table.exists(get));
    }
    table.close();
  }

  @Test(timeout=300000)
  public void testBatchWithManyColsInOneRowGetAndPut() throws Exception {
    LOG.info("test=testBatchWithManyColsInOneRowGetAndPut");
    HTable table = new HTable(UTIL.getConfiguration(), TEST_TABLE);

    List<Row> puts = new ArrayList<Row>();
    for (int i = 0; i < 100; i++) {
      Put put = new Put(ONE_ROW);
      byte[] qual = Bytes.toBytes("column" + i);
      put.add(BYTES_FAMILY, qual, VALUE);
      puts.add(put);
    }
    Object[] results = table.batch(puts);

    // validate
    validateSizeAndEmpty(results, 100);

    // get the data back and validate that it is correct
    List<Row> gets = new ArrayList<Row>();
    for (int i = 0; i < 100; i++) {
      Get get = new Get(ONE_ROW);
      byte[] qual = Bytes.toBytes("column" + i);
      get.addColumn(BYTES_FAMILY, qual);
      gets.add(get);
    }

    Object[] multiRes = table.batch(gets);

    int idx = 0;
    for (Object r : multiRes) {
      byte[] qual = Bytes.toBytes("column" + idx);
      validateResult(r, qual, VALUE);
      idx++;
    }
    table.close();
  }

  @Test(timeout=300000)
  public void testBatchWithIncrementAndAppend() throws Exception {
    LOG.info("test=testBatchWithIncrementAndAppend");
    final byte[] QUAL1 = Bytes.toBytes("qual1");
    final byte[] QUAL2 = Bytes.toBytes("qual2");
    final byte[] QUAL3 = Bytes.toBytes("qual3");
    final byte[] QUAL4 = Bytes.toBytes("qual4");
    HTable table = new HTable(UTIL.getConfiguration(), TEST_TABLE);
    Delete d = new Delete(ONE_ROW);
    table.delete(d);
    Put put = new Put(ONE_ROW);
    put.add(BYTES_FAMILY, QUAL1, Bytes.toBytes("abc"));
    put.add(BYTES_FAMILY, QUAL2, Bytes.toBytes(1L));
    table.put(put);

    Increment inc = new Increment(ONE_ROW);
    inc.addColumn(BYTES_FAMILY, QUAL2, 1);
    inc.addColumn(BYTES_FAMILY, QUAL3, 1);

    Append a = new Append(ONE_ROW);
    a.add(BYTES_FAMILY, QUAL1, Bytes.toBytes("def"));
    a.add(BYTES_FAMILY, QUAL4, Bytes.toBytes("xyz"));
    List<Row> actions = new ArrayList<Row>();
    actions.add(inc);
    actions.add(a);

    Object[] multiRes = table.batch(actions);
    validateResult(multiRes[1], QUAL1, Bytes.toBytes("abcdef"));
    validateResult(multiRes[1], QUAL4, Bytes.toBytes("xyz"));
    validateResult(multiRes[0], QUAL2, Bytes.toBytes(2L));
    validateResult(multiRes[0], QUAL3, Bytes.toBytes(1L));
    table.close();
  }

  @Test(timeout=300000)
  public void testBatchWithMixedActions() throws Exception {
    LOG.info("test=testBatchWithMixedActions");
    HTable table = new HTable(UTIL.getConfiguration(), TEST_TABLE);

    // Load some data to start
    Object[] results = table.batch(constructPutRequests());
    validateSizeAndEmpty(results, KEYS.length);

    // Batch: get, get, put(new col), delete, get, get of put, get of deleted,
    // put
    List<Row> actions = new ArrayList<Row>();

    byte[] qual2 = Bytes.toBytes("qual2");
    byte[] val2 = Bytes.toBytes("putvalue2");

    // 0 get
    Get get = new Get(KEYS[10]);
    get.addColumn(BYTES_FAMILY, QUALIFIER);
    actions.add(get);

    // 1 get
    get = new Get(KEYS[11]);
    get.addColumn(BYTES_FAMILY, QUALIFIER);
    actions.add(get);

    // 2 put of new column
    Put put = new Put(KEYS[10]);
    put.add(BYTES_FAMILY, qual2, val2);
    actions.add(put);

    // 3 delete
    Delete delete = new Delete(KEYS[20]);
    delete.deleteFamily(BYTES_FAMILY);
    actions.add(delete);

    // 4 get
    get = new Get(KEYS[30]);
    get.addColumn(BYTES_FAMILY, QUALIFIER);
    actions.add(get);

    // There used to be a 'get' of a previous put here, but removed
    // since this API really cannot guarantee order in terms of mixed
    // get/puts.

    // 5 put of new column
    put = new Put(KEYS[40]);
    put.add(BYTES_FAMILY, qual2, val2);
    actions.add(put);

    results = table.batch(actions);

    // Validation

    validateResult(results[0]);
    validateResult(results[1]);
    validateEmpty(results[2]);
    validateEmpty(results[3]);
    validateResult(results[4]);
    validateEmpty(results[5]);

    // validate last put, externally from the batch
    get = new Get(KEYS[40]);
    get.addColumn(BYTES_FAMILY, qual2);
    Result r = table.get(get);
    validateResult(r, qual2, val2);

    table.close();
  }

  // // Helper methods ////

  private void validateResult(Object r) {
    validateResult(r, QUALIFIER, VALUE);
  }

  private void validateResult(Object r1, byte[] qual, byte[] val) {
    // TODO provide nice assert here or something.
    Result r = (Result)r1;
    Assert.assertTrue(r.containsColumn(BYTES_FAMILY, qual));
    Assert.assertEquals(0, Bytes.compareTo(val, r.getValue(BYTES_FAMILY, qual)));
  }

  private List<Row> constructPutRequests() {
    List<Row> puts = new ArrayList<Row>();
    for (byte[] k : KEYS) {
      Put put = new Put(k);
      put.add(BYTES_FAMILY, QUALIFIER, VALUE);
      puts.add(put);
    }
    return puts;
  }

  private void validateLoadedData(HTable table) throws IOException {
    // get the data back and validate that it is correct
    for (byte[] k : KEYS) {
      Get get = new Get(k);
      get.addColumn(BYTES_FAMILY, QUALIFIER);
      Result r = table.get(get);
      Assert.assertTrue(r.containsColumn(BYTES_FAMILY, QUALIFIER));
      Assert.assertEquals(0, Bytes.compareTo(VALUE, r
          .getValue(BYTES_FAMILY, QUALIFIER)));
    }
  }

  private void validateEmpty(Object r1) {
    Result result = (Result)r1;
    Assert.assertTrue(result != null);
    Assert.assertTrue(result.getRow() == null);
    Assert.assertEquals(0, result.raw().length);
  }

  private void validateSizeAndEmpty(Object[] results, int expectedSize) {
    // Validate got back the same number of Result objects, all empty
    Assert.assertEquals(expectedSize, results.length);
    for (Object result : results) {
      validateEmpty(result);
    }
  }

  @org.junit.Rule
  public org.apache.hadoop.hbase.ResourceCheckerJUnitRule cu =
    new org.apache.hadoop.hbase.ResourceCheckerJUnitRule();
}

