/**
 * Copyright 2010 The Apache Software Foundation
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
package org.apache.hadoop.hbase.regionserver;


import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.DoNotRetryIOException;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.HBaseTestCase;
import org.apache.hadoop.hbase.HBaseTestingUtility;
import org.apache.hadoop.hbase.HColumnDescriptor;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.HConstants.OperationStatusCode;
import org.apache.hadoop.hbase.HDFSBlocksDistribution;
import org.apache.hadoop.hbase.HRegionInfo;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.MediumTests;
import org.apache.hadoop.hbase.MiniHBaseCluster;
import org.apache.hadoop.hbase.MultithreadedTestUtil;
import org.apache.hadoop.hbase.MultithreadedTestUtil.RepeatingTestThread;
import org.apache.hadoop.hbase.MultithreadedTestUtil.TestThread;
import org.apache.hadoop.hbase.client.Append;
import org.apache.hadoop.hbase.client.Delete;
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.HTable;
import org.apache.hadoop.hbase.client.Increment;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.filter.BinaryComparator;
import org.apache.hadoop.hbase.filter.ColumnCountGetFilter;
import org.apache.hadoop.hbase.filter.CompareFilter.CompareOp;
import org.apache.hadoop.hbase.filter.Filter;
import org.apache.hadoop.hbase.filter.FilterBase;
import org.apache.hadoop.hbase.filter.FilterList;
import org.apache.hadoop.hbase.filter.NullComparator;
import org.apache.hadoop.hbase.filter.PrefixFilter;
import org.apache.hadoop.hbase.filter.SingleColumnValueExcludeFilter;
import org.apache.hadoop.hbase.filter.SingleColumnValueFilter;
import org.apache.hadoop.hbase.monitoring.MonitoredRPCHandler;
import org.apache.hadoop.hbase.monitoring.MonitoredTask;
import org.apache.hadoop.hbase.monitoring.TaskMonitor;
import org.apache.hadoop.hbase.regionserver.HRegion.RegionScannerImpl;
import org.apache.hadoop.hbase.regionserver.StoreFile.BloomType;
import org.apache.hadoop.hbase.regionserver.metrics.SchemaMetrics;
import org.apache.hadoop.hbase.regionserver.wal.HLog;
import org.apache.hadoop.hbase.regionserver.wal.HLogKey;
import org.apache.hadoop.hbase.regionserver.wal.WALEdit;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.EnvironmentEdgeManager;
import org.apache.hadoop.hbase.util.EnvironmentEdgeManagerTestHelper;
import org.apache.hadoop.hbase.util.IncrementingEnvironmentEdge;
import org.apache.hadoop.hbase.util.ManualEnvironmentEdge;
import org.apache.hadoop.hbase.util.Pair;
import org.apache.hadoop.hbase.util.PairOfSameType;
import org.apache.hadoop.hbase.util.Threads;
import org.junit.Assert;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.mockito.Mockito;

import com.google.common.collect.Lists;


/**
 * Basic stand-alone testing of HRegion.
 *
 * A lot of the meta information for an HRegion now lives inside other
 * HRegions or in the HBaseMaster, so only basic testing is possible.
 */
@Category(MediumTests.class)
@SuppressWarnings("deprecation")
public class TestHRegion extends HBaseTestCase {
  // Do not spin up clusters in here.  If you need to spin up a cluster, do it
  // over in TestHRegionOnCluster.
  static final Log LOG = LogFactory.getLog(TestHRegion.class);

  private static final String COLUMN_FAMILY = "MyCF";

  HRegion region = null;
  private static final HBaseTestingUtility TEST_UTIL = new HBaseTestingUtility();
  private static final String DIR = TEST_UTIL.getDataTestDir("TestHRegion").toString();

  private final int MAX_VERSIONS = 2;

  // Test names
  protected final byte[] tableName = Bytes.toBytes("testtable");;
  protected final byte[] qual1 = Bytes.toBytes("qual1");
  protected final byte[] qual2 = Bytes.toBytes("qual2");
  protected final byte[] qual3 = Bytes.toBytes("qual3");
  protected final byte[] value1 = Bytes.toBytes("value1");
  protected final byte[] value2 = Bytes.toBytes("value2");
  protected final byte [] row = Bytes.toBytes("rowA");
  protected final byte [] row2 = Bytes.toBytes("rowB");


  private Map<String, Long> startingMetrics;

  /**
   * @see org.apache.hadoop.hbase.HBaseTestCase#setUp()
   */
  @Override
  protected void setUp() throws Exception {
    startingMetrics = SchemaMetrics.getMetricsSnapshot();
    super.setUp();
  }

  @Override
  protected void tearDown() throws Exception {
    super.tearDown();
    EnvironmentEdgeManagerTestHelper.reset();
    SchemaMetrics.validateMetricChanges(startingMetrics);
  }

  //////////////////////////////////////////////////////////////////////////////
  // New tests that doesn't spin up a mini cluster but rather just test the
  // individual code pieces in the HRegion. Putting files locally in
  // /tmp/testtable
  //////////////////////////////////////////////////////////////////////////////

  public void testCompactionAffectedByScanners() throws Exception {
    String method = "testCompactionAffectedByScanners";
    byte[] tableName = Bytes.toBytes(method);
    byte[] family = Bytes.toBytes("family");
    this.region = initHRegion(tableName, method, conf, family);

    Put put = new Put(Bytes.toBytes("r1"));
    put.add(family, Bytes.toBytes("q1"), Bytes.toBytes("v1"));
    region.put(put);
    region.flushcache();


    Scan scan = new Scan();
    scan.setMaxVersions(3);
    // open the first scanner
    RegionScanner scanner1 = region.getScanner(scan);

    Delete delete = new Delete(Bytes.toBytes("r1"));
    region.delete(delete, null, false);
    region.flushcache();

    // open the second scanner
    RegionScanner scanner2 = region.getScanner(scan);

    List<KeyValue> results = new ArrayList<KeyValue>();

    System.out.println("Smallest read point:" + region.getSmallestReadPoint());

    // make a major compaction
    region.compactStores(true);

    // open the third scanner
    RegionScanner scanner3 = region.getScanner(scan);

    // get data from scanner 1, 2, 3 after major compaction
    scanner1.next(results);
    System.out.println(results);
    assertEquals(1, results.size());

    results.clear();
    scanner2.next(results);
    System.out.println(results);
    assertEquals(0, results.size());

    results.clear();
    scanner3.next(results);
    System.out.println(results);
    assertEquals(0, results.size());
  }

  @Test
  public void testToShowNPEOnRegionScannerReseek() throws Exception{
    String method = "testToShowNPEOnRegionScannerReseek";
    byte[] tableName = Bytes.toBytes(method);
    byte[] family = Bytes.toBytes("family");
    this.region = initHRegion(tableName, method, conf, family);

    Put put = new Put(Bytes.toBytes("r1"));
    put.add(family, Bytes.toBytes("q1"), Bytes.toBytes("v1"));
    region.put(put);
    put = new Put(Bytes.toBytes("r2"));
    put.add(family, Bytes.toBytes("q1"), Bytes.toBytes("v1"));
    region.put(put);
    region.flushcache();


    Scan scan = new Scan();
    scan.setMaxVersions(3);
    // open the first scanner
    RegionScanner scanner1 = region.getScanner(scan);

    System.out.println("Smallest read point:" + region.getSmallestReadPoint());
    
    region.compactStores(true);

    scanner1.reseek(Bytes.toBytes("r2"));
    List<KeyValue> results = new ArrayList<KeyValue>();
    scanner1.next(results);
    KeyValue keyValue = results.get(0);
    Assert.assertTrue(Bytes.compareTo(keyValue.getRow(), Bytes.toBytes("r2")) == 0);
    scanner1.close();
  }

  public void testSkipRecoveredEditsReplay() throws Exception {
    String method = "testSkipRecoveredEditsReplay";
    byte[] tableName = Bytes.toBytes(method);
    byte[] family = Bytes.toBytes("family");
    this.region = initHRegion(tableName, method, conf, family);
    try {
      Path regiondir = region.getRegionDir();
      FileSystem fs = region.getFilesystem();
      byte[] regionName = region.getRegionInfo().getEncodedNameAsBytes();

      Path recoveredEditsDir = HLog.getRegionDirRecoveredEditsDir(regiondir);

      long maxSeqId = 1050;
      long minSeqId = 1000;

      for (long i = minSeqId; i <= maxSeqId; i += 10) {
        Path recoveredEdits = new Path(recoveredEditsDir, String.format("%019d", i));
        fs.create(recoveredEdits);
        HLog.Writer writer = HLog.createWriter(fs, recoveredEdits, conf);

        long time = System.nanoTime();
        WALEdit edit = new WALEdit();
        edit.add(new KeyValue(row, family, Bytes.toBytes(i),
            time, KeyValue.Type.Put, Bytes.toBytes(i)));
        writer.append(new HLog.Entry(new HLogKey(regionName, tableName,
            i, time, HConstants.DEFAULT_CLUSTER_ID), edit));

        writer.close();
      }
      MonitoredTask status = TaskMonitor.get().createStatus(method);
      long seqId = region.replayRecoveredEditsIfAny(regiondir, minSeqId-1, null, status);
      assertEquals(maxSeqId, seqId);
      Get get = new Get(row);
      Result result = region.get(get, null);
      for (long i = minSeqId; i <= maxSeqId; i += 10) {
        List<KeyValue> kvs = result.getColumn(family, Bytes.toBytes(i));
        assertEquals(1, kvs.size());
        assertEquals(Bytes.toBytes(i), kvs.get(0).getValue());
      }
    } finally {
      HRegion.closeHRegion(this.region);
      this.region = null;
    }
  }

  public void testSkipRecoveredEditsReplaySomeIgnored() throws Exception {
    String method = "testSkipRecoveredEditsReplaySomeIgnored";
    byte[] tableName = Bytes.toBytes(method);
    byte[] family = Bytes.toBytes("family");
    this.region = initHRegion(tableName, method, conf, family);
    try {
      Path regiondir = region.getRegionDir();
      FileSystem fs = region.getFilesystem();
      byte[] regionName = region.getRegionInfo().getEncodedNameAsBytes();

      Path recoveredEditsDir = HLog.getRegionDirRecoveredEditsDir(regiondir);

      long maxSeqId = 1050;
      long minSeqId = 1000;

      for (long i = minSeqId; i <= maxSeqId; i += 10) {
        Path recoveredEdits = new Path(recoveredEditsDir, String.format("%019d", i));
        fs.create(recoveredEdits);
        HLog.Writer writer = HLog.createWriter(fs, recoveredEdits, conf);

        long time = System.nanoTime();
        WALEdit edit = new WALEdit();
        edit.add(new KeyValue(row, family, Bytes.toBytes(i),
            time, KeyValue.Type.Put, Bytes.toBytes(i)));
        writer.append(new HLog.Entry(new HLogKey(regionName, tableName,
            i, time, HConstants.DEFAULT_CLUSTER_ID), edit));

        writer.close();
      }
      long recoverSeqId = 1030;
      MonitoredTask status = TaskMonitor.get().createStatus(method);
      long seqId = region.replayRecoveredEditsIfAny(regiondir, recoverSeqId-1, null, status);
      assertEquals(maxSeqId, seqId);
      Get get = new Get(row);
      Result result = region.get(get, null);
      for (long i = minSeqId; i <= maxSeqId; i += 10) {
        List<KeyValue> kvs = result.getColumn(family, Bytes.toBytes(i));
        if (i < recoverSeqId) {
          assertEquals(0, kvs.size());
        } else {
          assertEquals(1, kvs.size());
          assertEquals(Bytes.toBytes(i), kvs.get(0).getValue());
        }
      }
    } finally {
      HRegion.closeHRegion(this.region);
      this.region = null;
    }
  }

  public void testSkipRecoveredEditsReplayAllIgnored() throws Exception {
    String method = "testSkipRecoveredEditsReplayAllIgnored";
    byte[] tableName = Bytes.toBytes(method);
    byte[] family = Bytes.toBytes("family");
    this.region = initHRegion(tableName, method, conf, family);
    try {
      Path regiondir = region.getRegionDir();
      FileSystem fs = region.getFilesystem();

      Path recoveredEditsDir = HLog.getRegionDirRecoveredEditsDir(regiondir);
      for (int i = 1000; i < 1050; i += 10) {
        Path recoveredEdits = new Path(
            recoveredEditsDir, String.format("%019d", i));
        FSDataOutputStream dos=  fs.create(recoveredEdits);
        dos.writeInt(i);
        dos.close();
      }
      long minSeqId = 2000;
      Path recoveredEdits = new Path(
          recoveredEditsDir, String.format("%019d", minSeqId-1));
      FSDataOutputStream dos=  fs.create(recoveredEdits);
      dos.close();
      long seqId = region.replayRecoveredEditsIfAny(regiondir, minSeqId, null, null);
      assertEquals(minSeqId, seqId);
    } finally {
      HRegion.closeHRegion(this.region);
      this.region = null;
    }
  }

  public void testGetWhileRegionClose() throws IOException {
    Configuration hc = initSplit();
    int numRows = 100;
    byte [][] families = {fam1, fam2, fam3};

    //Setting up region
    String method = this.getName();
    this.region = initHRegion(tableName, method, hc, families);
    try {
      // Put data in region
      final int startRow = 100;
      putData(startRow, numRows, qual1, families);
      putData(startRow, numRows, qual2, families);
      putData(startRow, numRows, qual3, families);
      // this.region.flushcache();
      final AtomicBoolean done = new AtomicBoolean(false);
      final AtomicInteger gets = new AtomicInteger(0);
      GetTillDoneOrException [] threads = new GetTillDoneOrException[10];
      try {
        // Set ten threads running concurrently getting from the region.
        for (int i = 0; i < threads.length / 2; i++) {
          threads[i] = new GetTillDoneOrException(i, Bytes.toBytes("" + startRow),
              done, gets);
          threads[i].setDaemon(true);
          threads[i].start();
        }
        // Artificially make the condition by setting closing flag explicitly.
        // I can't make the issue happen with a call to region.close().
        this.region.closing.set(true);
        for (int i = threads.length / 2; i < threads.length; i++) {
          threads[i] = new GetTillDoneOrException(i, Bytes.toBytes("" + startRow),
              done, gets);
          threads[i].setDaemon(true);
          threads[i].start();
        }
      } finally {
        if (this.region != null) {
          this.region.close();
          this.region.getLog().closeAndDelete();
        }
      }
      done.set(true);
      for (GetTillDoneOrException t: threads) {
        try {
          t.join();
        } catch (InterruptedException e) {
          e.printStackTrace();
        }
        if (t.e != null) {
          LOG.info("Exception=" + t.e);
          assertFalse("Found a NPE in " + t.getName(),
              t.e instanceof NullPointerException);
        }
      }
    } finally {
      HRegion.closeHRegion(this.region);
      this.region = null;
    }
  }

  /*
   * Thread that does get on single row until 'done' flag is flipped.  If an
   * exception causes us to fail, it records it.
   */
  class GetTillDoneOrException extends Thread {
    private final Get g;
    private final AtomicBoolean done;
    private final AtomicInteger count;
    private Exception e;

    GetTillDoneOrException(final int i, final byte[] r, final AtomicBoolean d,
        final AtomicInteger c) {
      super("getter." + i);
      this.g = new Get(r);
      this.done = d;
      this.count = c;
    }

    @Override
    public void run() {
      while (!this.done.get()) {
        try {
          assertTrue(region.get(g, null).size() > 0);
          this.count.incrementAndGet();
        } catch (Exception e) {
          this.e = e;
          break;
        }
      }
    }
  }

  /*
   * An involved filter test.  Has multiple column families and deletes in mix.
   */
  public void testWeirdCacheBehaviour() throws Exception {
    byte[] TABLE = Bytes.toBytes("testWeirdCacheBehaviour");
    byte[][] FAMILIES = new byte[][] { Bytes.toBytes("trans-blob"),
        Bytes.toBytes("trans-type"), Bytes.toBytes("trans-date"),
        Bytes.toBytes("trans-tags"), Bytes.toBytes("trans-group") };
    this.region = initHRegion(TABLE, getName(), conf, FAMILIES);
    try {
      String value = "this is the value";
      String value2 = "this is some other value";
      String keyPrefix1 = "prefix1"; // UUID.randomUUID().toString();
      String keyPrefix2 = "prefix2"; // UUID.randomUUID().toString();
      String keyPrefix3 = "prefix3"; // UUID.randomUUID().toString();
      putRows(this.region, 3, value, keyPrefix1);
      putRows(this.region, 3, value, keyPrefix2);
      putRows(this.region, 3, value, keyPrefix3);
      // this.region.flushCommits();
      putRows(this.region, 3, value2, keyPrefix1);
      putRows(this.region, 3, value2, keyPrefix2);
      putRows(this.region, 3, value2, keyPrefix3);
      System.out.println("Checking values for key: " + keyPrefix1);
      assertEquals("Got back incorrect number of rows from scan", 3,
          getNumberOfRows(keyPrefix1, value2, this.region));
      System.out.println("Checking values for key: " + keyPrefix2);
      assertEquals("Got back incorrect number of rows from scan", 3,
          getNumberOfRows(keyPrefix2, value2, this.region));
      System.out.println("Checking values for key: " + keyPrefix3);
      assertEquals("Got back incorrect number of rows from scan", 3,
          getNumberOfRows(keyPrefix3, value2, this.region));
      deleteColumns(this.region, value2, keyPrefix1);
      deleteColumns(this.region, value2, keyPrefix2);
      deleteColumns(this.region, value2, keyPrefix3);
      System.out.println("Starting important checks.....");
      assertEquals("Got back incorrect number of rows from scan: " + keyPrefix1,
          0, getNumberOfRows(keyPrefix1, value2, this.region));
      assertEquals("Got back incorrect number of rows from scan: " + keyPrefix2,
          0, getNumberOfRows(keyPrefix2, value2, this.region));
      assertEquals("Got back incorrect number of rows from scan: " + keyPrefix3,
          0, getNumberOfRows(keyPrefix3, value2, this.region));
    } finally {
      HRegion.closeHRegion(this.region);
      this.region = null;
    }
  }

  public void testAppendWithReadOnlyTable() throws Exception {
    byte[] TABLE = Bytes.toBytes("readOnlyTable");
    this.region = initHRegion(TABLE, getName(), conf, true, Bytes.toBytes("somefamily"));
    boolean exceptionCaught = false;
    Append append = new Append(Bytes.toBytes("somerow"));
    append.add(Bytes.toBytes("somefamily"), Bytes.toBytes("somequalifier"), 
        Bytes.toBytes("somevalue"));
    try {
      region.append(append, false);
    } catch (IOException e) {
      exceptionCaught = true;
    } finally {
      HRegion.closeHRegion(this.region);
      this.region = null;
    }
    assertTrue(exceptionCaught == true);
  }

  public void testIncrWithReadOnlyTable() throws Exception {
    byte[] TABLE = Bytes.toBytes("readOnlyTable");
    this.region = initHRegion(TABLE, getName(), conf, true, Bytes.toBytes("somefamily"));
    boolean exceptionCaught = false;    
    Increment inc = new Increment(Bytes.toBytes("somerow"));
    inc.addColumn(Bytes.toBytes("somefamily"), Bytes.toBytes("somequalifier"), 1L);
    try {
      region.increment(inc, false);
    } catch (IOException e) {
      exceptionCaught = true;
    } finally {
      HRegion.closeHRegion(this.region);
      this.region = null;
    }
    assertTrue(exceptionCaught == true);
  }

  private void deleteColumns(HRegion r, String value, String keyPrefix)
  throws IOException {
    InternalScanner scanner = buildScanner(keyPrefix, value, r);
    int count = 0;
    boolean more = false;
    List<KeyValue> results = new ArrayList<KeyValue>();
    do {
      more = scanner.next(results);
      if (results != null && !results.isEmpty())
        count++;
      else
        break;
      Delete delete = new Delete(results.get(0).getRow());
      delete.deleteColumn(Bytes.toBytes("trans-tags"), Bytes.toBytes("qual2"));
      r.delete(delete, null, false);
      results.clear();
    } while (more);
    assertEquals("Did not perform correct number of deletes", 3, count);
  }

  private int getNumberOfRows(String keyPrefix, String value, HRegion r) throws Exception {
    InternalScanner resultScanner = buildScanner(keyPrefix, value, r);
    int numberOfResults = 0;
    List<KeyValue> results = new ArrayList<KeyValue>();
    boolean more = false;
    do {
      more = resultScanner.next(results);
      if (results != null && !results.isEmpty()) numberOfResults++;
      else break;
      for (KeyValue kv: results) {
        System.out.println("kv=" + kv.toString() + ", " + Bytes.toString(kv.getValue()));
      }
      results.clear();
    } while(more);
    return numberOfResults;
  }

  private InternalScanner buildScanner(String keyPrefix, String value, HRegion r)
  throws IOException {
    // Defaults FilterList.Operator.MUST_PASS_ALL.
    FilterList allFilters = new FilterList();
    allFilters.addFilter(new PrefixFilter(Bytes.toBytes(keyPrefix)));
    // Only return rows where this column value exists in the row.
    SingleColumnValueFilter filter =
      new SingleColumnValueFilter(Bytes.toBytes("trans-tags"),
        Bytes.toBytes("qual2"), CompareOp.EQUAL, Bytes.toBytes(value));
    filter.setFilterIfMissing(true);
    allFilters.addFilter(filter);
    Scan scan = new Scan();
    scan.addFamily(Bytes.toBytes("trans-blob"));
    scan.addFamily(Bytes.toBytes("trans-type"));
    scan.addFamily(Bytes.toBytes("trans-date"));
    scan.addFamily(Bytes.toBytes("trans-tags"));
    scan.addFamily(Bytes.toBytes("trans-group"));
    scan.setFilter(allFilters);
    return r.getScanner(scan);
  }

  private void putRows(HRegion r, int numRows, String value, String key)
  throws IOException {
    for (int i = 0; i < numRows; i++) {
      String row = key + "_" + i/* UUID.randomUUID().toString() */;
      System.out.println(String.format("Saving row: %s, with value %s", row,
        value));
      Put put = new Put(Bytes.toBytes(row));
      put.setWriteToWAL(false);
      put.add(Bytes.toBytes("trans-blob"), null,
        Bytes.toBytes("value for blob"));
      put.add(Bytes.toBytes("trans-type"), null, Bytes.toBytes("statement"));
      put.add(Bytes.toBytes("trans-date"), null,
        Bytes.toBytes("20090921010101999"));
      put.add(Bytes.toBytes("trans-tags"), Bytes.toBytes("qual2"),
        Bytes.toBytes(value));
      put.add(Bytes.toBytes("trans-group"), null,
        Bytes.toBytes("adhocTransactionGroupId"));
      r.put(put);
    }
  }

  public void testFamilyWithAndWithoutColon() throws Exception {
    byte [] b = Bytes.toBytes(getName());
    byte [] cf = Bytes.toBytes(COLUMN_FAMILY);
    this.region = initHRegion(b, getName(), conf, cf);
    try {
      Put p = new Put(b);
      byte [] cfwithcolon = Bytes.toBytes(COLUMN_FAMILY + ":");
      p.add(cfwithcolon, cfwithcolon, cfwithcolon);
      boolean exception = false;
      try {
        this.region.put(p);
      } catch (NoSuchColumnFamilyException e) {
        exception = true;
      }
      assertTrue(exception);
    } finally {
       HRegion.closeHRegion(this.region);
      this.region = null;
    }
  }

  @SuppressWarnings("unchecked")
  public void testBatchPut() throws Exception {
    byte[] b = Bytes.toBytes(getName());
    byte[] cf = Bytes.toBytes(COLUMN_FAMILY);
    byte[] qual = Bytes.toBytes("qual");
    byte[] val = Bytes.toBytes("val");
    this.region = initHRegion(b, getName(), conf, cf);
    try {
      HLog.getSyncTime(); // clear counter from prior tests
      assertEquals(0, HLog.getSyncTime().count);

      LOG.info("First a batch put with all valid puts");
      final Put[] puts = new Put[10];
      for (int i = 0; i < 10; i++) {
        puts[i] = new Put(Bytes.toBytes("row_" + i));
        puts[i].add(cf, qual, val);
      }

      OperationStatus[] codes = this.region.put(puts);
      assertEquals(10, codes.length);
      for (int i = 0; i < 10; i++) {
        assertEquals(OperationStatusCode.SUCCESS, codes[i]
            .getOperationStatusCode());
      }
      assertEquals(1, HLog.getSyncTime().count);

      LOG.info("Next a batch put with one invalid family");
      puts[5].add(Bytes.toBytes("BAD_CF"), qual, val);
      codes = this.region.put(puts);
      assertEquals(10, codes.length);
      for (int i = 0; i < 10; i++) {
        assertEquals((i == 5) ? OperationStatusCode.BAD_FAMILY :
          OperationStatusCode.SUCCESS, codes[i].getOperationStatusCode());
      }
      assertEquals(1, HLog.getSyncTime().count);

      LOG.info("Next a batch put that has to break into two batches to avoid a lock");
      Integer lockedRow = region.obtainRowLock(Bytes.toBytes("row_2"));

      MultithreadedTestUtil.TestContext ctx =
        new MultithreadedTestUtil.TestContext(conf);
      final AtomicReference<OperationStatus[]> retFromThread =
        new AtomicReference<OperationStatus[]>();
      TestThread putter = new TestThread(ctx) {
        @Override
        public void doWork() throws IOException {
          retFromThread.set(region.put(puts));
        }
      };
      LOG.info("...starting put thread while holding lock");
      ctx.addThread(putter);
      ctx.startThreads();
  
      LOG.info("...waiting for put thread to sync first time");
      long startWait = System.currentTimeMillis();
      while (HLog.getSyncTime().count == 0) {
        Thread.sleep(100);
        if (System.currentTimeMillis() - startWait > 10000) {
          fail("Timed out waiting for thread to sync first minibatch");
        }
      }
      LOG.info("...releasing row lock, which should let put thread continue");
      region.releaseRowLock(lockedRow);
      LOG.info("...joining on thread");
      ctx.stop();
      LOG.info("...checking that next batch was synced");
      assertEquals(1, HLog.getSyncTime().count);
      codes = retFromThread.get();
      for (int i = 0; i < 10; i++) {
        assertEquals((i == 5) ? OperationStatusCode.BAD_FAMILY :
          OperationStatusCode.SUCCESS, codes[i].getOperationStatusCode());
      }
  
      LOG.info("Nexta, a batch put which uses an already-held lock");
      lockedRow = region.obtainRowLock(Bytes.toBytes("row_2"));
      LOG.info("...obtained row lock");
      List<Pair<Put, Integer>> putsAndLocks = Lists.newArrayList();
      for (int i = 0; i < 10; i++) {
        Pair<Put, Integer> pair = new Pair<Put, Integer>(puts[i], null);
        if (i == 2) pair.setSecond(lockedRow);
        putsAndLocks.add(pair);
      }
  
      codes = region.put(putsAndLocks.toArray(new Pair[0]));
      LOG.info("...performed put");
      for (int i = 0; i < 10; i++) {
        assertEquals((i == 5) ? OperationStatusCode.BAD_FAMILY :
          OperationStatusCode.SUCCESS, codes[i].getOperationStatusCode());
      }
      // Make sure we didn't do an extra batch
      assertEquals(1, HLog.getSyncTime().count);
  
      // Make sure we still hold lock
      assertTrue(region.isRowLocked(lockedRow));
      LOG.info("...releasing lock");
      region.releaseRowLock(lockedRow);
    } finally {
      HRegion.closeHRegion(this.region);
       this.region = null;
    }
  }

  public void testBatchPutWithTsSlop() throws Exception {
    byte[] b = Bytes.toBytes(getName());
    byte[] cf = Bytes.toBytes(COLUMN_FAMILY);
    byte[] qual = Bytes.toBytes("qual");
    byte[] val = Bytes.toBytes("val");
    Configuration conf = HBaseConfiguration.create(this.conf);

    // add data with a timestamp that is too recent for range. Ensure assert
    conf.setInt("hbase.hregion.keyvalue.timestamp.slop.millisecs", 1000);
    this.region = initHRegion(b, getName(), conf, cf);

    try{
      HLog.getSyncTime(); // clear counter from prior tests
      assertEquals(0, HLog.getSyncTime().count);

      final Put[] puts = new Put[10];
      for (int i = 0; i < 10; i++) {
        puts[i] = new Put(Bytes.toBytes("row_" + i), Long.MAX_VALUE - 100);
        puts[i].add(cf, qual, val);
      }

      OperationStatus[] codes = this.region.put(puts);
      assertEquals(10, codes.length);
      for (int i = 0; i < 10; i++) {
        assertEquals(OperationStatusCode.SANITY_CHECK_FAILURE, codes[i]
            .getOperationStatusCode());
      }
      assertEquals(0, HLog.getSyncTime().count);


    } finally {
      HRegion.closeHRegion(this.region);
      this.region = null;
    }

  }

  //////////////////////////////////////////////////////////////////////////////
  // checkAndMutate tests
  //////////////////////////////////////////////////////////////////////////////
  public void testCheckAndMutate_WithEmptyRowValue() throws IOException {
    byte [] tableName = Bytes.toBytes("testtable");
    byte [] row1 = Bytes.toBytes("row1");
    byte [] fam1 = Bytes.toBytes("fam1");
    byte [] qf1  = Bytes.toBytes("qualifier");
    byte [] emptyVal  = new byte[] {};
    byte [] val1  = Bytes.toBytes("value1");
    byte [] val2  = Bytes.toBytes("value2");
    Integer lockId = null;

    //Setting up region
    String method = this.getName();
    this.region = initHRegion(tableName, method, conf, fam1);
    try {
      //Putting empty data in key
      Put put = new Put(row1);
      put.add(fam1, qf1, emptyVal);

      //checkAndPut with empty value
      boolean res = region.checkAndMutate(row1, fam1, qf1, CompareOp.EQUAL,
          new BinaryComparator(emptyVal), put, lockId, true);
      assertTrue(res);

      //Putting data in key
      put = new Put(row1);
      put.add(fam1, qf1, val1);

      //checkAndPut with correct value
      res = region.checkAndMutate(row1, fam1, qf1, CompareOp.EQUAL,
          new BinaryComparator(emptyVal), put, lockId, true);
      assertTrue(res);

      // not empty anymore
      res = region.checkAndMutate(row1, fam1, qf1, CompareOp.EQUAL,
          new BinaryComparator(emptyVal), put, lockId, true);
      assertFalse(res);

      Delete delete = new Delete(row1);
      delete.deleteColumn(fam1, qf1);
      res = region.checkAndMutate(row1, fam1, qf1, CompareOp.EQUAL,
          new BinaryComparator(emptyVal), delete, lockId, true);
      assertFalse(res);

      put = new Put(row1);
      put.add(fam1, qf1, val2);
      //checkAndPut with correct value
      res = region.checkAndMutate(row1, fam1, qf1, CompareOp.EQUAL,
          new BinaryComparator(val1), put, lockId, true);
      assertTrue(res);

      //checkAndDelete with correct value
      delete = new Delete(row1);
      delete.deleteColumn(fam1, qf1);
      delete.deleteColumn(fam1, qf1);
      res = region.checkAndMutate(row1, fam1, qf1, CompareOp.EQUAL,
          new BinaryComparator(val2), delete, lockId, true);
      assertTrue(res);

      delete = new Delete(row1);
      res = region.checkAndMutate(row1, fam1, qf1, CompareOp.EQUAL,
          new BinaryComparator(emptyVal), delete, lockId, true);
      assertTrue(res);

      //checkAndPut looking for a null value
      put = new Put(row1);
      put.add(fam1, qf1, val1);

      res = region.checkAndMutate(row1, fam1, qf1, CompareOp.EQUAL,
          new NullComparator(), put, lockId, true);
      assertTrue(res);
    } finally {
      HRegion.closeHRegion(this.region);
      this.region = null;
    }
  }

  public void testCheckAndMutate_WithWrongValue() throws IOException{
    byte [] tableName = Bytes.toBytes("testtable");
    byte [] row1 = Bytes.toBytes("row1");
    byte [] fam1 = Bytes.toBytes("fam1");
    byte [] qf1  = Bytes.toBytes("qualifier");
    byte [] val1  = Bytes.toBytes("value1");
    byte [] val2  = Bytes.toBytes("value2");
    Integer lockId = null;

    //Setting up region
    String method = this.getName();
    this.region = initHRegion(tableName, method, conf, fam1);
    try {
      //Putting data in key
      Put put = new Put(row1);
      put.add(fam1, qf1, val1);
      region.put(put);

      //checkAndPut with wrong value
      boolean res = region.checkAndMutate(row1, fam1, qf1, CompareOp.EQUAL,
          new BinaryComparator(val2), put, lockId, true);
      assertEquals(false, res);

      //checkAndDelete with wrong value
      Delete delete = new Delete(row1);
      delete.deleteFamily(fam1);
      res = region.checkAndMutate(row1, fam1, qf1, CompareOp.EQUAL,
          new BinaryComparator(val2), delete, lockId, true);
      assertEquals(false, res);
    } finally {
      HRegion.closeHRegion(this.region);
      this.region = null;
    }
  }

  public void testCheckAndMutate_WithCorrectValue() throws IOException{
    byte [] tableName = Bytes.toBytes("testtable");
    byte [] row1 = Bytes.toBytes("row1");
    byte [] fam1 = Bytes.toBytes("fam1");
    byte [] qf1  = Bytes.toBytes("qualifier");
    byte [] val1  = Bytes.toBytes("value1");
    Integer lockId = null;

    //Setting up region
    String method = this.getName();
    this.region = initHRegion(tableName, method, conf, fam1);
    try {
      //Putting data in key
      Put put = new Put(row1);
      put.add(fam1, qf1, val1);
      region.put(put);

      //checkAndPut with correct value
      boolean res = region.checkAndMutate(row1, fam1, qf1, CompareOp.EQUAL,
          new BinaryComparator(val1), put, lockId, true);
      assertEquals(true, res);

      //checkAndDelete with correct value
      Delete delete = new Delete(row1);
      delete.deleteColumn(fam1, qf1);
      res = region.checkAndMutate(row1, fam1, qf1, CompareOp.EQUAL,
          new BinaryComparator(val1), put, lockId, true);
      assertEquals(true, res);
    } finally {
      HRegion.closeHRegion(this.region);
      this.region = null;
    }
  }

  public void testCheckAndPut_ThatPutWasWritten() throws IOException{
    byte [] tableName = Bytes.toBytes("testtable");
    byte [] row1 = Bytes.toBytes("row1");
    byte [] fam1 = Bytes.toBytes("fam1");
    byte [] fam2 = Bytes.toBytes("fam2");
    byte [] qf1  = Bytes.toBytes("qualifier");
    byte [] val1  = Bytes.toBytes("value1");
    byte [] val2  = Bytes.toBytes("value2");
    Integer lockId = null;

    byte [][] families = {fam1, fam2};

    //Setting up region
    String method = this.getName();
    this.region = initHRegion(tableName, method, conf, families);
    try {
      //Putting data in the key to check
      Put put = new Put(row1);
      put.add(fam1, qf1, val1);
      region.put(put);

      //Creating put to add
      long ts = System.currentTimeMillis();
      KeyValue kv = new KeyValue(row1, fam2, qf1, ts, KeyValue.Type.Put, val2);
      put = new Put(row1);
      put.add(kv);

      //checkAndPut with wrong value
      Store store = region.getStore(fam1);
      store.memstore.kvset.size();

      boolean res = region.checkAndMutate(row1, fam1, qf1, CompareOp.EQUAL,
          new BinaryComparator(val1), put, lockId, true);
      assertEquals(true, res);
      store.memstore.kvset.size();

      Get get = new Get(row1);
      get.addColumn(fam2, qf1);
      KeyValue [] actual = region.get(get, null).raw();

      KeyValue [] expected = {kv};

      assertEquals(expected.length, actual.length);
      for(int i=0; i<actual.length; i++) {
        assertEquals(expected[i], actual[i]);
      }
    } finally {
      HRegion.closeHRegion(this.region);
      this.region = null;
    }
  }

  public void testCheckAndPut_wrongRowInPut() throws IOException {
    this.region = initHRegion(tableName, this.getName(), conf, COLUMNS);
    try {
      Put put = new Put(row2);
      put.add(fam1, qual1, value1);
      try {
        boolean res = region.checkAndMutate(row, fam1, qual1, CompareOp.EQUAL,
            new BinaryComparator(value2), put, null, false);
        fail();
      } catch (DoNotRetryIOException expected) {
        // expected exception.
      }
    } finally {
      HRegion.closeHRegion(this.region);
      this.region = null;
    }
  }

  public void testCheckAndDelete_ThatDeleteWasWritten() throws IOException{
    byte [] tableName = Bytes.toBytes("testtable");
    byte [] row1 = Bytes.toBytes("row1");
    byte [] fam1 = Bytes.toBytes("fam1");
    byte [] fam2 = Bytes.toBytes("fam2");
    byte [] qf1  = Bytes.toBytes("qualifier1");
    byte [] qf2  = Bytes.toBytes("qualifier2");
    byte [] qf3  = Bytes.toBytes("qualifier3");
    byte [] val1  = Bytes.toBytes("value1");
    byte [] val2  = Bytes.toBytes("value2");
    byte [] val3  = Bytes.toBytes("value3");
    byte[] emptyVal = new byte[] { };
    Integer lockId = null;

    byte [][] families = {fam1, fam2};

    //Setting up region
    String method = this.getName();
    this.region = initHRegion(tableName, method, conf, families);
    try {
      //Put content
      Put put = new Put(row1);
      put.add(fam1, qf1, val1);
      region.put(put);
      Threads.sleep(2);

      put = new Put(row1);
      put.add(fam1, qf1, val2);
      put.add(fam2, qf1, val3);
      put.add(fam2, qf2, val2);
      put.add(fam2, qf3, val1);
      put.add(fam1, qf3, val1);
      region.put(put);

      //Multi-column delete
      Delete delete = new Delete(row1);
      delete.deleteColumn(fam1, qf1);
      delete.deleteColumn(fam2, qf1);
      delete.deleteColumn(fam1, qf3);
      boolean res = region.checkAndMutate(row1, fam1, qf1, CompareOp.EQUAL,
          new BinaryComparator(val2), delete, lockId, true);
      assertEquals(true, res);

      Get get = new Get(row1);
      get.addColumn(fam1, qf1);
      get.addColumn(fam1, qf3);
      get.addColumn(fam2, qf2);
      Result r = region.get(get, null);
      assertEquals(2, r.size());
      assertEquals(val1, r.getValue(fam1, qf1));
      assertEquals(val2, r.getValue(fam2, qf2));

      //Family delete
      delete = new Delete(row1);
      delete.deleteFamily(fam2);
      res = region.checkAndMutate(row1, fam2, qf1, CompareOp.EQUAL,
          new BinaryComparator(emptyVal), delete, lockId, true);
      assertEquals(true, res);

      get = new Get(row1);
      r = region.get(get, null);
      assertEquals(1, r.size());
      assertEquals(val1, r.getValue(fam1, qf1));

      //Row delete
      delete = new Delete(row1);
      res = region.checkAndMutate(row1, fam1, qf1, CompareOp.EQUAL,
          new BinaryComparator(val1), delete, lockId, true);
      assertEquals(true, res);
      get = new Get(row1);
      r = region.get(get, null);
      assertEquals(0, r.size());
    } finally {
      HRegion.closeHRegion(this.region);
      this.region = null;
    }
  }

  //////////////////////////////////////////////////////////////////////////////
  // Delete tests
  //////////////////////////////////////////////////////////////////////////////
  public void testDelete_multiDeleteColumn() throws IOException {
    byte [] tableName = Bytes.toBytes("testtable");
    byte [] row1 = Bytes.toBytes("row1");
    byte [] fam1 = Bytes.toBytes("fam1");
    byte [] qual = Bytes.toBytes("qualifier");
    byte [] value = Bytes.toBytes("value");

    Put put = new Put(row1);
    put.add(fam1, qual, 1, value);
    put.add(fam1, qual, 2, value);

    String method = this.getName();
    this.region = initHRegion(tableName, method, conf, fam1);
    try {
      region.put(put);

      // We do support deleting more than 1 'latest' version
      Delete delete = new Delete(row1);
      delete.deleteColumn(fam1, qual);
      delete.deleteColumn(fam1, qual);
      region.delete(delete, null, false);

      Get get = new Get(row1);
      get.addFamily(fam1);
      Result r = region.get(get, null);
      assertEquals(0, r.size());
    } finally {
      HRegion.closeHRegion(this.region);
      this.region = null;
    }
  }

  public void testDelete_CheckFamily() throws IOException {
    byte [] tableName = Bytes.toBytes("testtable");
    byte [] row1 = Bytes.toBytes("row1");
    byte [] fam1 = Bytes.toBytes("fam1");
    byte [] fam2 = Bytes.toBytes("fam2");
    byte [] fam3 = Bytes.toBytes("fam3");
    byte [] fam4 = Bytes.toBytes("fam4");

    //Setting up region
    String method = this.getName();
    this.region = initHRegion(tableName, method, conf, fam1, fam2, fam3);
    try {
      List<KeyValue> kvs  = new ArrayList<KeyValue>();
      kvs.add(new KeyValue(row1, fam4, null, null));


      //testing existing family
      byte [] family = fam2;
      try {
        Map<byte[], List<KeyValue>> deleteMap = new HashMap<byte[], List<KeyValue>>();
        deleteMap.put(family, kvs);
        region.delete(deleteMap, HConstants.DEFAULT_CLUSTER_ID, true);
      } catch (Exception e) {
        assertTrue("Family " +new String(family)+ " does not exist", false);
      }

      //testing non existing family
      boolean ok = false;
      family = fam4;
      try {
        Map<byte[], List<KeyValue>> deleteMap = new HashMap<byte[], List<KeyValue>>();
        deleteMap.put(family, kvs);
        region.delete(deleteMap, HConstants.DEFAULT_CLUSTER_ID, true);
      } catch (Exception e) {
        ok = true;
      }
      assertEquals("Family " +new String(family)+ " does exist", true, ok);
    } finally {
      HRegion.closeHRegion(this.region);
      this.region = null;
    }
  }

  public void testDelete_mixed() throws IOException, InterruptedException {
    byte [] tableName = Bytes.toBytes("testtable");
    byte [] fam = Bytes.toBytes("info");
    byte [][] families = {fam};
    String method = this.getName();
    this.region = initHRegion(tableName, method, conf, families);
    try {
      EnvironmentEdgeManagerTestHelper.injectEdge(new IncrementingEnvironmentEdge());

      byte [] row = Bytes.toBytes("table_name");
      // column names
      byte [] serverinfo = Bytes.toBytes("serverinfo");
      byte [] splitA = Bytes.toBytes("splitA");
      byte [] splitB = Bytes.toBytes("splitB");

      // add some data:
      Put put = new Put(row);
      put.add(fam, splitA, Bytes.toBytes("reference_A"));
      region.put(put);

      put = new Put(row);
      put.add(fam, splitB, Bytes.toBytes("reference_B"));
      region.put(put);

      put = new Put(row);
      put.add(fam, serverinfo, Bytes.toBytes("ip_address"));
      region.put(put);

      // ok now delete a split:
      Delete delete = new Delete(row);
      delete.deleteColumns(fam, splitA);
      region.delete(delete, null, true);

      // assert some things:
      Get get = new Get(row).addColumn(fam, serverinfo);
      Result result = region.get(get, null);
      assertEquals(1, result.size());

      get = new Get(row).addColumn(fam, splitA);
      result = region.get(get, null);
      assertEquals(0, result.size());

      get = new Get(row).addColumn(fam, splitB);
      result = region.get(get, null);
      assertEquals(1, result.size());

      // Assert that after a delete, I can put.
      put = new Put(row);
      put.add(fam, splitA, Bytes.toBytes("reference_A"));
      region.put(put);
      get = new Get(row);
      result = region.get(get, null);
      assertEquals(3, result.size());

      // Now delete all... then test I can add stuff back
      delete = new Delete(row);
      region.delete(delete, null, false);
      assertEquals(0, region.get(get, null).size());

      region.put(new Put(row).add(fam, splitA, Bytes.toBytes("reference_A")));
      result = region.get(get, null);
      assertEquals(1, result.size());
    } finally {
      HRegion.closeHRegion(this.region);
      this.region = null;
    }
  }

  public void testDeleteRowWithFutureTs() throws IOException {
    byte [] tableName = Bytes.toBytes("testtable");
    byte [] fam = Bytes.toBytes("info");
    byte [][] families = {fam};
    String method = this.getName();
    this.region = initHRegion(tableName, method, conf, families);
    try {
      byte [] row = Bytes.toBytes("table_name");
      // column names
      byte [] serverinfo = Bytes.toBytes("serverinfo");

      // add data in the far future
      Put put = new Put(row);
      put.add(fam, serverinfo, HConstants.LATEST_TIMESTAMP-5,Bytes.toBytes("value"));
      region.put(put);

      // now delete something in the present
      Delete delete = new Delete(row);
      region.delete(delete, null, true);

      // make sure we still see our data
      Get get = new Get(row).addColumn(fam, serverinfo);
      Result result = region.get(get, null);
      assertEquals(1, result.size());

      // delete the future row
      delete = new Delete(row,HConstants.LATEST_TIMESTAMP-3,null);
      region.delete(delete, null, true);

      // make sure it is gone
      get = new Get(row).addColumn(fam, serverinfo);
      result = region.get(get, null);
      assertEquals(0, result.size());
    } finally {
      HRegion.closeHRegion(this.region);
      this.region = null;
    }
  }

  /**
   * Tests that the special LATEST_TIMESTAMP option for puts gets
   * replaced by the actual timestamp
   */
  public void testPutWithLatestTS() throws IOException {
    byte [] tableName = Bytes.toBytes("testtable");
    byte [] fam = Bytes.toBytes("info");
    byte [][] families = {fam};
    String method = this.getName();
    this.region = initHRegion(tableName, method, conf, families);
    try {
      byte [] row = Bytes.toBytes("row1");
      // column names
      byte [] qual = Bytes.toBytes("qual");

      // add data with LATEST_TIMESTAMP, put without WAL
      Put put = new Put(row);
      put.add(fam, qual, HConstants.LATEST_TIMESTAMP, Bytes.toBytes("value"));
      region.put(put, false);

      // Make sure it shows up with an actual timestamp
      Get get = new Get(row).addColumn(fam, qual);
      Result result = region.get(get, null);
      assertEquals(1, result.size());
      KeyValue kv = result.raw()[0];
      LOG.info("Got: " + kv);
      assertTrue("LATEST_TIMESTAMP was not replaced with real timestamp",
          kv.getTimestamp() != HConstants.LATEST_TIMESTAMP);

      // Check same with WAL enabled (historically these took different
      // code paths, so check both)
      row = Bytes.toBytes("row2");
      put = new Put(row);
      put.add(fam, qual, HConstants.LATEST_TIMESTAMP, Bytes.toBytes("value"));
      region.put(put, true);

      // Make sure it shows up with an actual timestamp
      get = new Get(row).addColumn(fam, qual);
      result = region.get(get, null);
      assertEquals(1, result.size());
      kv = result.raw()[0];
      LOG.info("Got: " + kv);
      assertTrue("LATEST_TIMESTAMP was not replaced with real timestamp",
          kv.getTimestamp() != HConstants.LATEST_TIMESTAMP);
    } finally {
      HRegion.closeHRegion(this.region);
      this.region = null;
    }

  }


  /**
   * Tests that there is server-side filtering for invalid timestamp upper
   * bound. Note that the timestamp lower bound is automatically handled for us
   * by the TTL field.
   */
  public void testPutWithTsSlop() throws IOException {
    byte[] tableName = Bytes.toBytes("testtable");
    byte[] fam = Bytes.toBytes("info");
    byte[][] families = { fam };
    String method = this.getName();
    Configuration conf = HBaseConfiguration.create(this.conf);

    // add data with a timestamp that is too recent for range. Ensure assert
    conf.setInt("hbase.hregion.keyvalue.timestamp.slop.millisecs", 1000);
    this.region = initHRegion(tableName, method, conf, families);
    boolean caughtExcep = false;
    try {
      try {
        // no TS specified == use latest. should not error
        region.put(new Put(row).add(fam, Bytes.toBytes("qual"), Bytes
            .toBytes("value")), false);
        // TS out of range. should error
        region.put(new Put(row).add(fam, Bytes.toBytes("qual"),
            System.currentTimeMillis() + 2000,
            Bytes.toBytes("value")), false);
        fail("Expected IOE for TS out of configured timerange");
      } catch (DoNotRetryIOException ioe) {
        LOG.debug("Received expected exception", ioe);
        caughtExcep = true;
      }
      assertTrue("Should catch FailedSanityCheckException", caughtExcep);
    } finally {
      HRegion.closeHRegion(this.region);
      this.region = null;
    }
  }

  public void testScanner_DeleteOneFamilyNotAnother() throws IOException {
    byte [] tableName = Bytes.toBytes("test_table");
    byte [] fam1 = Bytes.toBytes("columnA");
    byte [] fam2 = Bytes.toBytes("columnB");
    this.region = initHRegion(tableName, getName(), conf, fam1, fam2);
    try {
      byte [] rowA = Bytes.toBytes("rowA");
      byte [] rowB = Bytes.toBytes("rowB");

      byte [] value = Bytes.toBytes("value");

      Delete delete = new Delete(rowA);
      delete.deleteFamily(fam1);

      region.delete(delete, null, true);

      // now create data.
      Put put = new Put(rowA);
      put.add(fam2, null, value);
      region.put(put);

      put = new Put(rowB);
      put.add(fam1, null, value);
      put.add(fam2, null, value);
      region.put(put);

      Scan scan = new Scan();
      scan.addFamily(fam1).addFamily(fam2);
      InternalScanner s = region.getScanner(scan);
      List<KeyValue> results = new ArrayList<KeyValue>();
      s.next(results);
      assertTrue(Bytes.equals(rowA, results.get(0).getRow()));

      results.clear();
      s.next(results);
      assertTrue(Bytes.equals(rowB, results.get(0).getRow()));
    } finally {
      HRegion.closeHRegion(this.region);
      this.region = null;
    }
  }

  public void testDeleteColumns_PostInsert() throws IOException,
      InterruptedException {
    Delete delete = new Delete(row);
    delete.deleteColumns(fam1, qual1);
    doTestDelete_AndPostInsert(delete);
  }

  public void testDeleteFamily_PostInsert() throws IOException, InterruptedException {
    Delete delete = new Delete(row);
    delete.deleteFamily(fam1);
    doTestDelete_AndPostInsert(delete);
  }

  public void doTestDelete_AndPostInsert(Delete delete)
      throws IOException, InterruptedException {
    this.region = initHRegion(tableName, getName(), conf, fam1);
    try {
      EnvironmentEdgeManagerTestHelper.injectEdge(new IncrementingEnvironmentEdge());
      Put put = new Put(row);
      put.add(fam1, qual1, value1);
      region.put(put);

      // now delete the value:
      region.delete(delete, null, true);


      // ok put data:
      put = new Put(row);
      put.add(fam1, qual1, value2);
      region.put(put);

      // ok get:
      Get get = new Get(row);
      get.addColumn(fam1, qual1);

      Result r = region.get(get, null);
      assertEquals(1, r.size());
      assertByteEquals(value2, r.getValue(fam1, qual1));

      // next:
      Scan scan = new Scan(row);
      scan.addColumn(fam1, qual1);
      InternalScanner s = region.getScanner(scan);

      List<KeyValue> results = new ArrayList<KeyValue>();
      assertEquals(false, s.next(results));
      assertEquals(1, results.size());
      KeyValue kv = results.get(0);

      assertByteEquals(value2, kv.getValue());
      assertByteEquals(fam1, kv.getFamily());
      assertByteEquals(qual1, kv.getQualifier());
      assertByteEquals(row, kv.getRow());
    } finally {
      HRegion.closeHRegion(this.region);
      this.region = null;
    }
  }

  public void testDelete_CheckTimestampUpdated()
  throws IOException {
    byte [] row1 = Bytes.toBytes("row1");
    byte [] col1 = Bytes.toBytes("col1");
    byte [] col2 = Bytes.toBytes("col2");
    byte [] col3 = Bytes.toBytes("col3");

    //Setting up region
    String method = this.getName();
    this.region = initHRegion(tableName, method, conf, fam1);
    try {
      //Building checkerList
      List<KeyValue> kvs  = new ArrayList<KeyValue>();
      kvs.add(new KeyValue(row1, fam1, col1, null));
      kvs.add(new KeyValue(row1, fam1, col2, null));
      kvs.add(new KeyValue(row1, fam1, col3, null));

      Map<byte[], List<KeyValue>> deleteMap = new HashMap<byte[], List<KeyValue>>();
      deleteMap.put(fam1, kvs);
      region.delete(deleteMap, HConstants.DEFAULT_CLUSTER_ID, true);

      // extract the key values out the memstore:
      // This is kinda hacky, but better than nothing...
      long now = System.currentTimeMillis();
      KeyValue firstKv = region.getStore(fam1).memstore.kvset.first();
      assertTrue(firstKv.getTimestamp() <= now);
      now = firstKv.getTimestamp();
      for (KeyValue kv: region.getStore(fam1).memstore.kvset) {
        assertTrue(kv.getTimestamp() <= now);
        now = kv.getTimestamp();
      }
    } finally {
      HRegion.closeHRegion(this.region);
      this.region = null;
    }
  }

  //////////////////////////////////////////////////////////////////////////////
  // Get tests
  //////////////////////////////////////////////////////////////////////////////
  public void testGet_FamilyChecker() throws IOException {
    byte [] tableName = Bytes.toBytes("testtable");
    byte [] row1 = Bytes.toBytes("row1");
    byte [] fam1 = Bytes.toBytes("fam1");
    byte [] fam2 = Bytes.toBytes("False");
    byte [] col1 = Bytes.toBytes("col1");

    //Setting up region
    String method = this.getName();
    this.region = initHRegion(tableName, method, conf, fam1);
    try {
      Get get = new Get(row1);
      get.addColumn(fam2, col1);

      //Test
      try {
        region.get(get, null);
      } catch (DoNotRetryIOException e) {
        assertFalse(false);
        return;
      }
      assertFalse(true);
    } finally {
      HRegion.closeHRegion(this.region);
      this.region = null;
    }
  }

  public void testGet_Basic() throws IOException {
    byte [] tableName = Bytes.toBytes("testtable");
    byte [] row1 = Bytes.toBytes("row1");
    byte [] fam1 = Bytes.toBytes("fam1");
    byte [] col1 = Bytes.toBytes("col1");
    byte [] col2 = Bytes.toBytes("col2");
    byte [] col3 = Bytes.toBytes("col3");
    byte [] col4 = Bytes.toBytes("col4");
    byte [] col5 = Bytes.toBytes("col5");

    //Setting up region
    String method = this.getName();
    this.region = initHRegion(tableName, method, conf, fam1);
    try {
      //Add to memstore
      Put put = new Put(row1);
      put.add(fam1, col1, null);
      put.add(fam1, col2, null);
      put.add(fam1, col3, null);
      put.add(fam1, col4, null);
      put.add(fam1, col5, null);
      region.put(put);

      Get get = new Get(row1);
      get.addColumn(fam1, col2);
      get.addColumn(fam1, col4);
      //Expected result
      KeyValue kv1 = new KeyValue(row1, fam1, col2);
      KeyValue kv2 = new KeyValue(row1, fam1, col4);
      KeyValue [] expected = {kv1, kv2};

      //Test
      Result res = region.get(get, null);
      assertEquals(expected.length, res.size());
      for(int i=0; i<res.size(); i++){
        assertEquals(0,
            Bytes.compareTo(expected[i].getRow(), res.raw()[i].getRow()));
        assertEquals(0,
            Bytes.compareTo(expected[i].getFamily(), res.raw()[i].getFamily()));
        assertEquals(0,
            Bytes.compareTo(
                expected[i].getQualifier(), res.raw()[i].getQualifier()));
      }

      // Test using a filter on a Get
      Get g = new Get(row1);
      final int count = 2;
      g.setFilter(new ColumnCountGetFilter(count));
      res = region.get(g, null);
      assertEquals(count, res.size());
    } finally {
      HRegion.closeHRegion(this.region);
      this.region = null;
    }
  }

  public void testGet_Empty() throws IOException {
    byte [] tableName = Bytes.toBytes("emptytable");
    byte [] row = Bytes.toBytes("row");
    byte [] fam = Bytes.toBytes("fam");

    String method = this.getName();
    this.region = initHRegion(tableName, method, conf, fam);
    try {
      Get get = new Get(row);
      get.addFamily(fam);
      Result r = region.get(get, null);

      assertTrue(r.isEmpty());
    } finally {
      HRegion.closeHRegion(this.region);
      this.region = null;
    }
  }

  //Test that checked if there was anything special when reading from the ROOT
  //table. To be able to use this test you need to comment the part in
  //HTableDescriptor that checks for '-' and '.'. You also need to remove the
  //s in the beginning of the name.
  public void stestGet_Root() throws IOException {
    //Setting up region
    String method = this.getName();
    this.region = initHRegion(HConstants.ROOT_TABLE_NAME,
      method, conf, HConstants.CATALOG_FAMILY);
    try {
      //Add to memstore
      Put put = new Put(HConstants.EMPTY_START_ROW);
      put.add(HConstants.CATALOG_FAMILY, HConstants.REGIONINFO_QUALIFIER, null);
      region.put(put);

      Get get = new Get(HConstants.EMPTY_START_ROW);
      get.addColumn(HConstants.CATALOG_FAMILY, HConstants.REGIONINFO_QUALIFIER);

      //Expected result
      KeyValue kv1 = new KeyValue(HConstants.EMPTY_START_ROW,
          HConstants.CATALOG_FAMILY, HConstants.REGIONINFO_QUALIFIER);
      KeyValue [] expected = {kv1};

      //Test from memstore
      Result res = region.get(get, null);

      assertEquals(expected.length, res.size());
      for(int i=0; i<res.size(); i++){
        assertEquals(0,
            Bytes.compareTo(expected[i].getRow(), res.raw()[i].getRow()));
        assertEquals(0,
            Bytes.compareTo(expected[i].getFamily(), res.raw()[i].getFamily()));
        assertEquals(0,
            Bytes.compareTo(
                expected[i].getQualifier(), res.raw()[i].getQualifier()));
      }

      //flush
      region.flushcache();

      //test2
      res = region.get(get, null);

      assertEquals(expected.length, res.size());
      for(int i=0; i<res.size(); i++){
        assertEquals(0,
            Bytes.compareTo(expected[i].getRow(), res.raw()[i].getRow()));
        assertEquals(0,
            Bytes.compareTo(expected[i].getFamily(), res.raw()[i].getFamily()));
        assertEquals(0,
            Bytes.compareTo(
                expected[i].getQualifier(), res.raw()[i].getQualifier()));
      }

      //Scan
      Scan scan = new Scan();
      scan.addFamily(HConstants.CATALOG_FAMILY);
      InternalScanner s = region.getScanner(scan);
      List<KeyValue> result = new ArrayList<KeyValue>();
      s.next(result);

      assertEquals(expected.length, result.size());
      for(int i=0; i<res.size(); i++){
        assertEquals(0,
            Bytes.compareTo(expected[i].getRow(), result.get(i).getRow()));
        assertEquals(0,
            Bytes.compareTo(expected[i].getFamily(), result.get(i).getFamily()));
        assertEquals(0,
            Bytes.compareTo(
                expected[i].getQualifier(), result.get(i).getQualifier()));
      }
    } finally {
      HRegion.closeHRegion(this.region);
      this.region = null;
    }
  }

  //////////////////////////////////////////////////////////////////////////////
  // Lock test
  //////////////////////////////////////////////////////////////////////////////
  public void testLocks() throws IOException{
    byte [] tableName = Bytes.toBytes("testtable");
    byte [][] families = {fam1, fam2, fam3};

    Configuration hc = initSplit();
    //Setting up region
    String method = this.getName();
    this.region = initHRegion(tableName, method, hc, families);
    try {
      final int threadCount = 10;
      final int lockCount = 10;

      List<Thread>threads = new ArrayList<Thread>(threadCount);
      for (int i = 0; i < threadCount; i++) {
        threads.add(new Thread(Integer.toString(i)) {
          @Override
          public void run() {
            Integer [] lockids = new Integer[lockCount];
            // Get locks.
            for (int i = 0; i < lockCount; i++) {
              try {
                byte [] rowid = Bytes.toBytes(Integer.toString(i));
                lockids[i] = region.obtainRowLock(rowid);
                assertEquals(rowid, region.getRowFromLock(lockids[i]));
                LOG.debug(getName() + " locked " + Bytes.toString(rowid));
              } catch (IOException e) {
                e.printStackTrace();
              }
            }
            LOG.debug(getName() + " set " +
                Integer.toString(lockCount) + " locks");

            // Abort outstanding locks.
            for (int i = lockCount - 1; i >= 0; i--) {
              region.releaseRowLock(lockids[i]);
              LOG.debug(getName() + " unlocked " + i);
            }
            LOG.debug(getName() + " released " +
                Integer.toString(lockCount) + " locks");
          }
        });
      }

      // Startup all our threads.
      for (Thread t : threads) {
        t.start();
      }

      // Now wait around till all are done.
      for (Thread t: threads) {
        while (t.isAlive()) {
          try {
            Thread.sleep(1);
          } catch (InterruptedException e) {
            // Go around again.
          }
        }
      }
      LOG.info("locks completed.");
    } finally {
      HRegion.closeHRegion(this.region);
      this.region = null;
    }
  }

  //////////////////////////////////////////////////////////////////////////////
  // Merge test
  //////////////////////////////////////////////////////////////////////////////
  public void testMerge() throws IOException {
    byte [] tableName = Bytes.toBytes("testtable");
    byte [][] families = {fam1, fam2, fam3};
    Configuration hc = initSplit();
    //Setting up region
    String method = this.getName();
    this.region = initHRegion(tableName, method, hc, families);
    try {
      LOG.info("" + addContent(region, fam3));
      region.flushcache();
      region.compactStores();
      byte [] splitRow = region.checkSplit();
      assertNotNull(splitRow);
      LOG.info("SplitRow: " + Bytes.toString(splitRow));
      HRegion [] subregions = splitRegion(region, splitRow);
      try {
        // Need to open the regions.
        for (int i = 0; i < subregions.length; i++) {
          openClosedRegion(subregions[i]);
          subregions[i].compactStores();
        }
        Path oldRegionPath = region.getRegionDir();
        Path oldRegion1 = subregions[0].getRegionDir();
        Path oldRegion2 = subregions[1].getRegionDir();
        long startTime = System.currentTimeMillis();
        region = HRegion.mergeAdjacent(subregions[0], subregions[1]);
        LOG.info("Merge regions elapsed time: " +
            ((System.currentTimeMillis() - startTime) / 1000.0));
        fs.delete(oldRegion1, true);
        fs.delete(oldRegion2, true);
        fs.delete(oldRegionPath, true);
        LOG.info("splitAndMerge completed.");
      } finally {
        for (int i = 0; i < subregions.length; i++) {
          try {
            subregions[i].close();
            subregions[i].getLog().closeAndDelete();
          } catch (IOException e) {
            // Ignore.
          }
        }
      }
    } finally {
      HRegion.closeHRegion(this.region);
      this.region = null;
    }
  }

  /**
   * @param parent Region to split.
   * @param midkey Key to split around.
   * @return The Regions we created.
   * @throws IOException
   */
  HRegion [] splitRegion(final HRegion parent, final byte [] midkey)
  throws IOException {
    PairOfSameType<HRegion> result = null;
    SplitTransaction st = new SplitTransaction(parent, midkey);
    // If prepare does not return true, for some reason -- logged inside in
    // the prepare call -- we are not ready to split just now.  Just return.
    if (!st.prepare()) return null;
    try {
      result = st.execute(null, null);
    } catch (IOException ioe) {
      try {
        LOG.info("Running rollback of failed split of " +
          parent.getRegionNameAsString() + "; " + ioe.getMessage());
        st.rollback(null, null);
        LOG.info("Successful rollback of failed split of " +
          parent.getRegionNameAsString());
        return null;
      } catch (RuntimeException e) {
        // If failed rollback, kill this server to avoid having a hole in table.
        LOG.info("Failed rollback of failed split of " +
          parent.getRegionNameAsString() + " -- aborting server", e);
      }
    }
    return new HRegion [] {result.getFirst(), result.getSecond()};
  }

  //////////////////////////////////////////////////////////////////////////////
  // Scanner tests
  //////////////////////////////////////////////////////////////////////////////
  public void testGetScanner_WithOkFamilies() throws IOException {
    byte [] tableName = Bytes.toBytes("testtable");
    byte [] fam1 = Bytes.toBytes("fam1");
    byte [] fam2 = Bytes.toBytes("fam2");

    byte [][] families = {fam1, fam2};

    //Setting up region
    String method = this.getName();
    this.region = initHRegion(tableName, method, conf, families);
    try {
      Scan scan = new Scan();
      scan.addFamily(fam1);
      scan.addFamily(fam2);
      try {
        region.getScanner(scan);
      } catch (Exception e) {
        assertTrue("Families could not be found in Region", false);
      }
    } finally {
      HRegion.closeHRegion(this.region);
      this.region = null;
    }
  }

  public void testGetScanner_WithNotOkFamilies() throws IOException {
    byte [] tableName = Bytes.toBytes("testtable");
    byte [] fam1 = Bytes.toBytes("fam1");
    byte [] fam2 = Bytes.toBytes("fam2");

    byte [][] families = {fam1};

    //Setting up region
    String method = this.getName();
    this.region = initHRegion(tableName, method, conf, families);
    try {
      Scan scan = new Scan();
      scan.addFamily(fam2);
      boolean ok = false;
      try {
        region.getScanner(scan);
      } catch (Exception e) {
        ok = true;
      }
      assertTrue("Families could not be found in Region", ok);
    } finally {
      HRegion.closeHRegion(this.region);
      this.region = null;
    }
  }

  public void testGetScanner_WithNoFamilies() throws IOException {
    byte [] tableName = Bytes.toBytes("testtable");
    byte [] row1 = Bytes.toBytes("row1");
    byte [] fam1 = Bytes.toBytes("fam1");
    byte [] fam2 = Bytes.toBytes("fam2");
    byte [] fam3 = Bytes.toBytes("fam3");
    byte [] fam4 = Bytes.toBytes("fam4");

    byte [][] families = {fam1, fam2, fam3, fam4};

    //Setting up region
    String method = this.getName();
    this.region = initHRegion(tableName, method, conf, families);
    try {

      //Putting data in Region
      Put put = new Put(row1);
      put.add(fam1, null, null);
      put.add(fam2, null, null);
      put.add(fam3, null, null);
      put.add(fam4, null, null);
      region.put(put);

      Scan scan = null;
      HRegion.RegionScannerImpl is = null;

      //Testing to see how many scanners that is produced by getScanner, starting
      //with known number, 2 - current = 1
      scan = new Scan();
      scan.addFamily(fam2);
      scan.addFamily(fam4);
      is = (RegionScannerImpl) region.getScanner(scan);
      MultiVersionConsistencyControl.resetThreadReadPoint(region.getMVCC());
      assertEquals(1, ((RegionScannerImpl)is).storeHeap.getHeap().size());

      scan = new Scan();
      is = (RegionScannerImpl) region.getScanner(scan);
      MultiVersionConsistencyControl.resetThreadReadPoint(region.getMVCC());
      assertEquals(families.length -1,
          ((RegionScannerImpl)is).storeHeap.getHeap().size());
    } finally {
      HRegion.closeHRegion(this.region);
      this.region = null;
    }
  }

  /**
   * This method tests https://issues.apache.org/jira/browse/HBASE-2516.
   * @throws IOException 
   */
  public void testGetScanner_WithRegionClosed() throws IOException {
    byte[] tableName = Bytes.toBytes("testtable");
    byte[] fam1 = Bytes.toBytes("fam1");
    byte[] fam2 = Bytes.toBytes("fam2");

    byte[][] families = {fam1, fam2};

    //Setting up region
    String method = this.getName();
    try {
      this.region = initHRegion(tableName, method, conf, families);
    } catch (IOException e) {
      e.printStackTrace();
      fail("Got IOException during initHRegion, " + e.getMessage());
    }
    try {
      region.closed.set(true);
      try {
        region.getScanner(null);
        fail("Expected to get an exception during getScanner on a region that is closed");
      } catch (org.apache.hadoop.hbase.NotServingRegionException e) {
        //this is the correct exception that is expected
      } catch (IOException e) {
        fail("Got wrong type of exception - should be a NotServingRegionException, but was an IOException: "
            + e.getMessage());
      }
    } finally {
      HRegion.closeHRegion(this.region);
      this.region = null;
    }
  }

  public void testRegionScanner_Next() throws IOException {
    byte [] tableName = Bytes.toBytes("testtable");
    byte [] row1 = Bytes.toBytes("row1");
    byte [] row2 = Bytes.toBytes("row2");
    byte [] fam1 = Bytes.toBytes("fam1");
    byte [] fam2 = Bytes.toBytes("fam2");
    byte [] fam3 = Bytes.toBytes("fam3");
    byte [] fam4 = Bytes.toBytes("fam4");

    byte [][] families = {fam1, fam2, fam3, fam4};
    long ts = System.currentTimeMillis();

    //Setting up region
    String method = this.getName();
    this.region = initHRegion(tableName, method, conf, families);
    try {
      //Putting data in Region
      Put put = null;
      put = new Put(row1);
      put.add(fam1, null, ts, null);
      put.add(fam2, null, ts, null);
      put.add(fam3, null, ts, null);
      put.add(fam4, null, ts, null);
      region.put(put);

      put = new Put(row2);
      put.add(fam1, null, ts, null);
      put.add(fam2, null, ts, null);
      put.add(fam3, null, ts, null);
      put.add(fam4, null, ts, null);
      region.put(put);

      Scan scan = new Scan();
      scan.addFamily(fam2);
      scan.addFamily(fam4);
      InternalScanner is = region.getScanner(scan);

      List<KeyValue> res = null;

      //Result 1
      List<KeyValue> expected1 = new ArrayList<KeyValue>();
      expected1.add(new KeyValue(row1, fam2, null, ts, KeyValue.Type.Put, null));
      expected1.add(new KeyValue(row1, fam4, null, ts, KeyValue.Type.Put, null));

      res = new ArrayList<KeyValue>();
      is.next(res);
      for(int i=0; i<res.size(); i++) {
        assertEquals(expected1.get(i), res.get(i));
      }

      //Result 2
      List<KeyValue> expected2 = new ArrayList<KeyValue>();
      expected2.add(new KeyValue(row2, fam2, null, ts, KeyValue.Type.Put, null));
      expected2.add(new KeyValue(row2, fam4, null, ts, KeyValue.Type.Put, null));

      res = new ArrayList<KeyValue>();
      is.next(res);
      for(int i=0; i<res.size(); i++) {
        assertEquals(expected2.get(i), res.get(i));
      }
    } finally {
      HRegion.closeHRegion(this.region);
      this.region = null;
    }
  }

  public void testScanner_ExplicitColumns_FromMemStore_EnforceVersions()
  throws IOException {
    byte [] tableName = Bytes.toBytes("testtable");
    byte [] row1 = Bytes.toBytes("row1");
    byte [] qf1 = Bytes.toBytes("qualifier1");
    byte [] qf2 = Bytes.toBytes("qualifier2");
    byte [] fam1 = Bytes.toBytes("fam1");
    byte [][] families = {fam1};

    long ts1 = System.currentTimeMillis();
    long ts2 = ts1 + 1;
    long ts3 = ts1 + 2;

    //Setting up region
    String method = this.getName();
    this.region = initHRegion(tableName, method, conf, families);
    try {
      //Putting data in Region
      Put put = null;
      KeyValue kv13 = new KeyValue(row1, fam1, qf1, ts3, KeyValue.Type.Put, null);
      KeyValue kv12 = new KeyValue(row1, fam1, qf1, ts2, KeyValue.Type.Put, null);
      KeyValue kv11 = new KeyValue(row1, fam1, qf1, ts1, KeyValue.Type.Put, null);

      KeyValue kv23 = new KeyValue(row1, fam1, qf2, ts3, KeyValue.Type.Put, null);
      KeyValue kv22 = new KeyValue(row1, fam1, qf2, ts2, KeyValue.Type.Put, null);
      KeyValue kv21 = new KeyValue(row1, fam1, qf2, ts1, KeyValue.Type.Put, null);

      put = new Put(row1);
      put.add(kv13);
      put.add(kv12);
      put.add(kv11);
      put.add(kv23);
      put.add(kv22);
      put.add(kv21);
      region.put(put);

      //Expected
      List<KeyValue> expected = new ArrayList<KeyValue>();
      expected.add(kv13);
      expected.add(kv12);

      Scan scan = new Scan(row1);
      scan.addColumn(fam1, qf1);
      scan.setMaxVersions(MAX_VERSIONS);
      List<KeyValue> actual = new ArrayList<KeyValue>();
      InternalScanner scanner = region.getScanner(scan);

      boolean hasNext = scanner.next(actual);
      assertEquals(false, hasNext);

      //Verify result
      for(int i=0; i<expected.size(); i++) {
        assertEquals(expected.get(i), actual.get(i));
      }
    } finally {
      HRegion.closeHRegion(this.region);
      this.region = null;
    }
  }

  public void testScanner_ExplicitColumns_FromFilesOnly_EnforceVersions()
  throws IOException{
    byte [] tableName = Bytes.toBytes("testtable");
    byte [] row1 = Bytes.toBytes("row1");
    byte [] qf1 = Bytes.toBytes("qualifier1");
    byte [] qf2 = Bytes.toBytes("qualifier2");
    byte [] fam1 = Bytes.toBytes("fam1");
    byte [][] families = {fam1};

    long ts1 = 1; //System.currentTimeMillis();
    long ts2 = ts1 + 1;
    long ts3 = ts1 + 2;

    //Setting up region
    String method = this.getName();
    this.region = initHRegion(tableName, method, conf, families);
    try {
      //Putting data in Region
      Put put = null;
      KeyValue kv13 = new KeyValue(row1, fam1, qf1, ts3, KeyValue.Type.Put, null);
      KeyValue kv12 = new KeyValue(row1, fam1, qf1, ts2, KeyValue.Type.Put, null);
      KeyValue kv11 = new KeyValue(row1, fam1, qf1, ts1, KeyValue.Type.Put, null);

      KeyValue kv23 = new KeyValue(row1, fam1, qf2, ts3, KeyValue.Type.Put, null);
      KeyValue kv22 = new KeyValue(row1, fam1, qf2, ts2, KeyValue.Type.Put, null);
      KeyValue kv21 = new KeyValue(row1, fam1, qf2, ts1, KeyValue.Type.Put, null);

      put = new Put(row1);
      put.add(kv13);
      put.add(kv12);
      put.add(kv11);
      put.add(kv23);
      put.add(kv22);
      put.add(kv21);
      region.put(put);
      region.flushcache();

      //Expected
      List<KeyValue> expected = new ArrayList<KeyValue>();
      expected.add(kv13);
      expected.add(kv12);
      expected.add(kv23);
      expected.add(kv22);

      Scan scan = new Scan(row1);
      scan.addColumn(fam1, qf1);
      scan.addColumn(fam1, qf2);
      scan.setMaxVersions(MAX_VERSIONS);
      List<KeyValue> actual = new ArrayList<KeyValue>();
      InternalScanner scanner = region.getScanner(scan);

      boolean hasNext = scanner.next(actual);
      assertEquals(false, hasNext);

      //Verify result
      for(int i=0; i<expected.size(); i++) {
        assertEquals(expected.get(i), actual.get(i));
      }
    } finally {
      HRegion.closeHRegion(this.region);
      this.region = null;
    }
  }

  public void testScanner_ExplicitColumns_FromMemStoreAndFiles_EnforceVersions()
  throws IOException {
    byte [] tableName = Bytes.toBytes("testtable");
    byte [] row1 = Bytes.toBytes("row1");
    byte [] fam1 = Bytes.toBytes("fam1");
    byte [][] families = {fam1};
    byte [] qf1 = Bytes.toBytes("qualifier1");
    byte [] qf2 = Bytes.toBytes("qualifier2");

    long ts1 = 1;
    long ts2 = ts1 + 1;
    long ts3 = ts1 + 2;
    long ts4 = ts1 + 3;

    //Setting up region
    String method = this.getName();
    this.region = initHRegion(tableName, method, conf, families);
    try {
      //Putting data in Region
      KeyValue kv14 = new KeyValue(row1, fam1, qf1, ts4, KeyValue.Type.Put, null);
      KeyValue kv13 = new KeyValue(row1, fam1, qf1, ts3, KeyValue.Type.Put, null);
      KeyValue kv12 = new KeyValue(row1, fam1, qf1, ts2, KeyValue.Type.Put, null);
      KeyValue kv11 = new KeyValue(row1, fam1, qf1, ts1, KeyValue.Type.Put, null);

      KeyValue kv24 = new KeyValue(row1, fam1, qf2, ts4, KeyValue.Type.Put, null);
      KeyValue kv23 = new KeyValue(row1, fam1, qf2, ts3, KeyValue.Type.Put, null);
      KeyValue kv22 = new KeyValue(row1, fam1, qf2, ts2, KeyValue.Type.Put, null);
      KeyValue kv21 = new KeyValue(row1, fam1, qf2, ts1, KeyValue.Type.Put, null);

      Put put = null;
      put = new Put(row1);
      put.add(kv14);
      put.add(kv24);
      region.put(put);
      region.flushcache();

      put = new Put(row1);
      put.add(kv23);
      put.add(kv13);
      region.put(put);
      region.flushcache();

      put = new Put(row1);
      put.add(kv22);
      put.add(kv12);
      region.put(put);
      region.flushcache();

      put = new Put(row1);
      put.add(kv21);
      put.add(kv11);
      region.put(put);

      //Expected
      List<KeyValue> expected = new ArrayList<KeyValue>();
      expected.add(kv14);
      expected.add(kv13);
      expected.add(kv12);
      expected.add(kv24);
      expected.add(kv23);
      expected.add(kv22);

      Scan scan = new Scan(row1);
      scan.addColumn(fam1, qf1);
      scan.addColumn(fam1, qf2);
      int versions = 3;
      scan.setMaxVersions(versions);
      List<KeyValue> actual = new ArrayList<KeyValue>();
      InternalScanner scanner = region.getScanner(scan);

      boolean hasNext = scanner.next(actual);
      assertEquals(false, hasNext);

      //Verify result
      for(int i=0; i<expected.size(); i++) {
        assertEquals(expected.get(i), actual.get(i));
      }
    } finally {
      HRegion.closeHRegion(this.region);
      this.region = null;
    }
  }

  public void testScanner_Wildcard_FromMemStore_EnforceVersions()
  throws IOException {
    byte [] tableName = Bytes.toBytes("testtable");
    byte [] row1 = Bytes.toBytes("row1");
    byte [] qf1 = Bytes.toBytes("qualifier1");
    byte [] qf2 = Bytes.toBytes("qualifier2");
    byte [] fam1 = Bytes.toBytes("fam1");
    byte [][] families = {fam1};

    long ts1 = System.currentTimeMillis();
    long ts2 = ts1 + 1;
    long ts3 = ts1 + 2;

    //Setting up region
    String method = this.getName();
    this.region = initHRegion(tableName, method, conf, families);
    try {
      //Putting data in Region
      Put put = null;
      KeyValue kv13 = new KeyValue(row1, fam1, qf1, ts3, KeyValue.Type.Put, null);
      KeyValue kv12 = new KeyValue(row1, fam1, qf1, ts2, KeyValue.Type.Put, null);
      KeyValue kv11 = new KeyValue(row1, fam1, qf1, ts1, KeyValue.Type.Put, null);

      KeyValue kv23 = new KeyValue(row1, fam1, qf2, ts3, KeyValue.Type.Put, null);
      KeyValue kv22 = new KeyValue(row1, fam1, qf2, ts2, KeyValue.Type.Put, null);
      KeyValue kv21 = new KeyValue(row1, fam1, qf2, ts1, KeyValue.Type.Put, null);

      put = new Put(row1);
      put.add(kv13);
      put.add(kv12);
      put.add(kv11);
      put.add(kv23);
      put.add(kv22);
      put.add(kv21);
      region.put(put);

      //Expected
      List<KeyValue> expected = new ArrayList<KeyValue>();
      expected.add(kv13);
      expected.add(kv12);
      expected.add(kv23);
      expected.add(kv22);

      Scan scan = new Scan(row1);
      scan.addFamily(fam1);
      scan.setMaxVersions(MAX_VERSIONS);
      List<KeyValue> actual = new ArrayList<KeyValue>();
      InternalScanner scanner = region.getScanner(scan);

      boolean hasNext = scanner.next(actual);
      assertEquals(false, hasNext);

      //Verify result
      for(int i=0; i<expected.size(); i++) {
        assertEquals(expected.get(i), actual.get(i));
      }
    } finally {
      HRegion.closeHRegion(this.region);
      this.region = null;
    }
  }

  public void testScanner_Wildcard_FromFilesOnly_EnforceVersions()
  throws IOException{
    byte [] tableName = Bytes.toBytes("testtable");
    byte [] row1 = Bytes.toBytes("row1");
    byte [] qf1 = Bytes.toBytes("qualifier1");
    byte [] qf2 = Bytes.toBytes("qualifier2");
    byte [] fam1 = Bytes.toBytes("fam1");

    long ts1 = 1; //System.currentTimeMillis();
    long ts2 = ts1 + 1;
    long ts3 = ts1 + 2;

    //Setting up region
    String method = this.getName();
    this.region = initHRegion(tableName, method, conf, fam1);
    try {
      //Putting data in Region
      Put put = null;
      KeyValue kv13 = new KeyValue(row1, fam1, qf1, ts3, KeyValue.Type.Put, null);
      KeyValue kv12 = new KeyValue(row1, fam1, qf1, ts2, KeyValue.Type.Put, null);
      KeyValue kv11 = new KeyValue(row1, fam1, qf1, ts1, KeyValue.Type.Put, null);

      KeyValue kv23 = new KeyValue(row1, fam1, qf2, ts3, KeyValue.Type.Put, null);
      KeyValue kv22 = new KeyValue(row1, fam1, qf2, ts2, KeyValue.Type.Put, null);
      KeyValue kv21 = new KeyValue(row1, fam1, qf2, ts1, KeyValue.Type.Put, null);

      put = new Put(row1);
      put.add(kv13);
      put.add(kv12);
      put.add(kv11);
      put.add(kv23);
      put.add(kv22);
      put.add(kv21);
      region.put(put);
      region.flushcache();

      //Expected
      List<KeyValue> expected = new ArrayList<KeyValue>();
      expected.add(kv13);
      expected.add(kv12);
      expected.add(kv23);
      expected.add(kv22);

      Scan scan = new Scan(row1);
      scan.addFamily(fam1);
      scan.setMaxVersions(MAX_VERSIONS);
      List<KeyValue> actual = new ArrayList<KeyValue>();
      InternalScanner scanner = region.getScanner(scan);

      boolean hasNext = scanner.next(actual);
      assertEquals(false, hasNext);

      //Verify result
      for(int i=0; i<expected.size(); i++) {
        assertEquals(expected.get(i), actual.get(i));
      }
    } finally {
      HRegion.closeHRegion(this.region);
      this.region = null;
    }
  }

  public void testScanner_StopRow1542() throws IOException {
    byte [] tableName = Bytes.toBytes("test_table");
    byte [] family = Bytes.toBytes("testFamily");
    this.region = initHRegion(tableName, getName(), conf, family);
    try {
      byte [] row1 = Bytes.toBytes("row111");
      byte [] row2 = Bytes.toBytes("row222");
      byte [] row3 = Bytes.toBytes("row333");
      byte [] row4 = Bytes.toBytes("row444");
      byte [] row5 = Bytes.toBytes("row555");

      byte [] col1 = Bytes.toBytes("Pub111");
      byte [] col2 = Bytes.toBytes("Pub222");


      Put put = new Put(row1);
      put.add(family, col1, Bytes.toBytes(10L));
      region.put(put);

      put = new Put(row2);
      put.add(family, col1, Bytes.toBytes(15L));
      region.put(put);

      put = new Put(row3);
      put.add(family, col2, Bytes.toBytes(20L));
      region.put(put);

      put = new Put(row4);
      put.add(family, col2, Bytes.toBytes(30L));
      region.put(put);

      put = new Put(row5);
      put.add(family, col1, Bytes.toBytes(40L));
      region.put(put);

      Scan scan = new Scan(row3, row4);
      scan.setMaxVersions();
      scan.addColumn(family, col1);
      InternalScanner s = region.getScanner(scan);

      List<KeyValue> results = new ArrayList<KeyValue>();
      assertEquals(false, s.next(results));
      assertEquals(0, results.size());
    } finally {
      HRegion.closeHRegion(this.region);
      this.region = null;
    }
  }

  public void testIncrementColumnValue_UpdatingInPlace() throws IOException {
    this.region = initHRegion(tableName, getName(), conf, fam1);
    try {
      long value = 1L;
      long amount = 3L;

      Put put = new Put(row);
      put.add(fam1, qual1, Bytes.toBytes(value));
      region.put(put);

      long result = region.incrementColumnValue(row, fam1, qual1, amount, true);

      assertEquals(value+amount, result);

      Store store = region.getStore(fam1);
      // ICV removes any extra values floating around in there.
      assertEquals(1, store.memstore.kvset.size());
      assertTrue(store.memstore.snapshot.isEmpty());

      assertICV(row, fam1, qual1, value+amount);
    } finally {
      HRegion.closeHRegion(this.region);
      this.region = null;
    }
  }

  public void testIncrementColumnValue_BumpSnapshot() throws IOException {
    ManualEnvironmentEdge mee = new ManualEnvironmentEdge();
    EnvironmentEdgeManagerTestHelper.injectEdge(mee);
    this.region = initHRegion(tableName, getName(), conf, fam1);
    try {
      long value = 42L;
      long incr = 44L;

      // first put something in kvset, then snapshot it.
      Put put = new Put(row);
      put.add(fam1, qual1, Bytes.toBytes(value));
      region.put(put);

      // get the store in question:
      Store s = region.getStore(fam1);
      s.snapshot(); //bam

      // now increment:
      long newVal = region.incrementColumnValue(row, fam1, qual1,
          incr, false);

      assertEquals(value+incr, newVal);

      // get both versions:
      Get get = new Get(row);
      get.setMaxVersions();
      get.addColumn(fam1,qual1);

      Result r = region.get(get, null);
      assertEquals(2, r.size());
      KeyValue first = r.raw()[0];
      KeyValue second = r.raw()[1];

      assertTrue("ICV failed to upgrade timestamp",
          first.getTimestamp() != second.getTimestamp());
    } finally {
      HRegion.closeHRegion(this.region);
      this.region = null;
    }
  }

  public void testIncrementColumnValue_ConcurrentFlush() throws IOException {
    this.region = initHRegion(tableName, getName(), conf, fam1);
    try {
      long value = 1L;
      long amount = 3L;

      Put put = new Put(row);
      put.add(fam1, qual1, Bytes.toBytes(value));
      region.put(put);

      // now increment during a flush
      Thread t = new Thread() {
        public void run() {
          try {
            region.flushcache();
          } catch (IOException e) {
            LOG.info("test ICV, got IOE during flushcache()");
          }
        }
      };
      t.start();
      long r = region.incrementColumnValue(row, fam1, qual1, amount, true);
      assertEquals(value+amount, r);

      // this also asserts there is only 1 KeyValue in the set.
      assertICV(row, fam1, qual1, value+amount);
    } finally {
      HRegion.closeHRegion(this.region);
      this.region = null;
    }
  }

  public void testIncrementColumnValue_heapSize() throws IOException {
    EnvironmentEdgeManagerTestHelper.injectEdge(new IncrementingEnvironmentEdge());

    this.region = initHRegion(tableName, getName(), conf, fam1);
    try {
      long byAmount = 1L;
      long size;

      for( int i = 0; i < 1000 ; i++) {
        region.incrementColumnValue(row, fam1, qual1, byAmount, true);

        size = region.memstoreSize.get();
        assertTrue("memstore size: " + size, size >= 0);
      }
    } finally {
      HRegion.closeHRegion(this.region);
      this.region = null;
    }
  }

  public void testIncrementColumnValue_UpdatingInPlace_Negative()
    throws IOException {
    this.region = initHRegion(tableName, getName(), conf, fam1);
    try {
      long value = 3L;
      long amount = -1L;

      Put put = new Put(row);
      put.add(fam1, qual1, Bytes.toBytes(value));
      region.put(put);

      long result = region.incrementColumnValue(row, fam1, qual1, amount, true);
      assertEquals(value+amount, result);

      assertICV(row, fam1, qual1, value+amount);
    } finally {
      HRegion.closeHRegion(this.region);
      this.region = null;
    }
  }

  public void testIncrementColumnValue_AddingNew()
    throws IOException {
    this.region = initHRegion(tableName, getName(), conf, fam1);
    try {
      long value = 1L;
      long amount = 3L;

      Put put = new Put(row);
      put.add(fam1, qual1, Bytes.toBytes(value));
      put.add(fam1, qual2, Bytes.toBytes(value));
      region.put(put);

      long result = region.incrementColumnValue(row, fam1, qual3, amount, true);
      assertEquals(amount, result);

      Get get = new Get(row);
      get.addColumn(fam1, qual3);
      Result rr = region.get(get, null);
      assertEquals(1, rr.size());

      // ensure none of the other cols were incremented.
      assertICV(row, fam1, qual1, value);
      assertICV(row, fam1, qual2, value);
      assertICV(row, fam1, qual3, amount);
    } finally {
      HRegion.closeHRegion(this.region);
      this.region = null;
    }
  }

  public void testIncrementColumnValue_UpdatingFromSF() throws IOException {
    this.region = initHRegion(tableName, getName(), conf, fam1);
    try {
      long value = 1L;
      long amount = 3L;

      Put put = new Put(row);
      put.add(fam1, qual1, Bytes.toBytes(value));
      put.add(fam1, qual2, Bytes.toBytes(value));
      region.put(put);

      // flush to disk.
      region.flushcache();

      Store store = region.getStore(fam1);
      assertEquals(0, store.memstore.kvset.size());

      long r = region.incrementColumnValue(row, fam1, qual1, amount, true);
      assertEquals(value+amount, r);

      assertICV(row, fam1, qual1, value+amount);
    } finally {
      HRegion.closeHRegion(this.region);
      this.region = null;
    }
  }

  public void testIncrementColumnValue_AddingNewAfterSFCheck()
    throws IOException {
    this.region = initHRegion(tableName, getName(), conf, fam1);
    try {
      long value = 1L;
      long amount = 3L;

      Put put = new Put(row);
      put.add(fam1, qual1, Bytes.toBytes(value));
      put.add(fam1, qual2, Bytes.toBytes(value));
      region.put(put);
      region.flushcache();

      Store store = region.getStore(fam1);
      assertEquals(0, store.memstore.kvset.size());

      long r = region.incrementColumnValue(row, fam1, qual3, amount, true);
      assertEquals(amount, r);

      assertICV(row, fam1, qual3, amount);

      region.flushcache();

      // ensure that this gets to disk.
      assertICV(row, fam1, qual3, amount);
    } finally {
      HRegion.closeHRegion(this.region);
      this.region = null;
    }
  }

  /**
   * Added for HBASE-3235.
   *
   * When the initial put and an ICV update were arriving with the same timestamp,
   * the initial Put KV was being skipped during {@link MemStore#upsert(KeyValue)}
   * causing the iteration for matching KVs, causing the update-in-place to not
   * happen and the ICV put to effectively disappear.
   * @throws IOException
   */
  public void testIncrementColumnValue_UpdatingInPlace_TimestampClobber() throws IOException {
    this.region = initHRegion(tableName, getName(), conf, fam1);
    try {
      long value = 1L;
      long amount = 3L;
      long now = EnvironmentEdgeManager.currentTimeMillis();
      ManualEnvironmentEdge mock = new ManualEnvironmentEdge();
      mock.setValue(now);
      EnvironmentEdgeManagerTestHelper.injectEdge(mock);

      // verify we catch an ICV on a put with the same timestamp
      Put put = new Put(row);
      put.add(fam1, qual1, now, Bytes.toBytes(value));
      region.put(put);

      long result = region.incrementColumnValue(row, fam1, qual1, amount, true);

      assertEquals(value+amount, result);

      Store store = region.getStore(fam1);
      // ICV should update the existing Put with the same timestamp
      assertEquals(1, store.memstore.kvset.size());
      assertTrue(store.memstore.snapshot.isEmpty());

      assertICV(row, fam1, qual1, value+amount);

      // verify we catch an ICV even when the put ts > now
      put = new Put(row);
      put.add(fam1, qual2, now+1, Bytes.toBytes(value));
      region.put(put);

      result = region.incrementColumnValue(row, fam1, qual2, amount, true);

      assertEquals(value+amount, result);

      store = region.getStore(fam1);
      // ICV should update the existing Put with the same timestamp
      assertEquals(2, store.memstore.kvset.size());
      assertTrue(store.memstore.snapshot.isEmpty());

      assertICV(row, fam1, qual2, value+amount);
      EnvironmentEdgeManagerTestHelper.reset();
    } finally {
      HRegion.closeHRegion(this.region);
      this.region = null;
    }
  }

  public void testIncrementColumnValue_WrongInitialSize() throws IOException {
    this.region = initHRegion(tableName, getName(), conf, fam1);
    try {
      byte[] row1 = Bytes.add(Bytes.toBytes("1234"), Bytes.toBytes(0L));
      int row1Field1 = 0;
      int row1Field2 = 1;
      Put put1 = new Put(row1);
      put1.add(fam1, qual1, Bytes.toBytes(row1Field1));
      put1.add(fam1, qual2, Bytes.toBytes(row1Field2));
      region.put(put1);

      long result;
      try {
        result = region.incrementColumnValue(row1, fam1, qual1, 1, true);
        fail("Expected to fail here");
      } catch (Exception exception) {
        // Expected.
      }


      assertICV(row1, fam1, qual1, row1Field1);
      assertICV(row1, fam1, qual2, row1Field2);
    } finally {
      HRegion.closeHRegion(this.region);
      this.region = null;
    }
  }

  public void testIncrement_WrongInitialSize() throws IOException {
    this.region = initHRegion(tableName, getName(), conf, fam1);
    try {
      byte[] row1 = Bytes.add(Bytes.toBytes("1234"), Bytes.toBytes(0L));
      long row1Field1 = 0;
      int row1Field2 = 1;
      Put put1 = new Put(row1);
      put1.add(fam1, qual1, Bytes.toBytes(row1Field1));
      put1.add(fam1, qual2, Bytes.toBytes(row1Field2));
      region.put(put1);
      Increment increment = new Increment(row1);
      increment.addColumn(fam1, qual1, 1);

      //here we should be successful as normal
      region.increment(increment, null, true);
      assertICV(row1, fam1, qual1, row1Field1 + 1);

      //failed to increment
      increment = new Increment(row1);
      increment.addColumn(fam1, qual2, 1);
      try {
        region.increment(increment, null, true);
        fail("Expected to fail here");
      } catch (Exception exception) {
        // Expected.
      }
      assertICV(row1, fam1, qual2, row1Field2);
    } finally {
      HRegion.closeHRegion(this.region);
      this.region = null;
    }
  }
  private void assertICV(byte [] row,
                         byte [] familiy,
                         byte[] qualifier,
                         long amount) throws IOException {
    // run a get and see?
    Get get = new Get(row);
    get.addColumn(familiy, qualifier);
    Result result = region.get(get, null);
    assertEquals(1, result.size());

    KeyValue kv = result.raw()[0];
    long r = Bytes.toLong(kv.getValue());
    assertEquals(amount, r);
  }

  private void assertICV(byte [] row,
                         byte [] familiy,
                         byte[] qualifier,
                         int amount) throws IOException {
    // run a get and see?
    Get get = new Get(row);
    get.addColumn(familiy, qualifier);
    Result result = region.get(get, null);
    assertEquals(1, result.size());

    KeyValue kv = result.raw()[0];
    int r = Bytes.toInt(kv.getValue());
    assertEquals(amount, r);
  }

  public void testScanner_Wildcard_FromMemStoreAndFiles_EnforceVersions()
  throws IOException {
    byte [] tableName = Bytes.toBytes("testtable");
    byte [] row1 = Bytes.toBytes("row1");
    byte [] fam1 = Bytes.toBytes("fam1");
    byte [] qf1 = Bytes.toBytes("qualifier1");
    byte [] qf2 = Bytes.toBytes("quateslifier2");

    long ts1 = 1;
    long ts2 = ts1 + 1;
    long ts3 = ts1 + 2;
    long ts4 = ts1 + 3;

    //Setting up region
    String method = this.getName();
    this.region = initHRegion(tableName, method, conf, fam1);
    try {
      //Putting data in Region
      KeyValue kv14 = new KeyValue(row1, fam1, qf1, ts4, KeyValue.Type.Put, null);
      KeyValue kv13 = new KeyValue(row1, fam1, qf1, ts3, KeyValue.Type.Put, null);
      KeyValue kv12 = new KeyValue(row1, fam1, qf1, ts2, KeyValue.Type.Put, null);
      KeyValue kv11 = new KeyValue(row1, fam1, qf1, ts1, KeyValue.Type.Put, null);

      KeyValue kv24 = new KeyValue(row1, fam1, qf2, ts4, KeyValue.Type.Put, null);
      KeyValue kv23 = new KeyValue(row1, fam1, qf2, ts3, KeyValue.Type.Put, null);
      KeyValue kv22 = new KeyValue(row1, fam1, qf2, ts2, KeyValue.Type.Put, null);
      KeyValue kv21 = new KeyValue(row1, fam1, qf2, ts1, KeyValue.Type.Put, null);

      Put put = null;
      put = new Put(row1);
      put.add(kv14);
      put.add(kv24);
      region.put(put);
      region.flushcache();

      put = new Put(row1);
      put.add(kv23);
      put.add(kv13);
      region.put(put);
      region.flushcache();

      put = new Put(row1);
      put.add(kv22);
      put.add(kv12);
      region.put(put);
      region.flushcache();

      put = new Put(row1);
      put.add(kv21);
      put.add(kv11);
      region.put(put);

      //Expected
      List<KeyValue> expected = new ArrayList<KeyValue>();
      expected.add(kv14);
      expected.add(kv13);
      expected.add(kv12);
      expected.add(kv24);
      expected.add(kv23);
      expected.add(kv22);

      Scan scan = new Scan(row1);
      int versions = 3;
      scan.setMaxVersions(versions);
      List<KeyValue> actual = new ArrayList<KeyValue>();
      InternalScanner scanner = region.getScanner(scan);

      boolean hasNext = scanner.next(actual);
      assertEquals(false, hasNext);

      //Verify result
      for(int i=0; i<expected.size(); i++) {
        assertEquals(expected.get(i), actual.get(i));
      }
    } finally {
      HRegion.closeHRegion(this.region);
      this.region = null;
    }
  }

  /**
   * Added for HBASE-5416
   *
   * Here we test scan optimization when only subset of CFs are used in filter
   * conditions.
   */
  public void testScanner_JoinedScanners() throws IOException {
    byte [] tableName = Bytes.toBytes("testTable");
    byte [] cf_essential = Bytes.toBytes("essential");
    byte [] cf_joined = Bytes.toBytes("joined");
    byte [] cf_alpha = Bytes.toBytes("alpha");
    this.region = initHRegion(tableName, getName(), conf, cf_essential, cf_joined, cf_alpha);
    try {
      byte [] row1 = Bytes.toBytes("row1");
      byte [] row2 = Bytes.toBytes("row2");
      byte [] row3 = Bytes.toBytes("row3");

      byte [] col_normal = Bytes.toBytes("d");
      byte [] col_alpha = Bytes.toBytes("a");

      byte [] filtered_val = Bytes.toBytes(3);

      Put put = new Put(row1);
      put.add(cf_essential, col_normal, Bytes.toBytes(1));
      put.add(cf_joined, col_alpha, Bytes.toBytes(1));
      region.put(put);

      put = new Put(row2);
      put.add(cf_essential, col_alpha, Bytes.toBytes(2));
      put.add(cf_joined, col_normal, Bytes.toBytes(2));
      put.add(cf_alpha, col_alpha, Bytes.toBytes(2));
      region.put(put);

      put = new Put(row3);
      put.add(cf_essential, col_normal, filtered_val);
      put.add(cf_joined, col_normal, filtered_val);
      region.put(put);

      // Check two things:
      // 1. result list contains expected values
      // 2. result list is sorted properly

      Scan scan = new Scan();
      Filter filter = new SingleColumnValueExcludeFilter(cf_essential, col_normal,
                                                         CompareOp.NOT_EQUAL, filtered_val);
      scan.setFilter(filter);
      scan.setLoadColumnFamiliesOnDemand(true);
      InternalScanner s = region.getScanner(scan);

      List<KeyValue> results = new ArrayList<KeyValue>();
      assertTrue(s.next(results));
      assertEquals(results.size(), 1);
      results.clear();

      assertTrue(s.next(results));
      assertEquals(results.size(), 3);
      assertTrue("orderCheck", results.get(0).matchingFamily(cf_alpha));
      assertTrue("orderCheck", results.get(1).matchingFamily(cf_essential));
      assertTrue("orderCheck", results.get(2).matchingFamily(cf_joined));
      results.clear();

      assertFalse(s.next(results));
      assertEquals(results.size(), 0);
    } finally {
      HRegion.closeHRegion(this.region);
      this.region = null;
    }
  }

  /**
   * HBASE-5416
   *
   * Test case when scan limits amount of KVs returned on each next() call.
   */
  public void testScanner_JoinedScannersWithLimits() throws IOException {
    final byte [] tableName = Bytes.toBytes("testTable");
    final byte [] cf_first = Bytes.toBytes("first");
    final byte [] cf_second = Bytes.toBytes("second");

    this.region = initHRegion(tableName, getName(), conf, cf_first, cf_second);
    try {
      final byte [] col_a = Bytes.toBytes("a");
      final byte [] col_b = Bytes.toBytes("b");

      Put put;

      for (int i = 0; i < 10; i++) {
        put = new Put(Bytes.toBytes("r" + Integer.toString(i)));
        put.add(cf_first, col_a, Bytes.toBytes(i));
        if (i < 5) {
          put.add(cf_first, col_b, Bytes.toBytes(i));
          put.add(cf_second, col_a, Bytes.toBytes(i));
          put.add(cf_second, col_b, Bytes.toBytes(i));
        }
        region.put(put);
      }

      Scan scan = new Scan();
      scan.setLoadColumnFamiliesOnDemand(true);
      Filter bogusFilter = new FilterBase() {
        @Override
        public boolean isFamilyEssential(byte[] name) {
          return Bytes.equals(name, cf_first);
        }
        @Override
        public void readFields(DataInput arg0) throws IOException {
        }

        @Override
        public void write(DataOutput arg0) throws IOException {
        }
      };

      scan.setFilter(bogusFilter);
      InternalScanner s = region.getScanner(scan);

      // Our data looks like this:
      // r0: first:a, first:b, second:a, second:b
      // r1: first:a, first:b, second:a, second:b
      // r2: first:a, first:b, second:a, second:b
      // r3: first:a, first:b, second:a, second:b
      // r4: first:a, first:b, second:a, second:b
      // r5: first:a
      // r6: first:a
      // r7: first:a
      // r8: first:a
      // r9: first:a

      // But due to next's limit set to 3, we should get this:
      // r0: first:a, first:b, second:a
      // r0: second:b
      // r1: first:a, first:b, second:a
      // r1: second:b
      // r2: first:a, first:b, second:a
      // r2: second:b
      // r3: first:a, first:b, second:a
      // r3: second:b
      // r4: first:a, first:b, second:a
      // r4: second:b
      // r5: first:a
      // r6: first:a
      // r7: first:a
      // r8: first:a
      // r9: first:a

      List<KeyValue> results = new ArrayList<KeyValue>();
      int index = 0;
      while (true) {
        boolean more = s.next(results, 3);
        if ((index >> 1) < 5) {
          if (index % 2 == 0)
            assertEquals(results.size(), 3);
          else
            assertEquals(results.size(), 1);
        }
        else
          assertEquals(results.size(), 1);
        results.clear();
        index++;
        if (!more) break;
      }
    } finally {
      HRegion.closeHRegion(this.region);
      this.region = null;
    }
  }

  //////////////////////////////////////////////////////////////////////////////
  // Split test
  //////////////////////////////////////////////////////////////////////////////
  /**
   * Splits twice and verifies getting from each of the split regions.
   * @throws Exception
   */
  public void testBasicSplit() throws Exception {
    byte [] tableName = Bytes.toBytes("testtable");
    byte [][] families = {fam1, fam2, fam3};

    Configuration hc = initSplit();
    //Setting up region
    String method = this.getName();
    this.region = initHRegion(tableName, method, hc, families);

    try {
      LOG.info("" + addContent(region, fam3));
      region.flushcache();
      region.compactStores();
      byte [] splitRow = region.checkSplit();
      assertNotNull(splitRow);
      LOG.info("SplitRow: " + Bytes.toString(splitRow));
      HRegion [] regions = splitRegion(region, splitRow);
      try {
        // Need to open the regions.
        // TODO: Add an 'open' to HRegion... don't do open by constructing
        // instance.
        for (int i = 0; i < regions.length; i++) {
          regions[i] = openClosedRegion(regions[i]);
        }
        // Assert can get rows out of new regions. Should be able to get first
        // row from first region and the midkey from second region.
        assertGet(regions[0], fam3, Bytes.toBytes(START_KEY));
        assertGet(regions[1], fam3, splitRow);
        // Test I can get scanner and that it starts at right place.
        assertScan(regions[0], fam3,
            Bytes.toBytes(START_KEY));
        assertScan(regions[1], fam3, splitRow);
        // Now prove can't split regions that have references.
        for (int i = 0; i < regions.length; i++) {
          // Add so much data to this region, we create a store file that is >
          // than one of our unsplitable references. it will.
          for (int j = 0; j < 2; j++) {
            addContent(regions[i], fam3);
          }
          addContent(regions[i], fam2);
          addContent(regions[i], fam1);
          regions[i].flushcache();
        }

        byte [][] midkeys = new byte [regions.length][];
        // To make regions splitable force compaction.
        for (int i = 0; i < regions.length; i++) {
          regions[i].compactStores();
          midkeys[i] = regions[i].checkSplit();
        }

        TreeMap<String, HRegion> sortedMap = new TreeMap<String, HRegion>();
        // Split these two daughter regions so then I'll have 4 regions. Will
        // split because added data above.
        for (int i = 0; i < regions.length; i++) {
          HRegion[] rs = null;
          if (midkeys[i] != null) {
            rs = splitRegion(regions[i], midkeys[i]);
            for (int j = 0; j < rs.length; j++) {
              sortedMap.put(Bytes.toString(rs[j].getRegionName()),
                openClosedRegion(rs[j]));
            }
          }
        }
        LOG.info("Made 4 regions");
        // The splits should have been even. Test I can get some arbitrary row
        // out of each.
        int interval = (LAST_CHAR - FIRST_CHAR) / 3;
        byte[] b = Bytes.toBytes(START_KEY);
        for (HRegion r : sortedMap.values()) {
          assertGet(r, fam3, b);
          b[0] += interval;
        }
      } finally {
        for (int i = 0; i < regions.length; i++) {
          try {
            regions[i].close();
          } catch (IOException e) {
            // Ignore.
          }
        }
      }
    } finally {
      HRegion.closeHRegion(this.region);
      this.region = null;
    }
  }

  public void testSplitRegion() throws IOException {
    byte [] tableName = Bytes.toBytes("testtable");
    byte [] qualifier = Bytes.toBytes("qualifier");
    Configuration hc = initSplit();
    int numRows = 10;
    byte [][] families = {fam1, fam3};

    //Setting up region
    String method = this.getName();
    this.region = initHRegion(tableName, method, hc, families);

    //Put data in region
    int startRow = 100;
    putData(startRow, numRows, qualifier, families);
    int splitRow = startRow + numRows;
    putData(splitRow, numRows, qualifier, families);
    region.flushcache();

    HRegion [] regions = null;
    try {
      regions = splitRegion(region, Bytes.toBytes("" + splitRow));
      //Opening the regions returned.
      for (int i = 0; i < regions.length; i++) {
        regions[i] = openClosedRegion(regions[i]);
      }
      //Verifying that the region has been split
      assertEquals(2, regions.length);

      //Verifying that all data is still there and that data is in the right
      //place
      verifyData(regions[0], startRow, numRows, qualifier, families);
      verifyData(regions[1], splitRow, numRows, qualifier, families);

    } finally {
      HRegion.closeHRegion(this.region);
      this.region = null;
    }
  }


  /**
   * Flushes the cache in a thread while scanning. The tests verify that the
   * scan is coherent - e.g. the returned results are always of the same or
   * later update as the previous results.
   * @throws IOException scan / compact
   * @throws InterruptedException thread join
   */
  public void testFlushCacheWhileScanning() throws IOException, InterruptedException {
    byte[] tableName = Bytes.toBytes("testFlushCacheWhileScanning");
    byte[] family = Bytes.toBytes("family");
    int numRows = 1000;
    int flushAndScanInterval = 10;
    int compactInterval = 10 * flushAndScanInterval;

    String method = "testFlushCacheWhileScanning";
    this.region = initHRegion(tableName,method, conf, family);
    try {
      FlushThread flushThread = new FlushThread();
      flushThread.start();

      Scan scan = new Scan();
      scan.addFamily(family);
      scan.setFilter(new SingleColumnValueFilter(family, qual1,
          CompareOp.EQUAL, new BinaryComparator(Bytes.toBytes(5L))));

      int expectedCount = 0;
      List<KeyValue> res = new ArrayList<KeyValue>();

      boolean toggle=true;
      for (long i = 0; i < numRows; i++) {
        Put put = new Put(Bytes.toBytes(i));
        put.setWriteToWAL(false);
        put.add(family, qual1, Bytes.toBytes(i % 10));
        region.put(put);

        if (i != 0 && i % compactInterval == 0) {
          //System.out.println("iteration = " + i);
          region.compactStores(true);
        }

        if (i % 10 == 5L) {
          expectedCount++;
        }

        if (i != 0 && i % flushAndScanInterval == 0) {
          res.clear();
          InternalScanner scanner = region.getScanner(scan);
          if (toggle) {
            flushThread.flush();
          }
          while (scanner.next(res)) ;
          if (!toggle) {
            flushThread.flush();
          }
          assertEquals("i=" + i, expectedCount, res.size());
          toggle = !toggle;
        }
      }

      flushThread.done();
      flushThread.join();
      flushThread.checkNoError();
    } finally {
      HRegion.closeHRegion(this.region);
      this.region = null;
    }
  }

  protected class FlushThread extends Thread {
    private volatile boolean done;
    private Throwable error = null;

    public void done() {
      done = true;
      synchronized (this) {
        interrupt();
      }
    }

    public void checkNoError() {
      if (error != null) {
        assertNull(error);
      }
    }

    @Override
    public void run() {
      done = false;
      while (!done) {
        synchronized (this) {
          try {
            wait();
          } catch (InterruptedException ignored) {
            if (done) {
              break;
            }
          }
        }
        try {
          region.flushcache();
        } catch (IOException e) {
          if (!done) {
            LOG.error("Error while flusing cache", e);
            error = e;
          }
          break;
        }
      }

    }

    public void flush() {
      synchronized (this) {
        notify();
      }

    }
  }

  /**
   * Writes very wide records and scans for the latest every time..
   * Flushes and compacts the region every now and then to keep things
   * realistic.
   *
   * @throws IOException          by flush / scan / compaction
   * @throws InterruptedException when joining threads
   */
  public void testWritesWhileScanning()
    throws IOException, InterruptedException {
    byte[] tableName = Bytes.toBytes("testWritesWhileScanning");
    int testCount = 100;
    int numRows = 1;
    int numFamilies = 10;
    int numQualifiers = 100;
    int flushInterval = 7;
    int compactInterval = 5 * flushInterval;
    byte[][] families = new byte[numFamilies][];
    for (int i = 0; i < numFamilies; i++) {
      families[i] = Bytes.toBytes("family" + i);
    }
    byte[][] qualifiers = new byte[numQualifiers][];
    for (int i = 0; i < numQualifiers; i++) {
      qualifiers[i] = Bytes.toBytes("qual" + i);
    }

    String method = "testWritesWhileScanning";
    this.region = initHRegion(tableName, method, conf, families);
    try {
      PutThread putThread = new PutThread(numRows, families, qualifiers);
      putThread.start();
      putThread.waitForFirstPut();

      FlushThread flushThread = new FlushThread();
      flushThread.start();

      Scan scan = new Scan(Bytes.toBytes("row0"), Bytes.toBytes("row1"));
      //    scan.setFilter(new RowFilter(CompareFilter.CompareOp.EQUAL,
      //      new BinaryComparator(Bytes.toBytes("row0"))));

      int expectedCount = numFamilies * numQualifiers;
      List<KeyValue> res = new ArrayList<KeyValue>();

      long prevTimestamp = 0L;
      for (int i = 0; i < testCount; i++) {

        if (i != 0 && i % compactInterval == 0) {
          region.compactStores(true);
        }

        if (i != 0 && i % flushInterval == 0) {
          //System.out.println("flush scan iteration = " + i);
          flushThread.flush();
        }

        boolean previousEmpty = res.isEmpty();
        res.clear();
        InternalScanner scanner = region.getScanner(scan);
        while (scanner.next(res)) ;
        if (!res.isEmpty() || !previousEmpty || i > compactInterval) {
          assertEquals("i=" + i, expectedCount, res.size());
          long timestamp = res.get(0).getTimestamp();
          assertTrue("Timestamps were broke: " + timestamp + " prev: " + prevTimestamp,
              timestamp >= prevTimestamp);
          prevTimestamp = timestamp;
        }
      }

      putThread.done();

      region.flushcache();

      putThread.join();
      putThread.checkNoError();

      flushThread.done();
      flushThread.join();
      flushThread.checkNoError();
    } finally {
      HRegion.closeHRegion(this.region);
      this.region = null;
    }
  }

  protected class PutThread extends Thread {
    private volatile boolean done;
    private volatile int numPutsFinished = 0;

    private Throwable error = null;
    private int numRows;
    private byte[][] families;
    private byte[][] qualifiers;

    private PutThread(int numRows, byte[][] families,
      byte[][] qualifiers) {
      this.numRows = numRows;
      this.families = families;
      this.qualifiers = qualifiers;
    }

    /**
     * Block until this thread has put at least one row.
     */
    public void waitForFirstPut() throws InterruptedException {
      // wait until put thread actually puts some data
      while (numPutsFinished == 0) {
        checkNoError();
        Thread.sleep(50);
      }
    }

    public void done() {
      done = true;
      synchronized (this) {
        interrupt();
      }
    }

    public void checkNoError() {
      if (error != null) {
        assertNull(error);
      }
    }

    @Override
    public void run() {
      done = false;
      while (!done) {
        try {
          for (int r = 0; r < numRows; r++) {
            byte[] row = Bytes.toBytes("row" + r);
            Put put = new Put(row);
            put.setWriteToWAL(false);
            byte[] value = Bytes.toBytes(String.valueOf(numPutsFinished));
            for (byte[] family : families) {
              for (byte[] qualifier : qualifiers) {
                put.add(family, qualifier, (long) numPutsFinished, value);
              }
            }
//            System.out.println("Putting of kvsetsize=" + put.size());
            region.put(put);
            numPutsFinished++;
            if (numPutsFinished > 0 && numPutsFinished % 47 == 0) {
              System.out.println("put iteration = " + numPutsFinished);
              Delete delete = new Delete(row, (long)numPutsFinished-30, null);
              region.delete(delete, null, true);
            }
            numPutsFinished++;
          }
        } catch (InterruptedIOException e) {
          // This is fine. It means we are done, or didn't get the lock on time
        } catch (IOException e) {
          LOG.error("error while putting records", e);
          error = e;
          break;
        }
      }

    }

  }


  /**
   * Writes very wide records and gets the latest row every time..
   * Flushes and compacts the region aggressivly to catch issues.
   *
   * @throws IOException          by flush / scan / compaction
   * @throws InterruptedException when joining threads
   */
  public void testWritesWhileGetting()
    throws Exception {
    byte[] tableName = Bytes.toBytes("testWritesWhileGetting");
    int testCount = 100;
    int numRows = 1;
    int numFamilies = 10;
    int numQualifiers = 100;
    int compactInterval = 100;
    byte[][] families = new byte[numFamilies][];
    for (int i = 0; i < numFamilies; i++) {
      families[i] = Bytes.toBytes("family" + i);
    }
    byte[][] qualifiers = new byte[numQualifiers][];
    for (int i = 0; i < numQualifiers; i++) {
      qualifiers[i] = Bytes.toBytes("qual" + i);
    }

    Configuration conf = HBaseConfiguration.create(this.conf);

    String method = "testWritesWhileGetting";
    // This test flushes constantly and can cause many files to be created, possibly
    // extending over the ulimit.  Make sure compactions are aggressive in reducing
    // the number of HFiles created.
    conf.setInt("hbase.hstore.compaction.min", 1);
    conf.setInt("hbase.hstore.compaction.max", 1000);
    this.region = initHRegion(tableName, method, conf, families);
    PutThread putThread = null;
    MultithreadedTestUtil.TestContext ctx =
      new MultithreadedTestUtil.TestContext(conf);
    try {
      putThread = new PutThread(numRows, families, qualifiers);
      putThread.start();
      putThread.waitForFirstPut();

      // Add a thread that flushes as fast as possible
      ctx.addThread(new RepeatingTestThread(ctx) {
    	private int flushesSinceCompact = 0;
    	private final int maxFlushesSinceCompact = 20;
        public void doAnAction() throws Exception {
          if (region.flushcache()) {
            ++flushesSinceCompact;
          }
          // Compact regularly to avoid creating too many files and exceeding the ulimit.
          if (flushesSinceCompact == maxFlushesSinceCompact) {
            region.compactStores(false);
            flushesSinceCompact = 0;
          }
        }
      });
      ctx.startThreads();

      Get get = new Get(Bytes.toBytes("row0"));
      Result result = null;

      int expectedCount = numFamilies * numQualifiers;

      long prevTimestamp = 0L;
      for (int i = 0; i < testCount; i++) {

        boolean previousEmpty = result == null || result.isEmpty();
        result = region.get(get, null);
        if (!result.isEmpty() || !previousEmpty || i > compactInterval) {
          assertEquals("i=" + i, expectedCount, result.size());
          // TODO this was removed, now what dangit?!
          // search looking for the qualifier in question?
          long timestamp = 0;
          for (KeyValue kv : result.raw()) {
            if (Bytes.equals(kv.getFamily(), families[0])
                && Bytes.equals(kv.getQualifier(), qualifiers[0])) {
              timestamp = kv.getTimestamp();
            }
          }
          assertTrue(timestamp >= prevTimestamp);
          prevTimestamp = timestamp;
          KeyValue previousKV = null;

          for (KeyValue kv : result.raw()) {
            byte[] thisValue = kv.getValue();
            if (previousKV != null) {
              if (Bytes.compareTo(previousKV.getValue(), thisValue) != 0) {
                LOG.warn("These two KV should have the same value." +
                    " Previous KV:" +
                    previousKV + "(memStoreTS:" + previousKV.getMemstoreTS() + ")" +
                    ", New KV: " +
                    kv + "(memStoreTS:" + kv.getMemstoreTS() + ")"
                    );
                assertEquals(0, Bytes.compareTo(previousKV.getValue(), thisValue));
              }
            }
            previousKV = kv;
          }
        }
      }
    } finally {
      if (putThread != null) putThread.done();

      region.flushcache();

      if (putThread != null) {
        putThread.join();
        putThread.checkNoError();
      }

      ctx.stop();
      HRegion.closeHRegion(this.region);
      this.region = null;
    }
  }

  public void testHolesInMeta() throws Exception {
    String method = "testHolesInMeta";
    byte[] tableName = Bytes.toBytes(method);
    byte[] family = Bytes.toBytes("family");
    this.region = initHRegion(tableName, Bytes.toBytes("x"), Bytes.toBytes("z"), method,
        conf, false, family);
    try {
      byte[] rowNotServed = Bytes.toBytes("a");
      Get g = new Get(rowNotServed);
      try {
        region.get(g, null);
        fail();
      } catch (WrongRegionException x) {
        // OK
      }
      byte[] row = Bytes.toBytes("y");
      g = new Get(row);
      region.get(g, null);
    } finally {
      HRegion.closeHRegion(this.region);
      this.region = null;
    }
  }

  /**
   * Verifies that the .regioninfo file is written on region creation
   * and that is recreated if missing during region opening.
   */
  public void testRegionInfoFileCreation() throws IOException {
    Path rootDir = new Path(DIR + "testRegionInfoFileCreation");
    Configuration conf = HBaseConfiguration.create(this.conf);

    HTableDescriptor htd = new HTableDescriptor("testtb");
    htd.addFamily(new HColumnDescriptor("cf"));

    HRegionInfo hri = new HRegionInfo(htd.getName());

    // Create a region and skip the initialization (like CreateTableHandler)
    HRegion region = HRegion.createHRegion(hri, rootDir, conf, htd, null, false, true);
    Path regionDir = region.getRegionDir();
    FileSystem fs = region.getFilesystem();
    HRegion.closeHRegion(region);

    Path regionInfoFile = new Path(regionDir, HRegion.REGIONINFO_FILE);

    // Verify that the .regioninfo file is present
    assertTrue(HRegion.REGIONINFO_FILE + " should be present in the region dir",
      fs.exists(regionInfoFile));

    // Try to open the region
    region = HRegion.openHRegion(rootDir, hri, htd, null, conf);
    assertEquals(regionDir, region.getRegionDir());
    HRegion.closeHRegion(region);

    // Verify that the .regioninfo file is still there
    assertTrue(HRegion.REGIONINFO_FILE + " should be present in the region dir",
      fs.exists(regionInfoFile));

    // Remove the .regioninfo file and verify is recreated on region open
    fs.delete(regionInfoFile);
    assertFalse(HRegion.REGIONINFO_FILE + " should be removed from the region dir",
      fs.exists(regionInfoFile));

    region = HRegion.openHRegion(rootDir, hri, htd, null, conf);
    assertEquals(regionDir, region.getRegionDir());
    HRegion.closeHRegion(region);

    // Verify that the .regioninfo file is still there
    assertTrue(HRegion.REGIONINFO_FILE + " should be present in the region dir",
      fs.exists(new Path(regionDir, HRegion.REGIONINFO_FILE)));
  }

  /**
   * Testcase to check state of region initialization task set to ABORTED or not if any exceptions
   * during initialization
   * 
   * @throws Exception
   */
  @Test
  public void testStatusSettingToAbortIfAnyExceptionDuringRegionInitilization() throws Exception {
    HRegionInfo info = null;
    try {
      FileSystem fs = Mockito.mock(FileSystem.class);
      Mockito.when(fs.exists((Path) Mockito.anyObject())).thenThrow(new IOException());
      HTableDescriptor htd = new HTableDescriptor(tableName);
      htd.addFamily(new HColumnDescriptor("cf"));
      info = new HRegionInfo(htd.getName(), HConstants.EMPTY_BYTE_ARRAY,
          HConstants.EMPTY_BYTE_ARRAY, false);
      Path path = new Path(DIR + "testStatusSettingToAbortIfAnyExceptionDuringRegionInitilization");
      // no where we are instantiating HStore in this test case so useTableNameGlobally is null. To
      // avoid NullPointerException we are setting useTableNameGlobally to false.
      SchemaMetrics.setUseTableNameInTest(false);
      region = HRegion.newHRegion(path, null, fs, conf, info, htd, null);
      // region initialization throws IOException and set task state to ABORTED.
      region.initialize();
      fail("Region initialization should fail due to IOException");
    } catch (IOException io) {
      List<MonitoredTask> tasks = TaskMonitor.get().getTasks();
      for (MonitoredTask monitoredTask : tasks) {
        if (!(monitoredTask instanceof MonitoredRPCHandler)
            && monitoredTask.getDescription().contains(region.toString())) {
          assertTrue("Region state should be ABORTED.",
              monitoredTask.getState().equals(MonitoredTask.State.ABORTED));
          break;
        }
      }
    } finally {
      HRegion.closeHRegion(region);
    }
  }

  public void testIndexesScanWithOneDeletedRow() throws IOException {
    byte[] tableName = Bytes.toBytes("testIndexesScanWithOneDeletedRow");
    byte[] family = Bytes.toBytes("family");

    //Setting up region
    String method = "testIndexesScanWithOneDeletedRow";
    this.region = initHRegion(tableName, method, conf, family);
    try {
      Put put = new Put(Bytes.toBytes(1L));
      put.add(family, qual1, 1L, Bytes.toBytes(1L));
      region.put(put);

      region.flushcache();

      Delete delete = new Delete(Bytes.toBytes(1L), 1L, null);
      //delete.deleteColumn(family, qual1);
      region.delete(delete, null, true);

      put = new Put(Bytes.toBytes(2L));
      put.add(family, qual1, 2L, Bytes.toBytes(2L));
      region.put(put);

      Scan idxScan = new Scan();
      idxScan.addFamily(family);
      idxScan.setFilter(new FilterList(FilterList.Operator.MUST_PASS_ALL,
          Arrays.<Filter>asList(new SingleColumnValueFilter(family, qual1,
              CompareOp.GREATER_OR_EQUAL,
              new BinaryComparator(Bytes.toBytes(0L))),
              new SingleColumnValueFilter(family, qual1, CompareOp.LESS_OR_EQUAL,
                  new BinaryComparator(Bytes.toBytes(3L)))
              )));
      InternalScanner scanner = region.getScanner(idxScan);
      List<KeyValue> res = new ArrayList<KeyValue>();

      //long start = System.nanoTime();
      while (scanner.next(res)) ;
      //long end = System.nanoTime();
      //System.out.println("memStoreEmpty=" + memStoreEmpty + ", time=" + (end - start)/1000000D);
      assertEquals(1L, res.size());
    } finally {
      HRegion.closeHRegion(this.region);
      this.region = null;
    }
  }

  //////////////////////////////////////////////////////////////////////////////
  // Bloom filter test
  //////////////////////////////////////////////////////////////////////////////
  public void testBloomFilterSize() throws IOException {
    byte [] tableName = Bytes.toBytes("testBloomFilterSize");
    byte [] row1 = Bytes.toBytes("row1");
    byte [] fam1 = Bytes.toBytes("fam1");
    byte [] qf1  = Bytes.toBytes("col");
    byte [] val1  = Bytes.toBytes("value1");
    // Create Table
    HColumnDescriptor hcd = new HColumnDescriptor(fam1)
        .setMaxVersions(Integer.MAX_VALUE)
        .setBloomFilterType(BloomType.ROWCOL);

    HTableDescriptor htd = new HTableDescriptor(tableName);
    htd.addFamily(hcd);
    HRegionInfo info = new HRegionInfo(htd.getName(), null, null, false);
    Path path = new Path(DIR + "testBloomFilterSize");
    this.region = HRegion.createHRegion(info, path, conf, htd);
    try {
      int num_unique_rows = 10;
      int duplicate_multiplier =2;
      int num_storefiles = 4;

      int version = 0;
      for (int f =0 ; f < num_storefiles; f++) {
        for (int i = 0; i < duplicate_multiplier; i ++) {
          for (int j = 0; j < num_unique_rows; j++) {
            Put put = new Put(Bytes.toBytes("row" + j));
            put.setWriteToWAL(false);
            put.add(fam1, qf1, version++, val1);
            region.put(put);
          }
        }
        region.flushcache();
      }
      //before compaction
      Store store = region.getStore(fam1);
      List<StoreFile> storeFiles = store.getStorefiles();
      for (StoreFile storefile : storeFiles) {
        StoreFile.Reader reader = storefile.getReader();
        reader.loadFileInfo();
        reader.loadBloomfilter();
        assertEquals(num_unique_rows*duplicate_multiplier, reader.getEntries());
        assertEquals(num_unique_rows, reader.getFilterEntries());
      }

      region.compactStores(true);

      //after compaction
      storeFiles = store.getStorefiles();
      for (StoreFile storefile : storeFiles) {
        StoreFile.Reader reader = storefile.getReader();
        reader.loadFileInfo();
        reader.loadBloomfilter();
        assertEquals(num_unique_rows*duplicate_multiplier*num_storefiles,
            reader.getEntries());
        assertEquals(num_unique_rows, reader.getFilterEntries());
      }
    } finally {
      HRegion.closeHRegion(this.region);
      this.region = null;
    }
  }

  public void testAllColumnsWithBloomFilter() throws IOException {
    byte [] TABLE = Bytes.toBytes("testAllColumnsWithBloomFilter");
    byte [] FAMILY = Bytes.toBytes("family");

    //Create table
    HColumnDescriptor hcd = new HColumnDescriptor(FAMILY)
        .setMaxVersions(Integer.MAX_VALUE)
        .setBloomFilterType(BloomType.ROWCOL);
    HTableDescriptor htd = new HTableDescriptor(TABLE);
    htd.addFamily(hcd);
    HRegionInfo info = new HRegionInfo(htd.getName(), null, null, false);
    Path path = new Path(DIR + "testAllColumnsWithBloomFilter");
    this.region = HRegion.createHRegion(info, path, conf, htd);
    try {
      // For row:0, col:0: insert versions 1 through 5.
      byte row[] = Bytes.toBytes("row:" + 0);
      byte column[] = Bytes.toBytes("column:" + 0);
      Put put = new Put(row);
      put.setWriteToWAL(false);
      for (long idx = 1; idx <= 4; idx++) {
        put.add(FAMILY, column, idx, Bytes.toBytes("value-version-" + idx));
      }
      region.put(put);

      //Flush
      region.flushcache();

      //Get rows
      Get get = new Get(row);
      get.setMaxVersions();
      KeyValue[] kvs = region.get(get, null).raw();

      //Check if rows are correct
      assertEquals(4, kvs.length);
      checkOneCell(kvs[0], FAMILY, 0, 0, 4);
      checkOneCell(kvs[1], FAMILY, 0, 0, 3);
      checkOneCell(kvs[2], FAMILY, 0, 0, 2);
      checkOneCell(kvs[3], FAMILY, 0, 0, 1);
    } finally {
      HRegion.closeHRegion(this.region);
      this.region = null;
    }
  }

  /**
    * Testcase to cover bug-fix for HBASE-2823
    * Ensures correct delete when issuing delete row
    * on columns with bloom filter set to row+col (BloomType.ROWCOL)
   */
  public void testDeleteRowWithBloomFilter() throws IOException {
    byte [] tableName = Bytes.toBytes("testDeleteRowWithBloomFilter");
    byte [] familyName = Bytes.toBytes("familyName");

    // Create Table
    HColumnDescriptor hcd = new HColumnDescriptor(familyName)
        .setMaxVersions(Integer.MAX_VALUE)
        .setBloomFilterType(BloomType.ROWCOL);

    HTableDescriptor htd = new HTableDescriptor(tableName);
    htd.addFamily(hcd);
    HRegionInfo info = new HRegionInfo(htd.getName(), null, null, false);
    Path path = new Path(DIR + "TestDeleteRowWithBloomFilter");
    this.region = HRegion.createHRegion(info, path, conf, htd);
    try {
      // Insert some data
      byte row[] = Bytes.toBytes("row1");
      byte col[] = Bytes.toBytes("col1");

      Put put = new Put(row);
      put.add(familyName, col, 1, Bytes.toBytes("SomeRandomValue"));
      region.put(put);
      region.flushcache();

      Delete del = new Delete(row);
      region.delete(del, null, true);
      region.flushcache();

      // Get remaining rows (should have none)
      Get get = new Get(row);
      get.addColumn(familyName, col);

      KeyValue[] keyValues = region.get(get, null).raw();
      assertTrue(keyValues.length == 0);
    } finally {
      HRegion.closeHRegion(this.region);
      this.region = null;
    }
  }

  @Test public void testgetHDFSBlocksDistribution() throws Exception {
    HBaseTestingUtility htu = new HBaseTestingUtility();
    final int DEFAULT_BLOCK_SIZE = 1024;
    htu.getConfiguration().setLong("dfs.block.size", DEFAULT_BLOCK_SIZE);
    htu.getConfiguration().setInt("dfs.replication", 2);


    // set up a cluster with 3 nodes
    MiniHBaseCluster cluster = null;
    String dataNodeHosts[] = new String[] { "host1", "host2", "host3" };
    int regionServersCount = 3;

    try {
      cluster = htu.startMiniCluster(1, regionServersCount, dataNodeHosts);
      byte [][] families = {fam1, fam2};
      HTable ht = htu.createTable(Bytes.toBytes(this.getName()), families);

      //Setting up region
      byte row[] = Bytes.toBytes("row1");
      byte col[] = Bytes.toBytes("col1");

      Put put = new Put(row);
      put.add(fam1, col, 1, Bytes.toBytes("test1"));
      put.add(fam2, col, 1, Bytes.toBytes("test2"));
      ht.put(put);

      HRegion firstRegion = htu.getHBaseCluster().
          getRegions(Bytes.toBytes(this.getName())).get(0);
      firstRegion.flushcache();
      HDFSBlocksDistribution blocksDistribution1 =
          firstRegion.getHDFSBlocksDistribution();

      // given the default replication factor is 2 and we have 2 HFiles,
      // we will have total of 4 replica of blocks on 3 datanodes; thus there
      // must be at least one host that have replica for 2 HFiles. That host's
      // weight will be equal to the unique block weight.
      long uniqueBlocksWeight1 =
          blocksDistribution1.getUniqueBlocksTotalWeight();

      String topHost = blocksDistribution1.getTopHosts().get(0);
      long topHostWeight = blocksDistribution1.getWeight(topHost);
      assertTrue(uniqueBlocksWeight1 == topHostWeight);

      // use the static method to compute the value, it should be the same.
      // static method is used by load balancer or other components
      HDFSBlocksDistribution blocksDistribution2 =
        HRegion.computeHDFSBlocksDistribution(htu.getConfiguration(),
        firstRegion.getTableDesc(),
        firstRegion.getRegionInfo().getEncodedName());
      long uniqueBlocksWeight2 =
        blocksDistribution2.getUniqueBlocksTotalWeight();

      assertTrue(uniqueBlocksWeight1 == uniqueBlocksWeight2);

      ht.close();
      } finally {
        if (cluster != null) {
          htu.shutdownMiniCluster();
        }
      }
  }

  /**
   * Test case to check put function with memstore flushing for same row, same ts
   * @throws Exception
   */
  public void testPutWithMemStoreFlush() throws Exception {
    Configuration conf = HBaseConfiguration.create();
    String method = "testPutWithMemStoreFlush";
    byte[] tableName = Bytes.toBytes(method);
    byte[] family = Bytes.toBytes("family");;
    byte[] qualifier = Bytes.toBytes("qualifier");
    byte[] row = Bytes.toBytes("putRow");
    byte[] value = null;
    this.region = initHRegion(tableName, method, conf, family);
    Put put = null;
    Get get = null;
    List<KeyValue> kvs = null;
    Result res = null;

    put = new Put(row);
    value = Bytes.toBytes("value0");
    put.add(family, qualifier, 1234567l, value);
    region.put(put);
    get = new Get(row);
    get.addColumn(family, qualifier);
    get.setMaxVersions();
    res = this.region.get(get, null);
    kvs = res.getColumn(family, qualifier);
    assertEquals(1, kvs.size());
    assertEquals(Bytes.toBytes("value0"), kvs.get(0).getValue());

    region.flushcache();
    get = new Get(row);
    get.addColumn(family, qualifier);
    get.setMaxVersions();
    res = this.region.get(get, null);
    kvs = res.getColumn(family, qualifier);
    assertEquals(1, kvs.size());
    assertEquals(Bytes.toBytes("value0"), kvs.get(0).getValue());

    put = new Put(row);
    value = Bytes.toBytes("value1");
    put.add(family, qualifier, 1234567l, value);
    region.put(put);
    get = new Get(row);
    get.addColumn(family, qualifier);
    get.setMaxVersions();
    res = this.region.get(get, null);
    kvs = res.getColumn(family, qualifier);
    assertEquals(1, kvs.size());
    assertEquals(Bytes.toBytes("value1"), kvs.get(0).getValue());

    region.flushcache();
    get = new Get(row);
    get.addColumn(family, qualifier);
    get.setMaxVersions();
    res = this.region.get(get, null);
    kvs = res.getColumn(family, qualifier);
    assertEquals(1, kvs.size());
    assertEquals(Bytes.toBytes("value1"), kvs.get(0).getValue());
  }
  
  /**
   * TestCase for increment
   *
   */
  private static class Incrementer implements Runnable {
    private HRegion region;
    private final static byte[] incRow = Bytes.toBytes("incRow");
    private final static byte[] family = Bytes.toBytes("family");
    private final static byte[] qualifier = Bytes.toBytes("qualifier");
    private final static long ONE = 1l;
    private int incCounter;

    public Incrementer(HRegion region, int incCounter) {
      this.region = region;
      this.incCounter = incCounter;
    }

    @Override
    public void run() {
      int count = 0;
      while (count < incCounter) {
        Increment inc = new Increment(incRow);
        inc.addColumn(family, qualifier, ONE);
        count++;
        try {
          region.increment(inc, null, true);
        } catch (IOException e) {
          e.printStackTrace();
          break;
        }
      }
    }
  }

  /**
   * TestCase for append
   * 
   */
  private static class Appender implements Runnable {
    private HRegion region;
    private final static byte[] appendRow = Bytes.toBytes("appendRow");
    private final static byte[] family = Bytes.toBytes("family");
    private final static byte[] qualifier = Bytes.toBytes("qualifier");
    private final static byte[] CHAR = Bytes.toBytes("a");
    private int appendCounter;

    public Appender(HRegion region, int appendCounter) {
      this.region = region;
      this.appendCounter = appendCounter;
    }

    @Override
    public void run() {
      int count = 0;
      while (count < appendCounter) {
        Append app = new Append(appendRow);
        app.add(family, qualifier, CHAR);
        count++;
        try {
          region.append(app, null, true);
        } catch (IOException e) {
          e.printStackTrace();
          break;
        }
      }
    }
  }

  /**
   * Test case to check append function with memstore flushing
   * 
   * @throws Exception
   */
  @Test
  public void testParallelAppendWithMemStoreFlush() throws Exception {
    Configuration conf = HBaseConfiguration.create();
    String method = "testParallelAppendWithMemStoreFlush";
    byte[] tableName = Bytes.toBytes(method);
    byte[] family = Appender.family;
    this.region = initHRegion(tableName, method, conf, family);
    final HRegion region = this.region;
    final AtomicBoolean appendDone = new AtomicBoolean(false);
    Runnable flusher = new Runnable() {
      @Override
      public void run() {
        while (!appendDone.get()) {
          try {
            region.flushcache();
          } catch (Exception e) {
            e.printStackTrace();
          }
        }
      }
    };

    // after all append finished, the value will append to threadNum * appendCounter Appender.CHAR
    int threadNum = 20;
    int appendCounter = 100;
    byte[] expected = new byte[threadNum * appendCounter];
    for (int i = 0; i < threadNum * appendCounter; i++) {
      System.arraycopy(Appender.CHAR, 0, expected, i, 1);
    }
    Thread[] appenders = new Thread[threadNum];
    Thread flushThread = new Thread(flusher);
    for (int i = 0; i < threadNum; i++) {
      appenders[i] = new Thread(new Appender(this.region, appendCounter));
      appenders[i].start();
    }
    flushThread.start();
    for (int i = 0; i < threadNum; i++) {
      appenders[i].join();
    }

    appendDone.set(true);
    flushThread.join();

    Get get = new Get(Appender.appendRow);
    get.addColumn(Appender.family, Appender.qualifier);
    get.setMaxVersions(1);
    Result res = this.region.get(get, null);
    List<KeyValue> kvs = res.getColumn(Appender.family, Appender.qualifier);

    // we just got the latest version
    assertEquals(kvs.size(), 1);
    KeyValue kv = kvs.get(0);
    byte[] appendResult = new byte[kv.getValueLength()];
    System.arraycopy(kv.getBuffer(), kv.getValueOffset(), appendResult, 0, kv.getValueLength());
    assertEquals(expected, appendResult);
    this.region = null;
  }
   
  /**
   * Test case to check increment function with memstore flushing
   * @throws Exception
   */
  @Test
  public void testParallelIncrementWithMemStoreFlush() throws Exception {
    String method = "testParallelIncrementWithMemStoreFlush";
    byte[] tableName = Bytes.toBytes(method);
    byte[] family = Incrementer.family;
    this.region = initHRegion(tableName, method, conf, family);
    final HRegion region = this.region;
    final AtomicBoolean incrementDone = new AtomicBoolean(false);
    Runnable flusher = new Runnable() {
      @Override
      public void run() {
        while (!incrementDone.get()) {
          try {
            region.flushcache();
          } catch (Exception e) {
            e.printStackTrace();
          }
        }
      }
    };

    //after all increment finished, the row will increment to 20*100 = 2000
    int threadNum = 20;
    int incCounter = 100;
    long expected = threadNum * incCounter;
    Thread[] incrementers = new Thread[threadNum];
    Thread flushThread = new Thread(flusher);
    for (int i = 0; i < threadNum; i++) {
      incrementers[i] = new Thread(new Incrementer(this.region, incCounter));
      incrementers[i].start();
    }
    flushThread.start();
    for (int i = 0; i < threadNum; i++) {
      incrementers[i].join();
    }

    incrementDone.set(true);
    flushThread.join();

    Get get = new Get(Incrementer.incRow);
    get.addColumn(Incrementer.family, Incrementer.qualifier);
    get.setMaxVersions(1);
    Result res = this.region.get(get, null);
    List<KeyValue> kvs = res.getColumn(Incrementer.family,
        Incrementer.qualifier);
    
    //we just got the latest version
    assertEquals(kvs.size(), 1);
    KeyValue kv = kvs.get(0);
    assertEquals(expected, Bytes.toLong(kv.getBuffer(), kv.getValueOffset()));
    this.region = null;
  }

  private void putData(int startRow, int numRows, byte [] qf,
      byte [] ...families)
  throws IOException {
    for(int i=startRow; i<startRow+numRows; i++) {
      Put put = new Put(Bytes.toBytes("" + i));
      put.setWriteToWAL(false);
      for(byte [] family : families) {
        put.add(family, qf, null);
      }
      region.put(put);
    }
  }

  private void verifyData(HRegion newReg, int startRow, int numRows, byte [] qf,
      byte [] ... families)
  throws IOException {
    for(int i=startRow; i<startRow + numRows; i++) {
      byte [] row = Bytes.toBytes("" + i);
      Get get = new Get(row);
      for(byte [] family : families) {
        get.addColumn(family, qf);
      }
      Result result = newReg.get(get, null);
      KeyValue [] raw = result.raw();
      assertEquals(families.length, result.size());
      for(int j=0; j<families.length; j++) {
        assertEquals(0, Bytes.compareTo(row, raw[j].getRow()));
        assertEquals(0, Bytes.compareTo(families[j], raw[j].getFamily()));
        assertEquals(0, Bytes.compareTo(qf, raw[j].getQualifier()));
      }
    }
  }

  private void assertGet(final HRegion r, final byte [] family, final byte [] k)
  throws IOException {
    // Now I have k, get values out and assert they are as expected.
    Get get = new Get(k).addFamily(family).setMaxVersions();
    KeyValue [] results = r.get(get, null).raw();
    for (int j = 0; j < results.length; j++) {
      byte [] tmp = results[j].getValue();
      // Row should be equal to value every time.
      assertTrue(Bytes.equals(k, tmp));
    }
  }

  /*
   * Assert first value in the passed region is <code>firstValue</code>.
   * @param r
   * @param fs
   * @param firstValue
   * @throws IOException
   */
  private void assertScan(final HRegion r, final byte [] fs,
      final byte [] firstValue)
  throws IOException {
    byte [][] families = {fs};
    Scan scan = new Scan();
    for (int i = 0; i < families.length; i++) scan.addFamily(families[i]);
    InternalScanner s = r.getScanner(scan);
    try {
      List<KeyValue> curVals = new ArrayList<KeyValue>();
      boolean first = true;
      OUTER_LOOP: while(s.next(curVals)) {
        for (KeyValue kv: curVals) {
          byte [] val = kv.getValue();
          byte [] curval = val;
          if (first) {
            first = false;
            assertTrue(Bytes.compareTo(curval, firstValue) == 0);
          } else {
            // Not asserting anything.  Might as well break.
            break OUTER_LOOP;
          }
        }
      }
    } finally {
      s.close();
    }
  }

  private Configuration initSplit() {
    Configuration conf = HBaseConfiguration.create(this.conf);

    // Always compact if there is more than one store file.
    conf.setInt("hbase.hstore.compactionThreshold", 2);

    // Make lease timeout longer, lease checks less frequent
    conf.setInt("hbase.master.lease.thread.wakefrequency", 5 * 1000);

    conf.setInt(HConstants.HBASE_REGIONSERVER_LEASE_PERIOD_KEY, 10 * 1000);

    // Increase the amount of time between client retries
    conf.setLong("hbase.client.pause", 15 * 1000);

    // This size should make it so we always split using the addContent
    // below.  After adding all data, the first region is 1.3M
    conf.setLong(HConstants.HREGION_MAX_FILESIZE, 1024 * 128);
    return conf;
  }

  /**
   * @param tableName
   * @param callingMethod
   * @param conf
   * @param families
   * @throws IOException
   * @return A region on which you must call {@link HRegion#closeHRegion(HRegion)} when done.
   */
  public static HRegion initHRegion (byte [] tableName, String callingMethod,
      Configuration conf, byte [] ... families)
    throws IOException{
    return initHRegion(tableName, null, null, callingMethod, conf, false, families);
  }

  /**
   * @param tableName
   * @param callingMethod
   * @param conf
   * @param isReadOnly
   * @param families
   * @throws IOException
   * @return A region on which you must call {@link HRegion#closeHRegion(HRegion)} when done.
   */
  public static HRegion initHRegion (byte [] tableName, String callingMethod,
      Configuration conf, boolean isReadOnly, byte [] ... families)
    throws IOException{
    return initHRegion(tableName, null, null, callingMethod, conf, isReadOnly, families);
  }

  /**
   * @param tableName
   * @param startKey
   * @param stopKey
   * @param callingMethod
   * @param conf
   * @param isReadOnly
   * @param families
   * @throws IOException
   * @return A region on which you must call {@link HRegion#closeHRegion(HRegion)} when done.
   */
  private static HRegion initHRegion(byte[] tableName, byte[] startKey, byte[] stopKey,
      String callingMethod, Configuration conf, boolean isReadOnly, byte[]... families)
      throws IOException {
    HTableDescriptor htd = new HTableDescriptor(tableName);
    htd.setReadOnly(isReadOnly);
    for(byte [] family : families) {
      htd.addFamily(new HColumnDescriptor(family));
    }
    HRegionInfo info = new HRegionInfo(htd.getName(), startKey, stopKey, false);
    Path path = new Path(DIR + callingMethod);
    FileSystem fs = FileSystem.get(conf);
    if (fs.exists(path)) {
      if (!fs.delete(path, true)) {
        throw new IOException("Failed delete of " + path);
      }
    }
    return HRegion.createHRegion(info, path, conf, htd);
  }

  /**
   * Assert that the passed in KeyValue has expected contents for the
   * specified row, column & timestamp.
   */
  private void checkOneCell(KeyValue kv, byte[] cf,
                             int rowIdx, int colIdx, long ts) {
    String ctx = "rowIdx=" + rowIdx + "; colIdx=" + colIdx + "; ts=" + ts;
    assertEquals("Row mismatch which checking: " + ctx,
                 "row:"+ rowIdx, Bytes.toString(kv.getRow()));
    assertEquals("ColumnFamily mismatch while checking: " + ctx,
                 Bytes.toString(cf), Bytes.toString(kv.getFamily()));
    assertEquals("Column qualifier mismatch while checking: " + ctx,
                 "column:" + colIdx, Bytes.toString(kv.getQualifier()));
    assertEquals("Timestamp mismatch while checking: " + ctx,
                 ts, kv.getTimestamp());
    assertEquals("Value mismatch while checking: " + ctx,
                 "value-version-" + ts, Bytes.toString(kv.getValue()));
  }


  @org.junit.Rule
  public org.apache.hadoop.hbase.ResourceCheckerJUnitRule cu =
    new org.apache.hadoop.hbase.ResourceCheckerJUnitRule();
}

