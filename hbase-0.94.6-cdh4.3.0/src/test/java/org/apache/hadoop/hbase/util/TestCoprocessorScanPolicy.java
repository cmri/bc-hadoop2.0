/*
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
package org.apache.hadoop.hbase.util;
// this is deliberately not in the o.a.h.h.regionserver package
// in order to make sure all required classes/method are available

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseTestingUtility;
import org.apache.hadoop.hbase.HColumnDescriptor;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.MediumTests;
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.HTable;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.coprocessor.BaseRegionObserver;
import org.apache.hadoop.hbase.coprocessor.CoprocessorHost;
import org.apache.hadoop.hbase.coprocessor.ObserverContext;
import org.apache.hadoop.hbase.coprocessor.RegionCoprocessorEnvironment;
import org.apache.hadoop.hbase.regionserver.InternalScanner;
import org.apache.hadoop.hbase.regionserver.KeyValueScanner;
import org.apache.hadoop.hbase.regionserver.ScanType;
import org.apache.hadoop.hbase.regionserver.Store;
import org.apache.hadoop.hbase.regionserver.StoreScanner;
import org.apache.hadoop.hbase.regionserver.wal.WALEdit;
import org.apache.hadoop.hbase.util.Bytes;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import static org.junit.Assert.*;

@Category(MediumTests.class)
public class TestCoprocessorScanPolicy {
  final Log LOG = LogFactory.getLog(getClass());
  protected final static HBaseTestingUtility TEST_UTIL = new HBaseTestingUtility();
  private static final byte[] F = Bytes.toBytes("fam");
  private static final byte[] Q = Bytes.toBytes("qual");
  private static final byte[] R = Bytes.toBytes("row");


  @BeforeClass
  public static void setUpBeforeClass() throws Exception {
    Configuration conf = TEST_UTIL.getConfiguration();
    conf.setStrings(CoprocessorHost.REGION_COPROCESSOR_CONF_KEY,
        ScanObserver.class.getName());
    TEST_UTIL.startMiniCluster();
  }

  @AfterClass
  public static void tearDownAfterClass() throws Exception {
    TEST_UTIL.shutdownMiniCluster();
  }

  @Test
  public void testBaseCases() throws Exception {
    byte[] tableName = Bytes.toBytes("baseCases");
    HTable t = TEST_UTIL.createTable(tableName, F, 1);
    // set the version override to 2
    Put p = new Put(R);
    p.setAttribute("versions", new byte[]{});
    p.add(F, tableName, Bytes.toBytes(2));
    t.put(p);

    long now = EnvironmentEdgeManager.currentTimeMillis();

    // insert 2 versions
    p = new Put(R);
    p.add(F, Q, now, Q);
    t.put(p);
    p = new Put(R);
    p.add(F, Q, now+1, Q);
    t.put(p);
    Get g = new Get(R);
    g.setMaxVersions(10);
    Result r = t.get(g);
    assertEquals(2, r.size());

    TEST_UTIL.flush(tableName);
    TEST_UTIL.compact(tableName, true);

    // both version are still visible even after a flush/compaction
    g = new Get(R);
    g.setMaxVersions(10);
    r = t.get(g);
    assertEquals(2, r.size());

    // insert a 3rd version
    p = new Put(R);
    p.add(F, Q, now+2, Q);
    t.put(p);
    g = new Get(R);
    g.setMaxVersions(10);
    r = t.get(g);
    // still only two version visible
    assertEquals(2, r.size());

    t.close();
  }

  @Test
  public void testTTL() throws Exception {
    byte[] tableName = Bytes.toBytes("testTTL");
    HTableDescriptor desc = new HTableDescriptor(tableName);
    HColumnDescriptor hcd = new HColumnDescriptor(F)
    .setMaxVersions(10)
    .setTimeToLive(1);
    desc.addFamily(hcd);
    TEST_UTIL.getHBaseAdmin().createTable(desc);
    HTable t = new HTable(new Configuration(TEST_UTIL.getConfiguration()), tableName);
    long now = EnvironmentEdgeManager.currentTimeMillis();
    ManualEnvironmentEdge me = new ManualEnvironmentEdge();
    me.setValue(now);
    EnvironmentEdgeManagerTestHelper.injectEdge(me);
    // 2s in the past
    long ts = now - 2000;
    // Set the TTL override to 3s
    Put p = new Put(R);
    p.setAttribute("ttl", new byte[]{});
    p.add(F, tableName, Bytes.toBytes(3000L));
    t.put(p);

    p = new Put(R);
    p.add(F, Q, ts, Q);
    t.put(p);
    p = new Put(R);
    p.add(F, Q, ts+1, Q);
    t.put(p);

    // these two should be expired but for the override
    // (their ts was 2s in the past)
    Get g = new Get(R);
    g.setMaxVersions(10);
    Result r = t.get(g);
    // still there?
    assertEquals(2, r.size());

    TEST_UTIL.flush(tableName);
    TEST_UTIL.compact(tableName, true);

    g = new Get(R);
    g.setMaxVersions(10);
    r = t.get(g);
    // still there?
    assertEquals(2, r.size());

    // roll time forward 2s.
    me.setValue(now + 2000);
    // now verify that data eventually does expire
    g = new Get(R);
    g.setMaxVersions(10);
    r = t.get(g);
    // should be gone now
    assertEquals(0, r.size());
    t.close();
  }

  public static class ScanObserver extends BaseRegionObserver {
    private Map<String, Long> ttls = new HashMap<String,Long>();
    private Map<String, Integer> versions = new HashMap<String,Integer>();

    // lame way to communicate with the coprocessor,
    // since it is loaded by a different class loader
    @Override
    public void prePut(final ObserverContext<RegionCoprocessorEnvironment> c, final Put put,
        final WALEdit edit, final boolean writeToWAL) throws IOException {
      if (put.getAttribute("ttl") != null) {
        KeyValue kv = put.getFamilyMap().values().iterator().next().get(0);
        ttls.put(Bytes.toString(kv.getQualifier()), Bytes.toLong(kv.getValue()));
        c.bypass();
      } else if (put.getAttribute("versions") != null) {
        KeyValue kv = put.getFamilyMap().values().iterator().next().get(0);
        versions.put(Bytes.toString(kv.getQualifier()), Bytes.toInt(kv.getValue()));
        c.bypass();
      }
    }

    @Override
    public InternalScanner preFlushScannerOpen(final ObserverContext<RegionCoprocessorEnvironment> c,
        Store store, KeyValueScanner memstoreScanner, InternalScanner s) throws IOException {
      Long newTtl = ttls.get(store.getTableName());
      if (newTtl != null) {
        System.out.println("PreFlush:" + newTtl);
      }
      Integer newVersions = versions.get(store.getTableName());
      Store.ScanInfo oldSI = store.getScanInfo();
      HColumnDescriptor family = store.getFamily();
      Store.ScanInfo scanInfo = new Store.ScanInfo(family.getName(), family.getMinVersions(),
          newVersions == null ? family.getMaxVersions() : newVersions,
          newTtl == null ? oldSI.getTtl() : newTtl, family.getKeepDeletedCells(),
          oldSI.getTimeToPurgeDeletes(), oldSI.getComparator());
      Scan scan = new Scan();
      scan.setMaxVersions(newVersions == null ? oldSI.getMaxVersions() : newVersions);
      return new StoreScanner(store, scanInfo, scan, Collections.singletonList(memstoreScanner),
          ScanType.MINOR_COMPACT, store.getHRegion().getSmallestReadPoint(),
          HConstants.OLDEST_TIMESTAMP);
    }

    @Override
    public InternalScanner preCompactScannerOpen(final ObserverContext<RegionCoprocessorEnvironment> c,
        Store store, List<? extends KeyValueScanner> scanners, ScanType scanType,
        long earliestPutTs, InternalScanner s) throws IOException {
      Long newTtl = ttls.get(store.getTableName());
      Integer newVersions = versions.get(store.getTableName());
      Store.ScanInfo oldSI = store.getScanInfo();
      HColumnDescriptor family = store.getFamily();
      Store.ScanInfo scanInfo = new Store.ScanInfo(family.getName(), family.getMinVersions(),
          newVersions == null ? family.getMaxVersions() : newVersions,
          newTtl == null ? oldSI.getTtl() : newTtl, family.getKeepDeletedCells(),
          oldSI.getTimeToPurgeDeletes(), oldSI.getComparator());
      Scan scan = new Scan();
      scan.setMaxVersions(newVersions == null ? oldSI.getMaxVersions() : newVersions);
      return new StoreScanner(store, scanInfo, scan, scanners, scanType, store.getHRegion()
          .getSmallestReadPoint(), earliestPutTs);
    }

    @Override
    public KeyValueScanner preStoreScannerOpen(
        final ObserverContext<RegionCoprocessorEnvironment> c, Store store, final Scan scan,
        final NavigableSet<byte[]> targetCols, KeyValueScanner s) throws IOException {
      Long newTtl = ttls.get(store.getTableName());
      Integer newVersions = versions.get(store.getTableName());
      Store.ScanInfo oldSI = store.getScanInfo();
      HColumnDescriptor family = store.getFamily();
      Store.ScanInfo scanInfo = new Store.ScanInfo(family.getName(), family.getMinVersions(),
          newVersions == null ? family.getMaxVersions() : newVersions,
          newTtl == null ? oldSI.getTtl() : newTtl, family.getKeepDeletedCells(),
          oldSI.getTimeToPurgeDeletes(), oldSI.getComparator());
      return new StoreScanner(store, scanInfo, scan, targetCols);
    }
  }

  @org.junit.Rule
  public org.apache.hadoop.hbase.ResourceCheckerJUnitRule cu =
   new org.apache.hadoop.hbase.ResourceCheckerJUnitRule();
}
