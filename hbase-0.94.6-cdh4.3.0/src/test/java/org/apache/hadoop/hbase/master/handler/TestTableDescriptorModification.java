/**
 * Copyright The Apache Software Foundation
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
package org.apache.hadoop.hbase.master.handler;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.Set;

import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.HBaseTestingUtility;
import org.apache.hadoop.hbase.HColumnDescriptor;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.LargeTests;
import org.apache.hadoop.hbase.client.HBaseAdmin;
import org.apache.hadoop.hbase.master.MasterFileSystem;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.FSTableDescriptors;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * Verify that the HTableDescriptor is updated after
 * addColumn(), deleteColumn() and modifyTable() operations.
 */
@Category(LargeTests.class)
public class TestTableDescriptorModification {

  private static final HBaseTestingUtility TEST_UTIL = new HBaseTestingUtility();
  private static final byte[] TABLE_NAME = Bytes.toBytes("table");
  private static final byte[] FAMILY_0 = Bytes.toBytes("cf0");
  private static final byte[] FAMILY_1 = Bytes.toBytes("cf1");

  /**
   * Start up a mini cluster and put a small table of empty regions into it.
   *
   * @throws Exception
   */
  @BeforeClass
  public static void beforeAllTests() throws Exception {
    TEST_UTIL.startMiniCluster(1);
  }

  @AfterClass
  public static void afterAllTests() throws Exception {
    TEST_UTIL.shutdownMiniCluster();
  }

  @Test
  public void testModifyTable() throws IOException {
    HBaseAdmin admin = TEST_UTIL.getHBaseAdmin();
    // Create a table with one family
    HTableDescriptor baseHtd = new HTableDescriptor(TABLE_NAME);
    baseHtd.addFamily(new HColumnDescriptor(FAMILY_0));
    admin.createTable(baseHtd);
    admin.disableTable(TABLE_NAME);
    try {
      // Verify the table descriptor
      verifyTableDescriptor(TABLE_NAME, FAMILY_0);

      // Modify the table adding another family and verify the descriptor
      HTableDescriptor modifiedHtd = new HTableDescriptor(TABLE_NAME);
      modifiedHtd.addFamily(new HColumnDescriptor(FAMILY_0));
      modifiedHtd.addFamily(new HColumnDescriptor(FAMILY_1));
      admin.modifyTable(TABLE_NAME, modifiedHtd);
      verifyTableDescriptor(TABLE_NAME, FAMILY_0, FAMILY_1);
    } finally {
      admin.deleteTable(TABLE_NAME);
    }
  }

  @Test
  public void testAddColumn() throws IOException {
    HBaseAdmin admin = TEST_UTIL.getHBaseAdmin();
    // Create a table with two families
    HTableDescriptor baseHtd = new HTableDescriptor(TABLE_NAME);
    baseHtd.addFamily(new HColumnDescriptor(FAMILY_0));
    admin.createTable(baseHtd);
    admin.disableTable(TABLE_NAME);
    try {
      // Verify the table descriptor
      verifyTableDescriptor(TABLE_NAME, FAMILY_0);

      // Modify the table removing one family and verify the descriptor
      admin.addColumn(TABLE_NAME, new HColumnDescriptor(FAMILY_1));
      verifyTableDescriptor(TABLE_NAME, FAMILY_0, FAMILY_1);
    } finally {
      admin.deleteTable(TABLE_NAME);
    }
  }

  @Test
  public void testDeleteColumn() throws IOException {
    HBaseAdmin admin = TEST_UTIL.getHBaseAdmin();
    // Create a table with two families
    HTableDescriptor baseHtd = new HTableDescriptor(TABLE_NAME);
    baseHtd.addFamily(new HColumnDescriptor(FAMILY_0));
    baseHtd.addFamily(new HColumnDescriptor(FAMILY_1));
    admin.createTable(baseHtd);
    admin.disableTable(TABLE_NAME);
    try {
      // Verify the table descriptor
      verifyTableDescriptor(TABLE_NAME, FAMILY_0, FAMILY_1);

      // Modify the table removing one family and verify the descriptor
      admin.deleteColumn(TABLE_NAME, FAMILY_1);
      verifyTableDescriptor(TABLE_NAME, FAMILY_0);
    } finally {
      admin.deleteTable(TABLE_NAME);
    }
  }

  private void verifyTableDescriptor(final byte[] tableName, final byte[]... families)
      throws IOException {
    HBaseAdmin admin = TEST_UTIL.getHBaseAdmin();

    // Verify descriptor from master
    HTableDescriptor htd = admin.getTableDescriptor(tableName);
    verifyTableDescriptor(htd, tableName, families);

    // Verify descriptor from HDFS
    MasterFileSystem mfs = TEST_UTIL.getMiniHBaseCluster().getMaster().getMasterFileSystem();
    Path tableDir = HTableDescriptor.getTableDir(mfs.getRootDir(), tableName);
    htd = FSTableDescriptors.getTableDescriptor(mfs.getFileSystem(), tableDir);
    verifyTableDescriptor(htd, tableName, families);
  }

  private void verifyTableDescriptor(final HTableDescriptor htd,
      final byte[] tableName, final byte[]... families) {
    Set<byte[]> htdFamilies = htd.getFamiliesKeys();
    assertTrue(Bytes.equals(tableName, htd.getName()));
    assertEquals(families.length, htdFamilies.size());
    for (byte[] familyName: families) {
      assertTrue("Expected family " + Bytes.toString(familyName), htdFamilies.contains(familyName));
    }
  }
}