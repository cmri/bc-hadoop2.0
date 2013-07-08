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
package org.apache.hadoop.hbase.thrift;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseTestingUtility;
import org.apache.hadoop.hbase.MediumTests;
import org.apache.hadoop.hbase.filter.ParseFilter;
import org.apache.hadoop.hbase.thrift.generated.BatchMutation;
import org.apache.hadoop.hbase.thrift.generated.ColumnDescriptor;
import org.apache.hadoop.hbase.thrift.generated.Hbase;
import org.apache.hadoop.hbase.thrift.generated.Mutation;
import org.apache.hadoop.hbase.thrift.generated.TCell;
import org.apache.hadoop.hbase.thrift.generated.TRegionInfo;
import org.apache.hadoop.hbase.thrift.generated.TRowResult;
import org.apache.hadoop.hbase.thrift.generated.TIncrement;
import org.apache.hadoop.hbase.thrift.ThriftServerRunner.HBaseHandler;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.metrics.ContextFactory;
import org.apache.hadoop.metrics.MetricsContext;
import org.apache.hadoop.metrics.MetricsUtil;
import org.apache.hadoop.metrics.spi.NoEmitMetricsContext;
import org.apache.hadoop.metrics.spi.OutputRecord;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * Unit testing for ThriftServerRunner.HBaseHandler, a part of the
 * org.apache.hadoop.hbase.thrift package.
 */
@Category(MediumTests.class)
public class TestThriftServer {
  private static final HBaseTestingUtility UTIL = new HBaseTestingUtility();
  private static final Log LOG = LogFactory.getLog(TestThriftServer.class);
  protected static final int MAXVERSIONS = 3;

  private static ByteBuffer asByteBuffer(String i) {
    return ByteBuffer.wrap(Bytes.toBytes(i));
  }
  private static ByteBuffer asByteBuffer(long l) {
    return ByteBuffer.wrap(Bytes.toBytes(l));
  }

  // Static names for tables, columns, rows, and values
  private static ByteBuffer tableAname = asByteBuffer("tableA");
  private static ByteBuffer tableBname = asByteBuffer("tableB");
  private static ByteBuffer columnAname = asByteBuffer("columnA:");
  private static ByteBuffer columnAAname = asByteBuffer("columnA:A");
  private static ByteBuffer columnBname = asByteBuffer("columnB:");
  private static ByteBuffer rowAname = asByteBuffer("rowA");
  private static ByteBuffer rowBname = asByteBuffer("rowB");
  private static ByteBuffer valueAname = asByteBuffer("valueA");
  private static ByteBuffer valueBname = asByteBuffer("valueB");
  private static ByteBuffer valueCname = asByteBuffer("valueC");
  private static ByteBuffer valueDname = asByteBuffer("valueD");
  private static ByteBuffer valueEname = asByteBuffer(100l);

  @BeforeClass
  public static void beforeClass() throws Exception {
    UTIL.getConfiguration().setBoolean(ThriftServerRunner.COALESCE_INC_KEY, true);
    UTIL.startMiniCluster();
  }

  @AfterClass
  public static void afterClass() throws Exception {
    UTIL.shutdownMiniCluster();
  }

  /**
   * Runs all of the tests under a single JUnit test method.  We
   * consolidate all testing to one method because HBaseClusterTestCase
   * is prone to OutOfMemoryExceptions when there are three or more
   * JUnit test methods.
   *
   * @throws Exception
   */
  @Test
  public void testAll() throws Exception {
    // Run all tests
    doTestTableCreateDrop();
    doTestThriftMetrics();
    doTestTableMutations();
    doTestTableTimestampsAndColumns();
    doTestTableScanners();
    doTestGetTableRegions();
    doTestFilterRegistration();
  }

  /**
   * Tests for creating, enabling, disabling, and deleting tables.  Also
   * tests that creating a table with an invalid column name yields an
   * IllegalArgument exception.
   *
   * @throws Exception
   */
  public void doTestTableCreateDrop() throws Exception {
    ThriftServerRunner.HBaseHandler handler =
      new ThriftServerRunner.HBaseHandler(UTIL.getConfiguration());
    doTestTableCreateDrop(handler);
  }
  public static void doTestTableCreateDrop(Hbase.Iface handler) throws Exception {
    createTestTables(handler);
    dropTestTables(handler);
  }

  /**
   * Tests if the metrics for thrift handler work correctly
   */
  public void doTestThriftMetrics() throws Exception {
    Configuration conf = UTIL.getConfiguration();
    ThriftMetrics metrics = getMetrics(conf);
    Hbase.Iface handler = getHandler(metrics, conf);
    createTestTables(handler);
    dropTestTables(handler);
    verifyMetrics(metrics, "createTable_num_ops", 2);
    verifyMetrics(metrics, "deleteTable_num_ops", 2);
    verifyMetrics(metrics, "disableTable_num_ops", 2);
  }

  private static Hbase.Iface getHandler(ThriftMetrics metrics, Configuration conf)
      throws Exception {
    Hbase.Iface handler =
      new ThriftServerRunner.HBaseHandler(conf);
    return HbaseHandlerMetricsProxy.newInstance(handler, metrics, conf);
  }

  private static ThriftMetrics getMetrics(Configuration conf) throws Exception {
    setupMetricsContext();
    return new ThriftMetrics(ThriftServerRunner.DEFAULT_LISTEN_PORT, conf, Hbase.Iface.class);
  }

  private static void setupMetricsContext() throws IOException {
    ContextFactory factory = ContextFactory.getFactory();
    factory.setAttribute(ThriftMetrics.CONTEXT_NAME + ".class",
        NoEmitMetricsContext.class.getName());
    MetricsUtil.getContext(ThriftMetrics.CONTEXT_NAME)
               .createRecord(ThriftMetrics.CONTEXT_NAME).remove();
  }

  private static void verifyMetrics(ThriftMetrics metrics, String name, int expectValue)
      throws Exception {
    MetricsContext context = MetricsUtil.getContext(
        ThriftMetrics.CONTEXT_NAME);
    metrics.doUpdates(context);
    OutputRecord record = context.getAllRecords().get(
        ThriftMetrics.CONTEXT_NAME).iterator().next();
    assertEquals(expectValue, record.getMetric(name).intValue());
  }

  public static void createTestTables(Hbase.Iface handler) throws Exception {
    // Create/enable/disable/delete tables, ensure methods act correctly
    assertEquals(handler.getTableNames().size(), 0);
    handler.createTable(tableAname, getColumnDescriptors());
    assertEquals(handler.getTableNames().size(), 1);
    assertEquals(handler.getColumnDescriptors(tableAname).size(), 2);
    assertTrue(handler.isTableEnabled(tableAname));
    handler.createTable(tableBname, new ArrayList<ColumnDescriptor>());
    assertEquals(handler.getTableNames().size(), 2);
  }

  public static void checkTableList(Hbase.Iface handler) throws Exception {
    assertTrue(handler.getTableNames().contains(tableAname));
  }

  public static void dropTestTables(Hbase.Iface handler) throws Exception {
    handler.disableTable(tableBname);
    assertFalse(handler.isTableEnabled(tableBname));
    handler.deleteTable(tableBname);
    assertEquals(handler.getTableNames().size(), 1);
    handler.disableTable(tableAname);
    /* TODO Reenable.
    assertFalse(handler.isTableEnabled(tableAname));
    handler.enableTable(tableAname);
    assertTrue(handler.isTableEnabled(tableAname));
    handler.disableTable(tableAname);*/
    handler.deleteTable(tableAname);
  }

  public void doTestIncrements() throws Exception {
    ThriftServerRunner.HBaseHandler handler =
        new ThriftServerRunner.HBaseHandler(UTIL.getConfiguration());
    createTestTables(handler);
    doTestIncrements(handler);
    dropTestTables(handler);
  }

  public static void doTestIncrements(HBaseHandler handler) throws Exception {
    List<Mutation> mutations = new ArrayList<Mutation>(1);
    mutations.add(new Mutation(false, columnAAname, valueEname, true));
    mutations.add(new Mutation(false, columnAname, valueEname, true));
    handler.mutateRow(tableAname, rowAname, mutations, null);
    handler.mutateRow(tableAname, rowBname, mutations, null);

    List<TIncrement> increments = new ArrayList<TIncrement>();
    increments.add(new TIncrement(tableAname, rowBname, columnAAname, 7));
    increments.add(new TIncrement(tableAname, rowBname, columnAAname, 7));
    increments.add(new TIncrement(tableAname, rowBname, columnAAname, 7));

    int numIncrements = 60000;
    for (int i = 0; i < numIncrements; i++) {
      handler.increment(new TIncrement(tableAname, rowAname, columnAname, 2));
      handler.incrementRows(increments);
    }

    Thread.sleep(1000);
    long lv = handler.get(tableAname, rowAname, columnAname, null).get(0).value.getLong();
    assertEquals((100 + (2 * numIncrements)), lv );


    lv = handler.get(tableAname, rowBname, columnAAname, null).get(0).value.getLong();
    assertEquals((100 + (3 * 7 * numIncrements)), lv);

    assertTrue(handler.coalescer.getSuccessfulCoalescings() > 0);

  }

  /**
   * Tests adding a series of Mutations and BatchMutations, including a
   * delete mutation.  Also tests data retrieval, and getting back multiple
   * versions.
   *
   * @throws Exception
   */
  public void doTestTableMutations() throws Exception {
    ThriftServerRunner.HBaseHandler handler =
      new ThriftServerRunner.HBaseHandler(UTIL.getConfiguration());
    doTestTableMutations(handler);
  }

  public static void doTestTableMutations(Hbase.Iface handler) throws Exception {
    // Setup
    handler.createTable(tableAname, getColumnDescriptors());

    // Apply a few Mutations to rowA
    //     mutations.add(new Mutation(false, columnAname, valueAname));
    //     mutations.add(new Mutation(false, columnBname, valueBname));
    handler.mutateRow(tableAname, rowAname, getMutations(), null);

    // Assert that the changes were made
    assertEquals(valueAname,
      handler.get(tableAname, rowAname, columnAname, null).get(0).value);
    TRowResult rowResult1 = handler.getRow(tableAname, rowAname, null).get(0);
    assertEquals(rowAname, rowResult1.row);
    assertEquals(valueBname,
      rowResult1.columns.get(columnBname).value);

    // Apply a few BatchMutations for rowA and rowB
    // rowAmutations.add(new Mutation(true, columnAname, null));
    // rowAmutations.add(new Mutation(false, columnBname, valueCname));
    // batchMutations.add(new BatchMutation(rowAname, rowAmutations));
    // Mutations to rowB
    // rowBmutations.add(new Mutation(false, columnAname, valueCname));
    // rowBmutations.add(new Mutation(false, columnBname, valueDname));
    // batchMutations.add(new BatchMutation(rowBname, rowBmutations));
    handler.mutateRows(tableAname, getBatchMutations(), null);

    // Assert that changes were made to rowA
    List<TCell> cells = handler.get(tableAname, rowAname, columnAname, null);
    assertFalse(cells.size() > 0);
    assertEquals(valueCname, handler.get(tableAname, rowAname, columnBname, null).get(0).value);
    List<TCell> versions = handler.getVer(tableAname, rowAname, columnBname, MAXVERSIONS, null);
    assertEquals(valueCname, versions.get(0).value);
    assertEquals(valueBname, versions.get(1).value);

    // Assert that changes were made to rowB
    TRowResult rowResult2 = handler.getRow(tableAname, rowBname, null).get(0);
    assertEquals(rowBname, rowResult2.row);
    assertEquals(valueCname, rowResult2.columns.get(columnAname).value);
    assertEquals(valueDname, rowResult2.columns.get(columnBname).value);

    // Apply some deletes
    handler.deleteAll(tableAname, rowAname, columnBname, null);
    handler.deleteAllRow(tableAname, rowBname, null);

    // Assert that the deletes were applied
    int size = handler.get(tableAname, rowAname, columnBname, null).size();
    assertEquals(0, size);
    size = handler.getRow(tableAname, rowBname, null).size();
    assertEquals(0, size);

    // Try null mutation
    List<Mutation> mutations = new ArrayList<Mutation>();
    mutations.add(new Mutation(false, columnAname, null, true));
    handler.mutateRow(tableAname, rowAname, mutations, null);
    TRowResult rowResult3 = handler.getRow(tableAname, rowAname, null).get(0);
    assertEquals(rowAname, rowResult3.row);
    assertEquals(0, rowResult3.columns.get(columnAname).value.remaining());

    // Teardown
    handler.disableTable(tableAname);
    handler.deleteTable(tableAname);
  }

  /**
   * Similar to testTableMutations(), except Mutations are applied with
   * specific timestamps and data retrieval uses these timestamps to
   * extract specific versions of data.
   *
   * @throws Exception
   */
  public void doTestTableTimestampsAndColumns() throws Exception {
    // Setup
    ThriftServerRunner.HBaseHandler handler =
      new ThriftServerRunner.HBaseHandler(UTIL.getConfiguration());
    handler.createTable(tableAname, getColumnDescriptors());

    // Apply timestamped Mutations to rowA
    long time1 = System.currentTimeMillis();
    handler.mutateRowTs(tableAname, rowAname, getMutations(), time1, null);

    Thread.sleep(1000);

    // Apply timestamped BatchMutations for rowA and rowB
    long time2 = System.currentTimeMillis();
    handler.mutateRowsTs(tableAname, getBatchMutations(), time2, null);

    // Apply an overlapping timestamped mutation to rowB
    handler.mutateRowTs(tableAname, rowBname, getMutations(), time2, null);

    // the getVerTs is [inf, ts) so you need to increment one.
    time1 += 1;
    time2 += 2;

    // Assert that the timestamp-related methods retrieve the correct data
    assertEquals(2, handler.getVerTs(tableAname, rowAname, columnBname, time2,
      MAXVERSIONS, null).size());
    assertEquals(1, handler.getVerTs(tableAname, rowAname, columnBname, time1,
      MAXVERSIONS, null).size());

    TRowResult rowResult1 = handler.getRowTs(tableAname, rowAname, time1, null).get(0);
    TRowResult rowResult2 = handler.getRowTs(tableAname, rowAname, time2, null).get(0);
    // columnA was completely deleted
    //assertTrue(Bytes.equals(rowResult1.columns.get(columnAname).value, valueAname));
    assertEquals(rowResult1.columns.get(columnBname).value, valueBname);
    assertEquals(rowResult2.columns.get(columnBname).value, valueCname);

    // ColumnAname has been deleted, and will never be visible even with a getRowTs()
    assertFalse(rowResult2.columns.containsKey(columnAname));

    List<ByteBuffer> columns = new ArrayList<ByteBuffer>();
    columns.add(columnBname);

    rowResult1 = handler.getRowWithColumns(tableAname, rowAname, columns, null).get(0);
    assertEquals(rowResult1.columns.get(columnBname).value, valueCname);
    assertFalse(rowResult1.columns.containsKey(columnAname));

    rowResult1 = handler.getRowWithColumnsTs(tableAname, rowAname, columns, time1, null).get(0);
    assertEquals(rowResult1.columns.get(columnBname).value, valueBname);
    assertFalse(rowResult1.columns.containsKey(columnAname));

    // Apply some timestamped deletes
    // this actually deletes _everything_.
    // nukes everything in columnB: forever.
    handler.deleteAllTs(tableAname, rowAname, columnBname, time1, null);
    handler.deleteAllRowTs(tableAname, rowBname, time2, null);

    // Assert that the timestamp-related methods retrieve the correct data
    int size = handler.getVerTs(tableAname, rowAname, columnBname, time1, MAXVERSIONS, null).size();
    assertEquals(0, size);

    size = handler.getVerTs(tableAname, rowAname, columnBname, time2, MAXVERSIONS, null).size();
    assertEquals(1, size);

    // should be available....
    assertEquals(handler.get(tableAname, rowAname, columnBname, null).get(0).value, valueCname);

    assertEquals(0, handler.getRow(tableAname, rowBname, null).size());

    // Teardown
    handler.disableTable(tableAname);
    handler.deleteTable(tableAname);
  }

  /**
   * Tests the four different scanner-opening methods (with and without
   * a stoprow, with and without a timestamp).
   *
   * @throws Exception
   */
  public void doTestTableScanners() throws Exception {
    // Setup
    ThriftServerRunner.HBaseHandler handler =
      new ThriftServerRunner.HBaseHandler(UTIL.getConfiguration());
    handler.createTable(tableAname, getColumnDescriptors());

    // Apply timestamped Mutations to rowA
    long time1 = System.currentTimeMillis();
    handler.mutateRowTs(tableAname, rowAname, getMutations(), time1, null);

    // Sleep to assure that 'time1' and 'time2' will be different even with a
    // coarse grained system timer.
    Thread.sleep(1000);

    // Apply timestamped BatchMutations for rowA and rowB
    long time2 = System.currentTimeMillis();
    handler.mutateRowsTs(tableAname, getBatchMutations(), time2, null);

    time1 += 1;

    // Test a scanner on all rows and all columns, no timestamp
    int scanner1 = handler.scannerOpen(tableAname, rowAname, getColumnList(true, true), null);
    TRowResult rowResult1a = handler.scannerGet(scanner1).get(0);
    assertEquals(rowResult1a.row, rowAname);
    // This used to be '1'.  I don't know why when we are asking for two columns
    // and when the mutations above would seem to add two columns to the row.
    // -- St.Ack 05/12/2009
    assertEquals(rowResult1a.columns.size(), 1);
    assertEquals(rowResult1a.columns.get(columnBname).value, valueCname);

    TRowResult rowResult1b = handler.scannerGet(scanner1).get(0);
    assertEquals(rowResult1b.row, rowBname);
    assertEquals(rowResult1b.columns.size(), 2);
    assertEquals(rowResult1b.columns.get(columnAname).value, valueCname);
    assertEquals(rowResult1b.columns.get(columnBname).value, valueDname);
    closeScanner(scanner1, handler);

    // Test a scanner on all rows and all columns, with timestamp
    int scanner2 = handler.scannerOpenTs(tableAname, rowAname, getColumnList(true, true), time1, null);
    TRowResult rowResult2a = handler.scannerGet(scanner2).get(0);
    assertEquals(rowResult2a.columns.size(), 1);
    // column A deleted, does not exist.
    //assertTrue(Bytes.equals(rowResult2a.columns.get(columnAname).value, valueAname));
    assertEquals(rowResult2a.columns.get(columnBname).value, valueBname);
    closeScanner(scanner2, handler);

    // Test a scanner on the first row and first column only, no timestamp
    int scanner3 = handler.scannerOpenWithStop(tableAname, rowAname, rowBname,
        getColumnList(true, false), null);
    closeScanner(scanner3, handler);

    // Test a scanner on the first row and second column only, with timestamp
    int scanner4 = handler.scannerOpenWithStopTs(tableAname, rowAname, rowBname,
        getColumnList(false, true), time1, null);
    TRowResult rowResult4a = handler.scannerGet(scanner4).get(0);
    assertEquals(rowResult4a.columns.size(), 1);
    assertEquals(rowResult4a.columns.get(columnBname).value, valueBname);

    // Teardown
    handler.disableTable(tableAname);
    handler.deleteTable(tableAname);
  }

  /**
   * For HBASE-2556
   * Tests for GetTableRegions
   *
   * @throws Exception
   */
  public void doTestGetTableRegions() throws Exception {
    ThriftServerRunner.HBaseHandler handler =
      new ThriftServerRunner.HBaseHandler(UTIL.getConfiguration());
    doTestGetTableRegions(handler);
  }

  public static void doTestGetTableRegions(Hbase.Iface handler)
      throws Exception {
    assertEquals(handler.getTableNames().size(), 0);
    handler.createTable(tableAname, getColumnDescriptors());
    assertEquals(handler.getTableNames().size(), 1);
    List<TRegionInfo> regions = handler.getTableRegions(tableAname);
    int regionCount = regions.size();
    assertEquals("empty table should have only 1 region, " +
            "but found " + regionCount, regionCount, 1);
    LOG.info("Region found:" + regions.get(0));
    handler.disableTable(tableAname);
    handler.deleteTable(tableAname);
    regionCount = handler.getTableRegions(tableAname).size();
    assertEquals("non-existing table should have 0 region, " +
            "but found " + regionCount, regionCount, 0);
  }

  public void doTestFilterRegistration() throws Exception {
    Configuration conf = UTIL.getConfiguration();

    conf.set("hbase.thrift.filters", "MyFilter:filterclass");

    ThriftServerRunner.registerFilters(conf);

    Map<String, String> registeredFilters = ParseFilter.getAllFilters();

    assertEquals("filterclass", registeredFilters.get("MyFilter"));
  }

  /**
   *
   * @return a List of ColumnDescriptors for use in creating a table.  Has one
   * default ColumnDescriptor and one ColumnDescriptor with fewer versions
   */
  private static List<ColumnDescriptor> getColumnDescriptors() {
    ArrayList<ColumnDescriptor> cDescriptors = new ArrayList<ColumnDescriptor>();

    // A default ColumnDescriptor
    ColumnDescriptor cDescA = new ColumnDescriptor();
    cDescA.name = columnAname;
    cDescriptors.add(cDescA);

    // A slightly customized ColumnDescriptor (only 2 versions)
    ColumnDescriptor cDescB = new ColumnDescriptor(columnBname, 2, "NONE",
        false, "NONE", 0, 0, false, -1);
    cDescriptors.add(cDescB);

    return cDescriptors;
  }

  /**
   *
   * @param includeA whether or not to include columnA
   * @param includeB whether or not to include columnB
   * @return a List of column names for use in retrieving a scanner
   */
  private List<ByteBuffer> getColumnList(boolean includeA, boolean includeB) {
    List<ByteBuffer> columnList = new ArrayList<ByteBuffer>();
    if (includeA) columnList.add(columnAname);
    if (includeB) columnList.add(columnBname);
    return columnList;
  }

  /**
   *
   * @return a List of Mutations for a row, with columnA having valueA
   * and columnB having valueB
   */
  private static List<Mutation> getMutations() {
    List<Mutation> mutations = new ArrayList<Mutation>();
    mutations.add(new Mutation(false, columnAname, valueAname, true));
    mutations.add(new Mutation(false, columnBname, valueBname, true));
    return mutations;
  }

  /**
   *
   * @return a List of BatchMutations with the following effects:
   * (rowA, columnA): delete
   * (rowA, columnB): place valueC
   * (rowB, columnA): place valueC
   * (rowB, columnB): place valueD
   */
  private static List<BatchMutation> getBatchMutations() {
    List<BatchMutation> batchMutations = new ArrayList<BatchMutation>();

    // Mutations to rowA.  You can't mix delete and put anymore.
    List<Mutation> rowAmutations = new ArrayList<Mutation>();
    rowAmutations.add(new Mutation(true, columnAname, null, true));
    batchMutations.add(new BatchMutation(rowAname, rowAmutations));

    rowAmutations = new ArrayList<Mutation>();
    rowAmutations.add(new Mutation(false, columnBname, valueCname, true));
    batchMutations.add(new BatchMutation(rowAname, rowAmutations));

    // Mutations to rowB
    List<Mutation> rowBmutations = new ArrayList<Mutation>();
    rowBmutations.add(new Mutation(false, columnAname, valueCname, true));
    rowBmutations.add(new Mutation(false, columnBname, valueDname, true));
    batchMutations.add(new BatchMutation(rowBname, rowBmutations));

    return batchMutations;
  }

  /**
   * Asserts that the passed scanner is exhausted, and then closes
   * the scanner.
   *
   * @param scannerId the scanner to close
   * @param handler the HBaseHandler interfacing to HBase
   * @throws Exception
   */
  private void closeScanner(
      int scannerId, ThriftServerRunner.HBaseHandler handler) throws Exception {
    handler.scannerGet(scannerId);
    handler.scannerClose(scannerId);
  }

  @org.junit.Rule
  public org.apache.hadoop.hbase.ResourceCheckerJUnitRule cu =
    new org.apache.hadoop.hbase.ResourceCheckerJUnitRule();
}

