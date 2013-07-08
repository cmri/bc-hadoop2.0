/**
 * Copyright 2007 The Apache Software Foundation
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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;

import org.apache.hadoop.hbase.*;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.FSTableDescriptors;
import org.apache.hadoop.hbase.util.FSUtils;
import org.apache.hadoop.hbase.util.MD5Hash;
import org.junit.Test;
import org.junit.experimental.categories.Category;

@Category(SmallTests.class)
public class TestHRegionInfo {
  @Test
  public void testCreateHRegionInfoName() throws Exception {
    String tableName = "tablename";
    final byte [] tn = Bytes.toBytes(tableName);
    String startKey = "startkey";
    final byte [] sk = Bytes.toBytes(startKey);
    String id = "id";

    // old format region name
    byte [] name = HRegionInfo.createRegionName(tn, sk, id, false);
    String nameStr = Bytes.toString(name);
    assertEquals(tableName + "," + startKey + "," + id, nameStr);


    // new format region name.
    String md5HashInHex = MD5Hash.getMD5AsHex(name);
    assertEquals(HRegionInfo.MD5_HEX_LENGTH, md5HashInHex.length());
    name = HRegionInfo.createRegionName(tn, sk, id, true);
    nameStr = Bytes.toString(name);
    assertEquals(tableName + "," + startKey + ","
                 + id + "." + md5HashInHex + ".",
                 nameStr);
  }
  
  @Test
  public void testGetSetOfHTD() throws IOException {
    HBaseTestingUtility HTU = new HBaseTestingUtility();
        final String tablename = "testGetSetOfHTD";

    // Delete the temporary table directory that might still be there from the
    // previous test run.
    FSTableDescriptors.deleteTableDescriptorIfExists(tablename,
        HTU.getConfiguration());

    HTableDescriptor htd = new HTableDescriptor(tablename);
    FSTableDescriptors.createTableDescriptor(htd, HTU.getConfiguration());
    HRegionInfo hri = new HRegionInfo(Bytes.toBytes("testGetSetOfHTD"),
        HConstants.EMPTY_START_ROW, HConstants.EMPTY_END_ROW);
    HTableDescriptor htd2 = hri.getTableDesc();
    assertTrue(htd.equals(htd2));
    final String key = "SOME_KEY";
    assertNull(htd.getValue(key));
    final String value = "VALUE";
    htd.setValue(key, value);
    hri.setTableDesc(htd);
    HTableDescriptor htd3 = hri.getTableDesc();
    assertTrue(htd.equals(htd3));
  }
  
  @Test
  public void testContainsRange() {
    HTableDescriptor tableDesc = new HTableDescriptor("testtable");
    HRegionInfo hri = new HRegionInfo(
        tableDesc.getName(), Bytes.toBytes("a"), Bytes.toBytes("g"));
    // Single row range at start of region
    assertTrue(hri.containsRange(Bytes.toBytes("a"), Bytes.toBytes("a")));
    // Fully contained range
    assertTrue(hri.containsRange(Bytes.toBytes("b"), Bytes.toBytes("c")));
    // Range overlapping start of region
    assertTrue(hri.containsRange(Bytes.toBytes("a"), Bytes.toBytes("c")));
    // Fully contained single-row range
    assertTrue(hri.containsRange(Bytes.toBytes("c"), Bytes.toBytes("c")));
    // Range that overlaps end key and hence doesn't fit
    assertFalse(hri.containsRange(Bytes.toBytes("a"), Bytes.toBytes("g")));
    // Single row range on end key
    assertFalse(hri.containsRange(Bytes.toBytes("g"), Bytes.toBytes("g")));
    // Single row range entirely outside
    assertFalse(hri.containsRange(Bytes.toBytes("z"), Bytes.toBytes("z")));
    
    // Degenerate range
    try {
      hri.containsRange(Bytes.toBytes("z"), Bytes.toBytes("a"));
      fail("Invalid range did not throw IAE");
    } catch (IllegalArgumentException iae) {
    }
  }

  @Test
  public void testLastRegionCompare() {
    HTableDescriptor tableDesc = new HTableDescriptor("testtable");
    HRegionInfo hrip = new HRegionInfo(
        tableDesc.getName(), Bytes.toBytes("a"), new byte[0]);
    HRegionInfo hric = new HRegionInfo(
        tableDesc.getName(), Bytes.toBytes("a"), Bytes.toBytes("b"));
    assertTrue(hrip.compareTo(hric) > 0);
  }

  @Test
  public void testMetaTables() {
    assertTrue(HRegionInfo.ROOT_REGIONINFO.isMetaTable());
    assertTrue(HRegionInfo.FIRST_META_REGIONINFO.isMetaTable());
  }

  @Test
  public void testComparator() {
    byte[] tablename = Bytes.toBytes("comparatorTablename");
    byte[] empty = new byte[0];
    HRegionInfo older = new HRegionInfo(tablename, empty, empty, false, 0L); 
    HRegionInfo newer = new HRegionInfo(tablename, empty, empty, false, 1L); 
    assertTrue(older.compareTo(newer) < 0);
    assertTrue(newer.compareTo(older) > 0);
    assertTrue(older.compareTo(older) == 0);
    assertTrue(newer.compareTo(newer) == 0);
  }
  
  @org.junit.Rule
  public org.apache.hadoop.hbase.ResourceCheckerJUnitRule cu =
    new org.apache.hadoop.hbase.ResourceCheckerJUnitRule();
}

