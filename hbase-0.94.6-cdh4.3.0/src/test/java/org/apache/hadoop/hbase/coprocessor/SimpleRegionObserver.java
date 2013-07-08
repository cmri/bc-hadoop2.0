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

package org.apache.hadoop.hbase.coprocessor;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Arrays;
import java.util.NavigableSet;

import com.google.common.collect.ImmutableList;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.hbase.CoprocessorEnvironment;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.Mutation;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Delete;
import org.apache.hadoop.hbase.client.Increment;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.regionserver.HRegion;
import org.apache.hadoop.hbase.regionserver.InternalScanner;
import org.apache.hadoop.hbase.regionserver.KeyValueScanner;
import org.apache.hadoop.hbase.regionserver.Leases;
import org.apache.hadoop.hbase.regionserver.MiniBatchOperationInProgress;
import org.apache.hadoop.hbase.regionserver.RegionScanner;
import org.apache.hadoop.hbase.regionserver.ScanType;
import org.apache.hadoop.hbase.regionserver.Store;
import org.apache.hadoop.hbase.regionserver.StoreFile;
import org.apache.hadoop.hbase.regionserver.wal.WALEdit;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.Pair;

/**
 * A sample region observer that tests the RegionObserver interface.
 * It works with TestRegionObserverInterface to provide the test case.
 */
public class SimpleRegionObserver extends BaseRegionObserver {
  static final Log LOG = LogFactory.getLog(TestRegionObserverInterface.class);

  boolean beforeDelete = true;
  boolean scannerOpened = false;
  boolean hadPreOpen;
  boolean hadPostOpen;
  boolean hadPreClose;
  boolean hadPostClose;
  boolean hadPreFlush;
  boolean hadPreFlushScannerOpen;
  boolean hadPostFlush;
  boolean hadPreSplit;
  boolean hadPostSplit;
  boolean hadPreCompactSelect;
  boolean hadPostCompactSelect;
  boolean hadPreCompactScanner;
  boolean hadPreCompact;
  boolean hadPostCompact;
  boolean hadPreGet = false;
  boolean hadPostGet = false;
  boolean hadPrePut = false;
  boolean hadPostPut = false;
  boolean hadPreDeleted = false;
  boolean hadPostDeleted = false;
  boolean hadPreGetClosestRowBefore = false;
  boolean hadPostGetClosestRowBefore = false;
  boolean hadPreIncrement = false;
  boolean hadPostIncrement = false;
  boolean hadPreWALRestored = false;
  boolean hadPostWALRestored = false;
  boolean hadPreScannerNext = false;
  boolean hadPostScannerNext = false;
  boolean hadPreScannerClose = false;
  boolean hadPostScannerClose = false;
  boolean hadPreScannerOpen = false;
  boolean hadPreStoreScannerOpen = false;
  boolean hadPostScannerOpen = false;
  boolean hadPreBulkLoadHFile = false;
  boolean hadPostBulkLoadHFile = false;
  boolean hadPreBatchMutate = false;
  boolean hadPostBatchMutate = false;
  
  @Override
  public void start(CoprocessorEnvironment e) throws IOException {
    // this only makes sure that leases and locks are available to coprocessors
    // from external packages
    RegionCoprocessorEnvironment re = (RegionCoprocessorEnvironment)e;
    Leases leases = re.getRegionServerServices().getLeases();
    leases.createLease("x", null);
    leases.cancelLease("x");
  }

  @Override
  public void preOpen(ObserverContext<RegionCoprocessorEnvironment> c) {
    hadPreOpen = true;
  }

  @Override
  public void postOpen(ObserverContext<RegionCoprocessorEnvironment> c) {
    hadPostOpen = true;
  }

  public boolean wasOpened() {
    return hadPreOpen && hadPostOpen;
  }

  @Override
  public void preClose(ObserverContext<RegionCoprocessorEnvironment> c, boolean abortRequested) {
    hadPreClose = true;
  }

  @Override
  public void postClose(ObserverContext<RegionCoprocessorEnvironment> c, boolean abortRequested) {
    hadPostClose = true;
  }

  public boolean wasClosed() {
    return hadPreClose && hadPostClose;
  }

  @Override
  public InternalScanner preFlush(ObserverContext<RegionCoprocessorEnvironment> c, Store store, InternalScanner scanner) {
    hadPreFlush = true;
    return scanner;
  }

  @Override
  public InternalScanner preFlushScannerOpen(final ObserverContext<RegionCoprocessorEnvironment> c,
      Store store, KeyValueScanner memstoreScanner, InternalScanner s) throws IOException {
    hadPreFlushScannerOpen = true;
    return null;
  }

  @Override
  public void postFlush(ObserverContext<RegionCoprocessorEnvironment> c, Store store, StoreFile resultFile) {
    hadPostFlush = true;
  }

  public boolean wasFlushed() {
    return hadPreFlush && hadPostFlush;
  }

  @Override
  public void preSplit(ObserverContext<RegionCoprocessorEnvironment> c) {
    hadPreSplit = true;
  }

  @Override
  public void postSplit(ObserverContext<RegionCoprocessorEnvironment> c, HRegion l, HRegion r) {
    hadPostSplit = true;
  }

  public boolean wasSplit() {
    return hadPreSplit && hadPostSplit;
  }

  @Override
  public void preCompactSelection(ObserverContext<RegionCoprocessorEnvironment> c,
      Store store, List<StoreFile> candidates) {
    hadPreCompactSelect = true;
  }

  @Override
  public void postCompactSelection(ObserverContext<RegionCoprocessorEnvironment> c,
      Store store, ImmutableList<StoreFile> selected) {
    hadPostCompactSelect = true;
  }

  @Override
  public InternalScanner preCompact(ObserverContext<RegionCoprocessorEnvironment> e,
      Store store, InternalScanner scanner) {
    hadPreCompact = true;
    return scanner;
  }

  @Override
  public InternalScanner preCompactScannerOpen(final ObserverContext<RegionCoprocessorEnvironment> c,
      Store store, List<? extends KeyValueScanner> scanners, ScanType scanType, long earliestPutTs,
      InternalScanner s) throws IOException {
    hadPreCompactScanner = true;
    return null;
  }

  @Override
  public void postCompact(ObserverContext<RegionCoprocessorEnvironment> e,
      Store store, StoreFile resultFile) {
    hadPostCompact = true;
  }

  public boolean wasCompacted() {
    return hadPreCompact && hadPostCompact;
  }

  @Override
  public RegionScanner preScannerOpen(final ObserverContext<RegionCoprocessorEnvironment> c,
      final Scan scan,
      final RegionScanner s) throws IOException {
    hadPreScannerOpen = true;
    return null;
  }

  @Override
  public KeyValueScanner preStoreScannerOpen(final ObserverContext<RegionCoprocessorEnvironment> c,
      final Store store, final Scan scan, final NavigableSet<byte[]> targetCols,
      final KeyValueScanner s) throws IOException {
    hadPreStoreScannerOpen = true;
    return null;
  }

  @Override
  public RegionScanner postScannerOpen(final ObserverContext<RegionCoprocessorEnvironment> c,
      final Scan scan, final RegionScanner s)
      throws IOException {
    hadPostScannerOpen = true;
    return s;
  }

  @Override
  public boolean preScannerNext(final ObserverContext<RegionCoprocessorEnvironment> c,
      final InternalScanner s, final List<Result> results,
      final int limit, final boolean hasMore) throws IOException {
    hadPreScannerNext = true;
    return hasMore;
  }

  @Override
  public boolean postScannerNext(final ObserverContext<RegionCoprocessorEnvironment> c,
      final InternalScanner s, final List<Result> results, final int limit,
      final boolean hasMore) throws IOException {
    hadPostScannerNext = true;
    return hasMore;
  }

  @Override
  public void preScannerClose(final ObserverContext<RegionCoprocessorEnvironment> c,
      final InternalScanner s) throws IOException {
    hadPreScannerClose = true;
  }

  @Override
  public void postScannerClose(final ObserverContext<RegionCoprocessorEnvironment> c,
      final InternalScanner s) throws IOException {
    hadPostScannerClose = true;
  }

  @Override
  public void preGet(final ObserverContext<RegionCoprocessorEnvironment> c, final Get get,
      final List<KeyValue> results) throws IOException {
    RegionCoprocessorEnvironment e = c.getEnvironment();
    assertNotNull(e);
    assertNotNull(e.getRegion());
    assertNotNull(get);
    assertNotNull(results);
    hadPreGet = true;
  }

  @Override
  public void postGet(final ObserverContext<RegionCoprocessorEnvironment> c, final Get get,
      final List<KeyValue> results) {
    RegionCoprocessorEnvironment e = c.getEnvironment();
    assertNotNull(e);
    assertNotNull(e.getRegion());
    assertNotNull(get);
    assertNotNull(results);
    if (Arrays.equals(e.getRegion().getTableDesc().getName(),
        TestRegionObserverInterface.TEST_TABLE)) {
      boolean foundA = false;
      boolean foundB = false;
      boolean foundC = false;
      for (KeyValue kv: results) {
        if (Bytes.equals(kv.getFamily(), TestRegionObserverInterface.A)) {
          foundA = true;
        }
        if (Bytes.equals(kv.getFamily(), TestRegionObserverInterface.B)) {
          foundB = true;
        }
        if (Bytes.equals(kv.getFamily(), TestRegionObserverInterface.C)) {
          foundC = true;
        }
      }
      assertTrue(foundA);
      assertTrue(foundB);
      assertTrue(foundC);
    }
    hadPostGet = true;
  }

  @Override
  public void prePut(final ObserverContext<RegionCoprocessorEnvironment> c, 
      final Put put, final WALEdit edit,
      final boolean writeToWAL) throws IOException {
    Map<byte[], List<KeyValue>> familyMap  = put.getFamilyMap();
    RegionCoprocessorEnvironment e = c.getEnvironment();
    assertNotNull(e);
    assertNotNull(e.getRegion());
    assertNotNull(familyMap);
    if (Arrays.equals(e.getRegion().getTableDesc().getName(),
        TestRegionObserverInterface.TEST_TABLE)) {
      List<KeyValue> kvs = familyMap.get(TestRegionObserverInterface.A);
      assertNotNull(kvs);
      assertNotNull(kvs.get(0));
      assertTrue(Bytes.equals(kvs.get(0).getQualifier(),
          TestRegionObserverInterface.A));
      kvs = familyMap.get(TestRegionObserverInterface.B);
      assertNotNull(kvs);
      assertNotNull(kvs.get(0));
      assertTrue(Bytes.equals(kvs.get(0).getQualifier(),
          TestRegionObserverInterface.B));
      kvs = familyMap.get(TestRegionObserverInterface.C);
      assertNotNull(kvs);
      assertNotNull(kvs.get(0));
      assertTrue(Bytes.equals(kvs.get(0).getQualifier(),
          TestRegionObserverInterface.C));
    }
    hadPrePut = true;
  }

  @Override
  public void postPut(final ObserverContext<RegionCoprocessorEnvironment> c,
      final Put put, final WALEdit edit,
      final boolean writeToWAL) throws IOException {
    Map<byte[], List<KeyValue>> familyMap  = put.getFamilyMap();
    RegionCoprocessorEnvironment e = c.getEnvironment();
    assertNotNull(e);
    assertNotNull(e.getRegion());
    assertNotNull(familyMap);
    List<KeyValue> kvs = familyMap.get(TestRegionObserverInterface.A);
    if (Arrays.equals(e.getRegion().getTableDesc().getName(),
        TestRegionObserverInterface.TEST_TABLE)) {
      assertNotNull(kvs);
      assertNotNull(kvs.get(0));
      assertTrue(Bytes.equals(kvs.get(0).getQualifier(),
          TestRegionObserverInterface.A));
      kvs = familyMap.get(TestRegionObserverInterface.B);
      assertNotNull(kvs);
      assertNotNull(kvs.get(0));
      assertTrue(Bytes.equals(kvs.get(0).getQualifier(),
          TestRegionObserverInterface.B));
      kvs = familyMap.get(TestRegionObserverInterface.C);
      assertNotNull(kvs);
      assertNotNull(kvs.get(0));
      assertTrue(Bytes.equals(kvs.get(0).getQualifier(),
          TestRegionObserverInterface.C));
    }
    hadPostPut = true;
  }

  @Override
  public void preDelete(final ObserverContext<RegionCoprocessorEnvironment> c, 
      final Delete delete, final WALEdit edit,
      final boolean writeToWAL) throws IOException {
    Map<byte[], List<KeyValue>> familyMap  = delete.getFamilyMap();
    RegionCoprocessorEnvironment e = c.getEnvironment();
    assertNotNull(e);
    assertNotNull(e.getRegion());
    assertNotNull(familyMap);
    if (beforeDelete) {
      hadPreDeleted = true;
    }
  }

  @Override
  public void postDelete(final ObserverContext<RegionCoprocessorEnvironment> c, 
      final Delete delete, final WALEdit edit,
      final boolean writeToWAL) throws IOException {
    Map<byte[], List<KeyValue>> familyMap  = delete.getFamilyMap();
    RegionCoprocessorEnvironment e = c.getEnvironment();
    assertNotNull(e);
    assertNotNull(e.getRegion());
    assertNotNull(familyMap);
    beforeDelete = false;
    hadPostDeleted = true;
  }

  @Override
  public void preBatchMutate(ObserverContext<RegionCoprocessorEnvironment> c,
      MiniBatchOperationInProgress<Pair<Mutation, Integer>> miniBatchOp) throws IOException {
    RegionCoprocessorEnvironment e = c.getEnvironment();
    assertNotNull(e);
    assertNotNull(e.getRegion());
    assertNotNull(miniBatchOp);
    hadPreBatchMutate = true;
  }

  @Override
  public void postBatchMutate(final ObserverContext<RegionCoprocessorEnvironment> c,
      final MiniBatchOperationInProgress<Pair<Mutation, Integer>> miniBatchOp) throws IOException {
    RegionCoprocessorEnvironment e = c.getEnvironment();
    assertNotNull(e);
    assertNotNull(e.getRegion());
    assertNotNull(miniBatchOp);
    hadPostBatchMutate = true;
  }
  
  @Override
  public void preGetClosestRowBefore(final ObserverContext<RegionCoprocessorEnvironment> c,
      final byte[] row, final byte[] family, final Result result)
      throws IOException {
    RegionCoprocessorEnvironment e = c.getEnvironment();
    assertNotNull(e);
    assertNotNull(e.getRegion());
    assertNotNull(row);
    assertNotNull(result);
    if (beforeDelete) {
      hadPreGetClosestRowBefore = true;
    }
  }

  @Override
  public void postGetClosestRowBefore(final ObserverContext<RegionCoprocessorEnvironment> c,
      final byte[] row, final byte[] family, final Result result)
      throws IOException {
    RegionCoprocessorEnvironment e = c.getEnvironment();
    assertNotNull(e);
    assertNotNull(e.getRegion());
    assertNotNull(row);
    assertNotNull(result);
    hadPostGetClosestRowBefore = true;
  }

  @Override
  public Result preIncrement(final ObserverContext<RegionCoprocessorEnvironment> c,
      final Increment increment) throws IOException {
    hadPreIncrement = true;
    return null;
  }

  @Override
  public Result postIncrement(final ObserverContext<RegionCoprocessorEnvironment> c,
      final Increment increment, final Result result) throws IOException {
    hadPostIncrement = true;
    return result;
  }

  @Override
  public void preBulkLoadHFile(ObserverContext<RegionCoprocessorEnvironment> ctx,
                               List<Pair<byte[], String>> familyPaths) throws IOException {
    RegionCoprocessorEnvironment e = ctx.getEnvironment();
    assertNotNull(e);
    assertNotNull(e.getRegion());
    if (Arrays.equals(e.getRegion().getTableDesc().getName(),
        TestRegionObserverInterface.TEST_TABLE)) {
      assertNotNull(familyPaths);
      assertEquals(1,familyPaths.size());
      assertArrayEquals(familyPaths.get(0).getFirst(), TestRegionObserverInterface.A);
      String familyPath = familyPaths.get(0).getSecond();
      String familyName = Bytes.toString(TestRegionObserverInterface.A);
      assertEquals(familyPath.substring(familyPath.length()-familyName.length()-1),"/"+familyName);
    }
    hadPreBulkLoadHFile = true;
  }

  @Override
  public boolean postBulkLoadHFile(ObserverContext<RegionCoprocessorEnvironment> ctx,
                                   List<Pair<byte[], String>> familyPaths, boolean hasLoaded) throws IOException {
    RegionCoprocessorEnvironment e = ctx.getEnvironment();
    assertNotNull(e);
    assertNotNull(e.getRegion());
    if (Arrays.equals(e.getRegion().getTableDesc().getName(),
        TestRegionObserverInterface.TEST_TABLE)) {
      assertNotNull(familyPaths);
      assertEquals(1,familyPaths.size());
      assertArrayEquals(familyPaths.get(0).getFirst(), TestRegionObserverInterface.A);
      String familyPath = familyPaths.get(0).getSecond();
      String familyName = Bytes.toString(TestRegionObserverInterface.A);
      assertEquals(familyPath.substring(familyPath.length()-familyName.length()-1),"/"+familyName);
    }
    hadPostBulkLoadHFile = true;
    return hasLoaded;
  }

  public boolean hadPreGet() {
    return hadPreGet;
  }

  public boolean hadPostGet() {
    return hadPostGet;
  }

  public boolean hadPrePut() {
    return hadPrePut;
  }

  public boolean hadPostPut() {
    return hadPostPut;
  }
  
  public boolean hadPreBatchMutate() {
    return hadPreBatchMutate;
  }

  public boolean hadPostBatchMutate() {
    return hadPostBatchMutate;
  }
  
  public boolean hadDelete() {
    return !beforeDelete;
  }

  public boolean hadPreIncrement() {
    return hadPreIncrement;
  }

  public boolean hadPostIncrement() {
    return hadPostIncrement;
  }

  public boolean hadPreWALRestored() {
    return hadPreWALRestored;
  }

  public boolean hadPostWALRestored() {
    return hadPostWALRestored;
  }
  public boolean wasScannerNextCalled() {
    return hadPreScannerNext && hadPostScannerNext;
  }
  public boolean wasScannerCloseCalled() {
    return hadPreScannerClose && hadPostScannerClose;
  }
  public boolean wasScannerOpenCalled() {
    return hadPreScannerOpen && hadPostScannerOpen;
  }
  public boolean hadDeleted() {
    return hadPreDeleted && hadPostDeleted;
  }

  public boolean hadPostBulkLoadHFile() {
    return hadPostBulkLoadHFile;
  }

  public boolean hadPreBulkLoadHFile() {
    return hadPreBulkLoadHFile;
  }
}
