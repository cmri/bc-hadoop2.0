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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.HBaseTestingUtility;
import org.apache.hadoop.hbase.HColumnDescriptor;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.HRegionInfo;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.Server;
import org.apache.hadoop.hbase.SmallTests;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.io.hfile.CacheConfig;
import org.apache.hadoop.hbase.io.hfile.HFile;
import org.apache.hadoop.hbase.io.hfile.LruBlockCache;
import org.apache.hadoop.hbase.regionserver.wal.HLog;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.PairOfSameType;
import org.apache.zookeeper.KeeperException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.mockito.Mockito;

import com.google.common.collect.ImmutableList;

/**
 * Test the {@link SplitTransaction} class against an HRegion (as opposed to
 * running cluster).
 */
@Category(SmallTests.class)
public class TestSplitTransaction {
  private final HBaseTestingUtility TEST_UTIL = new HBaseTestingUtility();
  private final Path testdir =
    TEST_UTIL.getDataTestDir(this.getClass().getName());
  private HRegion parent;
  private HLog wal;
  private FileSystem fs;
  private static final byte [] STARTROW = new byte [] {'a', 'a', 'a'};
  // '{' is next ascii after 'z'.
  private static final byte [] ENDROW = new byte [] {'{', '{', '{'};
  private static final byte [] GOOD_SPLIT_ROW = new byte [] {'d', 'd', 'd'};
  private static final byte [] CF = HConstants.CATALOG_FAMILY;
  
  @Before public void setup() throws IOException {
    this.fs = FileSystem.get(TEST_UTIL.getConfiguration());
    this.fs.delete(this.testdir, true);
    this.wal = new HLog(fs, new Path(this.testdir, "logs"),
      new Path(this.testdir, "archive"),
      TEST_UTIL.getConfiguration());
    this.parent = createRegion(this.testdir, this.wal);
    TEST_UTIL.getConfiguration().setBoolean("hbase.testing.nocluster", true);
  }

  @After public void teardown() throws IOException {
    if (this.parent != null && !this.parent.isClosed()) this.parent.close();
    if (this.fs.exists(this.parent.getRegionDir()) &&
        !this.fs.delete(this.parent.getRegionDir(), true)) {
      throw new IOException("Failed delete of " + this.parent.getRegionDir());
    }
    if (this.wal != null) this.wal.closeAndDelete();
    this.fs.delete(this.testdir, true);
  }

  @Test public void testFailAfterPONR() throws IOException, KeeperException {
    final int rowcount = TEST_UTIL.loadRegion(this.parent, CF);
    assertTrue(rowcount > 0);
    int parentRowCount = countRows(this.parent);
    assertEquals(rowcount, parentRowCount);

    // Start transaction.
    SplitTransaction st = prepareGOOD_SPLIT_ROW();
    SplitTransaction spiedUponSt = spy(st);
    Mockito
        .doThrow(new MockedFailedDaughterOpen())
        .when(spiedUponSt)
        .openDaughterRegion((Server) Mockito.anyObject(),
            (HRegion) Mockito.anyObject());

    // Run the execute.  Look at what it returns.
    boolean expectedException = false;
    Server mockServer = Mockito.mock(Server.class);
    when(mockServer.getConfiguration()).thenReturn(TEST_UTIL.getConfiguration());
    try {
      spiedUponSt.execute(mockServer, null);
    } catch (IOException e) {
      if (e.getCause() != null &&
          e.getCause() instanceof MockedFailedDaughterOpen) {
        expectedException = true;
      }
    }
    assertTrue(expectedException);
    // Run rollback returns that we should restart.
    assertFalse(spiedUponSt.rollback(null, null));
    // Make sure that region a and region b are still in the filesystem, that
    // they have not been removed; this is supposed to be the case if we go
    // past point of no return.
    Path tableDir =  this.parent.getRegionDir().getParent();
    Path daughterADir =
      new Path(tableDir, spiedUponSt.getFirstDaughter().getEncodedName());
    Path daughterBDir =
      new Path(tableDir, spiedUponSt.getSecondDaughter().getEncodedName());
    assertTrue(TEST_UTIL.getTestFileSystem().exists(daughterADir));
    assertTrue(TEST_UTIL.getTestFileSystem().exists(daughterBDir));
  }

  /**
   * Test straight prepare works.  Tries to split on {@link #GOOD_SPLIT_ROW}
   * @throws IOException
   */
  @Test public void testPrepare() throws IOException {
    prepareGOOD_SPLIT_ROW();
  }

  private SplitTransaction prepareGOOD_SPLIT_ROW() {
    SplitTransaction st = new SplitTransaction(this.parent, GOOD_SPLIT_ROW);
    assertTrue(st.prepare());
    return st;
  }

  /**
   * Pass a reference store
   */
  @Test public void testPrepareWithRegionsWithReference() throws IOException {
    // create a mock that will act as a reference StoreFile
    StoreFile storeFileMock  = Mockito.mock(StoreFile.class);
    when(storeFileMock.isReference()).thenReturn(true);

    // add the mock to the parent stores
    Store storeMock = Mockito.mock(Store.class);
    List<StoreFile> storeFileList = new ArrayList<StoreFile>(1);
    storeFileList.add(storeFileMock);
    when(storeMock.getStorefiles()).thenReturn(storeFileList);
    when(storeMock.close()).thenReturn(ImmutableList.copyOf(storeFileList));
    this.parent.stores.put(Bytes.toBytes(""), storeMock);

    SplitTransaction st = new SplitTransaction(this.parent, GOOD_SPLIT_ROW);

    assertFalse("a region should not be splittable if it has instances of store file references",
                st.prepare());
  }

  /**
   * Pass an unreasonable split row.
   */
  @Test public void testPrepareWithBadSplitRow() throws IOException {
    // Pass start row as split key.
    SplitTransaction st = new SplitTransaction(this.parent, STARTROW);
    assertFalse(st.prepare());
    st = new SplitTransaction(this.parent, HConstants.EMPTY_BYTE_ARRAY);
    assertFalse(st.prepare());
    st = new SplitTransaction(this.parent, new byte [] {'A', 'A', 'A'});
    assertFalse(st.prepare());
    st = new SplitTransaction(this.parent, ENDROW);
    assertFalse(st.prepare());
  }

  @Test public void testPrepareWithClosedRegion() throws IOException {
    this.parent.close();
    SplitTransaction st = new SplitTransaction(this.parent, GOOD_SPLIT_ROW);
    assertFalse(st.prepare());
  }

  @Test public void testWholesomeSplitWithHFileV1() throws IOException {
    int defaultVersion = TEST_UTIL.getConfiguration().getInt(
        HFile.FORMAT_VERSION_KEY, 2);
    TEST_UTIL.getConfiguration().setInt(HFile.FORMAT_VERSION_KEY, 1);
    try {
      for (Store store : this.parent.stores.values()) {
        store.getFamily().setBloomFilterType(StoreFile.BloomType.ROW);
      }
      testWholesomeSplit();
    } finally {
      TEST_UTIL.getConfiguration().setInt(HFile.FORMAT_VERSION_KEY,
          defaultVersion);
    }
  }

  @Test public void testWholesomeSplit() throws IOException {
    final int rowcount = TEST_UTIL.loadRegion(this.parent, CF, true);
    assertTrue(rowcount > 0);
    int parentRowCount = countRows(this.parent);
    assertEquals(rowcount, parentRowCount);

    // Pretend region's blocks are not in the cache, used for
    // testWholesomeSplitWithHFileV1
    CacheConfig cacheConf = new CacheConfig(TEST_UTIL.getConfiguration());
    ((LruBlockCache) cacheConf.getBlockCache()).clearCache();

    // Start transaction.
    SplitTransaction st = prepareGOOD_SPLIT_ROW();

    // Run the execute.  Look at what it returns.
    Server mockServer = Mockito.mock(Server.class);
    when(mockServer.getConfiguration()).thenReturn(TEST_UTIL.getConfiguration());
    PairOfSameType<HRegion> daughters = st.execute(mockServer, null);
    // Do some assertions about execution.
    assertTrue(this.fs.exists(st.getSplitDir()));
    // Assert the parent region is closed.
    assertTrue(this.parent.isClosed());

    // Assert splitdir is empty -- because its content will have been moved out
    // to be under the daughter region dirs.
    assertEquals(0, this.fs.listStatus(st.getSplitDir()).length);
    // Check daughters have correct key span.
    assertTrue(Bytes.equals(this.parent.getStartKey(),
      daughters.getFirst().getStartKey()));
    assertTrue(Bytes.equals(GOOD_SPLIT_ROW,
      daughters.getFirst().getEndKey()));
    assertTrue(Bytes.equals(daughters.getSecond().getStartKey(),
      GOOD_SPLIT_ROW));
    assertTrue(Bytes.equals(this.parent.getEndKey(),
      daughters.getSecond().getEndKey()));
    // Count rows.
    int daughtersRowCount = 0;
    for (HRegion r: daughters) {
      // Open so can count its content.
      HRegion openRegion = HRegion.openHRegion(this.testdir, r.getRegionInfo(),
         r.getTableDesc(), r.getLog(), r.getConf());
      try {
        int count = countRows(openRegion);
        assertTrue(count > 0 && count != rowcount);
        daughtersRowCount += count;
      } finally {
        openRegion.close();
        openRegion.getLog().closeAndDelete();
      }
    }
    assertEquals(rowcount, daughtersRowCount);
    // Assert the write lock is no longer held on parent
    assertTrue(!this.parent.lock.writeLock().isHeldByCurrentThread());
  }

  @Test public void testRollback() throws IOException {
    final int rowcount = TEST_UTIL.loadRegion(this.parent, CF);
    assertTrue(rowcount > 0);
    int parentRowCount = countRows(this.parent);
    assertEquals(rowcount, parentRowCount);

    // Start transaction.
    SplitTransaction st = prepareGOOD_SPLIT_ROW();
    SplitTransaction spiedUponSt = spy(st);
    when(spiedUponSt.createDaughterRegion(spiedUponSt.getSecondDaughter(), null)).
      thenThrow(new MockedFailedDaughterCreation());
    // Run the execute.  Look at what it returns.
    boolean expectedException = false;
    Server mockServer = Mockito.mock(Server.class);
    when(mockServer.getConfiguration()).thenReturn(TEST_UTIL.getConfiguration());
    try {
      spiedUponSt.execute(mockServer, null);
    } catch (MockedFailedDaughterCreation e) {
      expectedException = true;
    }
    assertTrue(expectedException);
    // Run rollback
    assertTrue(spiedUponSt.rollback(null, null));

    // Assert I can scan parent.
    int parentRowCount2 = countRows(this.parent);
    assertEquals(parentRowCount, parentRowCount2);

    // Assert rollback cleaned up stuff in fs
    assertTrue(!this.fs.exists(HRegion.getRegionDir(this.testdir, st.getFirstDaughter())));
    assertTrue(!this.fs.exists(HRegion.getRegionDir(this.testdir, st.getSecondDaughter())));
    assertTrue(!this.parent.lock.writeLock().isHeldByCurrentThread());

    // Now retry the split but do not throw an exception this time.
    assertTrue(st.prepare());
    PairOfSameType<HRegion> daughters = st.execute(mockServer, null);
    // Count rows.
    int daughtersRowCount = 0;
    for (HRegion r: daughters) {
      // Open so can count its content.
      HRegion openRegion = HRegion.openHRegion(this.testdir, r.getRegionInfo(),
         r.getTableDesc(), r.getLog(), r.getConf());
      try {
        int count = countRows(openRegion);
        assertTrue(count > 0 && count != rowcount);
        daughtersRowCount += count;
      } finally {
        openRegion.close();
        openRegion.getLog().closeAndDelete();
      }
    }
    assertEquals(rowcount, daughtersRowCount);
    // Assert the write lock is no longer held on parent
    assertTrue(!this.parent.lock.writeLock().isHeldByCurrentThread());
  }

  /**
   * Exception used in this class only.
   */
  @SuppressWarnings("serial")
  private class MockedFailedDaughterCreation extends IOException {}
  private class MockedFailedDaughterOpen extends IOException {}

  private int countRows(final HRegion r) throws IOException {
    int rowcount = 0;
    InternalScanner scanner = r.getScanner(new Scan());
    try {
      List<KeyValue> kvs = new ArrayList<KeyValue>();
      boolean hasNext = true;
      while (hasNext) {
        hasNext = scanner.next(kvs);
        if (!kvs.isEmpty()) rowcount++;
      }
    } finally {
      scanner.close();
    }
    return rowcount;
  }

  HRegion createRegion(final Path testdir, final HLog wal)
  throws IOException {
    // Make a region with start and end keys. Use 'aaa', to 'AAA'.  The load
    // region utility will add rows between 'aaa' and 'zzz'.
    HTableDescriptor htd = new HTableDescriptor("table");
    HColumnDescriptor hcd = new HColumnDescriptor(CF);
    htd.addFamily(hcd);
    HRegionInfo hri = new HRegionInfo(htd.getName(), STARTROW, ENDROW);
    HRegion r = HRegion.createHRegion(hri, testdir, TEST_UTIL.getConfiguration(), htd);
    r.close();
    r.getLog().closeAndDelete();
    return HRegion.openHRegion(testdir, hri, htd, wal,
      TEST_UTIL.getConfiguration());
  }

  @org.junit.Rule
  public org.apache.hadoop.hbase.ResourceCheckerJUnitRule cu =
    new org.apache.hadoop.hbase.ResourceCheckerJUnitRule();
}

