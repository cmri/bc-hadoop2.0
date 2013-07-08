/**
 * Copyright 2011 The Apache Software Foundation
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
package org.apache.hadoop.hbase.mapred;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.anyObject;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

import java.io.IOException;
import java.util.Arrays;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.hbase.*;
import org.apache.hadoop.hbase.client.HTable;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.ResultScanner;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.io.ImmutableBytesWritable;
import org.apache.hadoop.hbase.util.Bytes;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

/**
 * This tests the TableInputFormat and its recovery semantics
 * 
 */
@Category(LargeTests.class)
public class TestTableInputFormat {

  private static final Log LOG = LogFactory.getLog(TestTableInputFormat.class);

  private final static HBaseTestingUtility UTIL = new HBaseTestingUtility();
  static final byte[] FAMILY = Bytes.toBytes("family");

  private static final byte[][] columns = new byte[][] { FAMILY };

  @BeforeClass
  public static void beforeClass() throws Exception {
    UTIL.startMiniCluster();
  }

  @AfterClass
  public static void afterClass() throws Exception {
    UTIL.shutdownMiniCluster();
  }

  @Before
  public void before() throws IOException {
    LOG.info("before");
    UTIL.ensureSomeRegionServersAvailable(1);
    LOG.info("before done");
  }

  /**
   * Setup a table with two rows and values.
   * 
   * @param tableName
   * @return
   * @throws IOException
   */
  public static HTable createTable(byte[] tableName) throws IOException {
    HTable table = UTIL.createTable(tableName, FAMILY);
    Put p = new Put("aaa".getBytes());
    p.add(FAMILY, null, "value aaa".getBytes());
    table.put(p);
    p = new Put("bbb".getBytes());
    p.add(FAMILY, null, "value bbb".getBytes());
    table.put(p);
    return table;
  }

  /**
   * Verify that the result and key have expected values.
   * 
   * @param r
   * @param key
   * @param expectedKey
   * @param expectedValue
   * @return
   */
  static boolean checkResult(Result r, ImmutableBytesWritable key,
      byte[] expectedKey, byte[] expectedValue) {
    assertEquals(0, key.compareTo(expectedKey));
    Map<byte[], byte[]> vals = r.getFamilyMap(FAMILY);
    byte[] value = vals.values().iterator().next();
    assertTrue(Arrays.equals(value, expectedValue));
    return true; // if succeed
  }

  /**
   * Create table data and run tests on specified htable using the
   * o.a.h.hbase.mapred API.
   * 
   * @param table
   * @throws IOException
   */
  static void runTestMapred(HTable table) throws IOException {
    org.apache.hadoop.hbase.mapred.TableRecordReader trr = 
        new org.apache.hadoop.hbase.mapred.TableRecordReader();
    trr.setStartRow("aaa".getBytes());
    trr.setEndRow("zzz".getBytes());
    trr.setHTable(table);
    trr.setInputColumns(columns);

    trr.init();
    Result r = new Result();
    ImmutableBytesWritable key = new ImmutableBytesWritable();

    boolean more = trr.next(key, r);
    assertTrue(more);
    checkResult(r, key, "aaa".getBytes(), "value aaa".getBytes());

    more = trr.next(key, r);
    assertTrue(more);
    checkResult(r, key, "bbb".getBytes(), "value bbb".getBytes());

    // no more data
    more = trr.next(key, r);
    assertFalse(more);
  }

  /**
   * Create table data and run tests on specified htable using the
   * o.a.h.hbase.mapreduce API.
   * 
   * @param table
   * @throws IOException
   * @throws InterruptedException
   */
  static void runTestMapreduce(HTable table) throws IOException,
      InterruptedException {
    org.apache.hadoop.hbase.mapreduce.TableRecordReaderImpl trr = 
        new org.apache.hadoop.hbase.mapreduce.TableRecordReaderImpl();
    Scan s = new Scan();
    s.setStartRow("aaa".getBytes());
    s.setStopRow("zzz".getBytes());
    s.addFamily(FAMILY);
    trr.setScan(s);
    trr.setHTable(table);

    trr.initialize(null, null);
    Result r = new Result();
    ImmutableBytesWritable key = new ImmutableBytesWritable();

    boolean more = trr.nextKeyValue();
    assertTrue(more);
    key = trr.getCurrentKey();
    r = trr.getCurrentValue();
    checkResult(r, key, "aaa".getBytes(), "value aaa".getBytes());

    more = trr.nextKeyValue();
    assertTrue(more);
    key = trr.getCurrentKey();
    r = trr.getCurrentValue();
    checkResult(r, key, "bbb".getBytes(), "value bbb".getBytes());

    // no more data
    more = trr.nextKeyValue();
    assertFalse(more);
  }

  /**
   * Create a table that IOE's on first scanner next call
   * 
   * @throws IOException
   */
  static HTable createIOEScannerTable(byte[] name, final int failCnt)
      throws IOException {
    // build up a mock scanner stuff to fail the first time
    Answer<ResultScanner> a = new Answer<ResultScanner>() {
      int cnt = 0;

      @Override
      public ResultScanner answer(InvocationOnMock invocation) throws Throwable {
        // first invocation return the busted mock scanner
        if (cnt++ < failCnt) {
          // create mock ResultScanner that always fails.
          Scan scan = mock(Scan.class);
          doReturn("bogus".getBytes()).when(scan).getStartRow(); // avoid npe
          ResultScanner scanner = mock(ResultScanner.class);
          // simulate TimeoutException / IOException
          doThrow(new IOException("Injected exception")).when(scanner).next();
          return scanner;
        }

        // otherwise return the real scanner.
        return (ResultScanner) invocation.callRealMethod();
      }
    };

    HTable htable = spy(createTable(name));
    doAnswer(a).when(htable).getScanner((Scan) anyObject());
    return htable;
  }

  /**
   * Create a table that throws a DoNoRetryIOException on first scanner next
   * call
   * 
   * @throws IOException
   */
  static HTable createDNRIOEScannerTable(byte[] name, final int failCnt)
      throws IOException {
    // build up a mock scanner stuff to fail the first time
    Answer<ResultScanner> a = new Answer<ResultScanner>() {
      int cnt = 0;

      @Override
      public ResultScanner answer(InvocationOnMock invocation) throws Throwable {
        // first invocation return the busted mock scanner
        if (cnt++ < failCnt) {
          // create mock ResultScanner that always fails.
          Scan scan = mock(Scan.class);
          doReturn("bogus".getBytes()).when(scan).getStartRow(); // avoid npe
          ResultScanner scanner = mock(ResultScanner.class);

          invocation.callRealMethod(); // simulate UnknownScannerException
          doThrow(
              new UnknownScannerException("Injected simulated TimeoutException"))
              .when(scanner).next();
          return scanner;
        }

        // otherwise return the real scanner.
        return (ResultScanner) invocation.callRealMethod();
      }
    };

    HTable htable = spy(createTable(name));
    doAnswer(a).when(htable).getScanner((Scan) anyObject());
    return htable;
  }

  /**
   * Run test assuming no errors using mapred api.
   * 
   * @throws IOException
   */
  @Test
  public void testTableRecordReader() throws IOException {
    HTable table = createTable("table1".getBytes());
    runTestMapred(table);
  }

  /**
   * Run test assuming Scanner IOException failure using mapred api,
   * 
   * @throws IOException
   */
  @Test
  public void testTableRecordReaderScannerFail() throws IOException {
    HTable htable = createIOEScannerTable("table2".getBytes(), 1);
    runTestMapred(htable);
  }

  /**
   * Run test assuming Scanner IOException failure using mapred api,
   * 
   * @throws IOException
   */
  @Test(expected = IOException.class)
  public void testTableRecordReaderScannerFailTwice() throws IOException {
    HTable htable = createIOEScannerTable("table3".getBytes(), 2);
    runTestMapred(htable);
  }

  /**
   * Run test assuming UnknownScannerException (which is a type of
   * DoNotRetryIOException) using mapred api.
   * 
   * @throws DoNotRetryIOException
   */
  @Test
  public void testTableRecordReaderScannerTimeout() throws IOException {
    HTable htable = createDNRIOEScannerTable("table4".getBytes(), 1);
    runTestMapred(htable);
  }

  /**
   * Run test assuming UnknownScannerException (which is a type of
   * DoNotRetryIOException) using mapred api.
   * 
   * @throws DoNotRetryIOException
   */
  @Test(expected = DoNotRetryIOException.class)
  public void testTableRecordReaderScannerTimeoutTwice() throws IOException {
    HTable htable = createDNRIOEScannerTable("table5".getBytes(), 2);
    runTestMapred(htable);
  }

  /**
   * Run test assuming no errors using newer mapreduce api
   * 
   * @throws IOException
   * @throws InterruptedException
   */
  @Test
  public void testTableRecordReaderMapreduce() throws IOException,
      InterruptedException {
    HTable table = createTable("table1-mr".getBytes());
    runTestMapreduce(table);
  }

  /**
   * Run test assuming Scanner IOException failure using newer mapreduce api
   * 
   * @throws IOException
   * @throws InterruptedException
   */
  @Test
  public void testTableRecordReaderScannerFailMapreduce() throws IOException,
      InterruptedException {
    HTable htable = createIOEScannerTable("table2-mr".getBytes(), 1);
    runTestMapreduce(htable);
  }

  /**
   * Run test assuming Scanner IOException failure using newer mapreduce api
   * 
   * @throws IOException
   * @throws InterruptedException
   */
  @Test(expected = IOException.class)
  public void testTableRecordReaderScannerFailMapreduceTwice() throws IOException,
      InterruptedException {
    HTable htable = createIOEScannerTable("table3-mr".getBytes(), 2);
    runTestMapreduce(htable);
  }

  /**
   * Run test assuming UnknownScannerException (which is a type of
   * DoNotRetryIOException) using newer mapreduce api
   * 
   * @throws InterruptedException
   * @throws DoNotRetryIOException
   */
  @Test
  public void testTableRecordReaderScannerTimeoutMapreduce()
      throws IOException, InterruptedException {
    HTable htable = createDNRIOEScannerTable("table4-mr".getBytes(), 1);
    runTestMapreduce(htable);
  }

  /**
   * Run test assuming UnknownScannerException (which is a type of
   * DoNotRetryIOException) using newer mapreduce api
   * 
   * @throws InterruptedException
   * @throws DoNotRetryIOException
   */
  @Test(expected = DoNotRetryIOException.class)
  public void testTableRecordReaderScannerTimeoutMapreduceTwice()
      throws IOException, InterruptedException {
    HTable htable = createDNRIOEScannerTable("table5-mr".getBytes(), 2);
    runTestMapreduce(htable);
  }

  @org.junit.Rule
  public org.apache.hadoop.hbase.ResourceCheckerJUnitRule cu =
    new org.apache.hadoop.hbase.ResourceCheckerJUnitRule();
}

