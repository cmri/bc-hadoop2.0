/**
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
package org.apache.hadoop.hbase;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.Set;
import java.util.TreeSet;

import junit.framework.TestCase;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.hbase.KeyValue.KVComparator;
import org.apache.hadoop.hbase.KeyValue.MetaComparator;
import org.apache.hadoop.hbase.KeyValue.Type;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.io.WritableUtils;
import org.junit.experimental.categories.Category;

@Category(SmallTests.class)
public class TestKeyValue extends TestCase {
  private final Log LOG = LogFactory.getLog(this.getClass().getName());

  public void testColumnCompare() throws Exception {
    final byte [] a = Bytes.toBytes("aaa");
    byte [] family1 = Bytes.toBytes("abc");
    byte [] qualifier1 = Bytes.toBytes("def");
    byte [] family2 = Bytes.toBytes("abcd");
    byte [] qualifier2 = Bytes.toBytes("ef");

    KeyValue aaa = new KeyValue(a, family1, qualifier1, 0L, Type.Put, a);
    assertFalse(aaa.matchingColumn(family2, qualifier2));
    assertTrue(aaa.matchingColumn(family1, qualifier1));
    aaa = new KeyValue(a, family2, qualifier2, 0L, Type.Put, a);
    assertFalse(aaa.matchingColumn(family1, qualifier1));
    assertTrue(aaa.matchingColumn(family2,qualifier2));
    byte [] nullQualifier = new byte[0];
    aaa = new KeyValue(a, family1, nullQualifier, 0L, Type.Put, a);
    assertTrue(aaa.matchingColumn(family1,null));
    assertFalse(aaa.matchingColumn(family2,qualifier2));
  }

  /** 
   * Test a corner case when the family qualifier is a prefix of the
   *  column qualifier.
   */
  public void testColumnCompare_prefix() throws Exception {
    final byte [] a = Bytes.toBytes("aaa");
    byte [] family1 = Bytes.toBytes("abc");
    byte [] qualifier1 = Bytes.toBytes("def");
    byte [] family2 = Bytes.toBytes("ab");
    byte [] qualifier2 = Bytes.toBytes("def");

    KeyValue aaa = new KeyValue(a, family1, qualifier1, 0L, Type.Put, a);
    assertFalse(aaa.matchingColumn(family2, qualifier2));
  }

  public void testBasics() throws Exception {
    LOG.info("LOWKEY: " + KeyValue.LOWESTKEY.toString());
    check(Bytes.toBytes(getName()),
      Bytes.toBytes(getName()), Bytes.toBytes(getName()), 1,
      Bytes.toBytes(getName()));
    // Test empty value and empty column -- both should work. (not empty fam)
    check(Bytes.toBytes(getName()), Bytes.toBytes(getName()), null, 1, null);
    check(HConstants.EMPTY_BYTE_ARRAY, Bytes.toBytes(getName()), null, 1, null);
  }

  private void check(final byte [] row, final byte [] family, byte [] qualifier,
    final long timestamp, final byte [] value) {
    KeyValue kv = new KeyValue(row, family, qualifier, timestamp, value);
    assertTrue(Bytes.compareTo(kv.getRow(), row) == 0);
    assertTrue(kv.matchingColumn(family, qualifier));
    // Call toString to make sure it works.
    LOG.info(kv.toString());
  }

  public void testPlainCompare() throws Exception {
    final byte [] a = Bytes.toBytes("aaa");
    final byte [] b = Bytes.toBytes("bbb");
    final byte [] fam = Bytes.toBytes("col");
    final byte [] qf = Bytes.toBytes("umn");
//    final byte [] column = Bytes.toBytes("col:umn");
    KeyValue aaa = new KeyValue(a, fam, qf, a);
    KeyValue bbb = new KeyValue(b, fam, qf, b);
    byte [] keyabb = aaa.getKey();
    byte [] keybbb = bbb.getKey();
    assertTrue(KeyValue.COMPARATOR.compare(aaa, bbb) < 0);
    assertTrue(KeyValue.KEY_COMPARATOR.compare(keyabb, 0, keyabb.length, keybbb,
      0, keybbb.length) < 0);
    assertTrue(KeyValue.COMPARATOR.compare(bbb, aaa) > 0);
    assertTrue(KeyValue.KEY_COMPARATOR.compare(keybbb, 0, keybbb.length, keyabb,
      0, keyabb.length) > 0);
    // Compare breaks if passed same ByteBuffer as both left and right arguments.
    assertTrue(KeyValue.COMPARATOR.compare(bbb, bbb) == 0);
    assertTrue(KeyValue.KEY_COMPARATOR.compare(keybbb, 0, keybbb.length, keybbb,
      0, keybbb.length) == 0);
    assertTrue(KeyValue.COMPARATOR.compare(aaa, aaa) == 0);
    assertTrue(KeyValue.KEY_COMPARATOR.compare(keyabb, 0, keyabb.length, keyabb,
      0, keyabb.length) == 0);
    // Do compare with different timestamps.
    aaa = new KeyValue(a, fam, qf, 1, a);
    bbb = new KeyValue(a, fam, qf, 2, a);
    assertTrue(KeyValue.COMPARATOR.compare(aaa, bbb) > 0);
    assertTrue(KeyValue.COMPARATOR.compare(bbb, aaa) < 0);
    assertTrue(KeyValue.COMPARATOR.compare(aaa, aaa) == 0);
    // Do compare with different types.  Higher numbered types -- Delete
    // should sort ahead of lower numbers; i.e. Put
    aaa = new KeyValue(a, fam, qf, 1, KeyValue.Type.Delete, a);
    bbb = new KeyValue(a, fam, qf, 1, a);
    assertTrue(KeyValue.COMPARATOR.compare(aaa, bbb) < 0);
    assertTrue(KeyValue.COMPARATOR.compare(bbb, aaa) > 0);
    assertTrue(KeyValue.COMPARATOR.compare(aaa, aaa) == 0);
  }

  public void testMoreComparisons() throws Exception {
    // Root compares
    long now = System.currentTimeMillis();
    KeyValue a = new KeyValue(Bytes.toBytes(".META.,,99999999999999"), now);
    KeyValue b = new KeyValue(Bytes.toBytes(".META.,,1"), now);
    KVComparator c = new KeyValue.RootComparator();
    assertTrue(c.compare(b, a) < 0);
    KeyValue aa = new KeyValue(Bytes.toBytes(".META.,,1"), now);
    KeyValue bb = new KeyValue(Bytes.toBytes(".META.,,1"),
        Bytes.toBytes("info"), Bytes.toBytes("regioninfo"), 1235943454602L,
        (byte[])null);
    assertTrue(c.compare(aa, bb) < 0);

    // Meta compares
    KeyValue aaa = new KeyValue(
        Bytes.toBytes("TestScanMultipleVersions,row_0500,1236020145502"), now);
    KeyValue bbb = new KeyValue(
        Bytes.toBytes("TestScanMultipleVersions,,99999999999999"), now);
    c = new KeyValue.MetaComparator();
    assertTrue(c.compare(bbb, aaa) < 0);

    KeyValue aaaa = new KeyValue(Bytes.toBytes("TestScanMultipleVersions,,1236023996656"),
        Bytes.toBytes("info"), Bytes.toBytes("regioninfo"), 1236024396271L,
        (byte[])null);
    assertTrue(c.compare(aaaa, bbb) < 0);

    KeyValue x = new KeyValue(Bytes.toBytes("TestScanMultipleVersions,row_0500,1236034574162"),
        Bytes.toBytes("info"), Bytes.toBytes(""), 9223372036854775807L,
        (byte[])null);
    KeyValue y = new KeyValue(Bytes.toBytes("TestScanMultipleVersions,row_0500,1236034574162"),
        Bytes.toBytes("info"), Bytes.toBytes("regioninfo"), 1236034574912L,
        (byte[])null);
    assertTrue(c.compare(x, y) < 0);
    comparisons(new KeyValue.MetaComparator());
    comparisons(new KeyValue.KVComparator());
    metacomparisons(new KeyValue.RootComparator());
    metacomparisons(new KeyValue.MetaComparator());
  }

  public void testBadMetaCompareSingleDelim() {
    MetaComparator c = new KeyValue.MetaComparator();
    long now = System.currentTimeMillis();
    // meta keys values are not quite right.  A users can enter illegal values 
    // from shell when scanning meta.
    KeyValue a = new KeyValue(Bytes.toBytes("table,a1"), now);
    KeyValue b = new KeyValue(Bytes.toBytes("table,a2"), now);
    try {
      c.compare(a, b);
    } catch (IllegalArgumentException iae) { 
      assertEquals(".META. key must have two ',' delimiters and have the following" +
      		" format: '<table>,<key>,<etc>'", iae.getMessage());
      return;
    }
    fail("Expected IllegalArgumentException");
  }

  public void testMetaComparatorTableKeysWithCommaOk() {
    MetaComparator c = new KeyValue.MetaComparator();
    long now = System.currentTimeMillis();
    // meta keys values are not quite right.  A users can enter illegal values 
    // from shell when scanning meta.
    KeyValue a = new KeyValue(Bytes.toBytes("table,key,with,commas1,1234"), now);
    KeyValue b = new KeyValue(Bytes.toBytes("table,key,with,commas2,0123"), now);
    assertTrue(c.compare(a, b) < 0);
  }
  
  /**
   * Tests cases where rows keys have characters below the ','.
   * See HBASE-832
   * @throws IOException
   */
  public void testKeyValueBorderCases() throws IOException {
    // % sorts before , so if we don't do special comparator, rowB would
    // come before rowA.
    KeyValue rowA = new KeyValue(Bytes.toBytes("testtable,www.hbase.org/,1234"),
      Bytes.toBytes("fam"), Bytes.toBytes(""), Long.MAX_VALUE, (byte[])null);
    KeyValue rowB = new KeyValue(Bytes.toBytes("testtable,www.hbase.org/%20,99999"),
        Bytes.toBytes("fam"), Bytes.toBytes(""), Long.MAX_VALUE, (byte[])null);
    assertTrue(KeyValue.META_COMPARATOR.compare(rowA, rowB) < 0);

    rowA = new KeyValue(Bytes.toBytes("testtable,,1234"), Bytes.toBytes("fam"),
        Bytes.toBytes(""), Long.MAX_VALUE, (byte[])null);
    rowB = new KeyValue(Bytes.toBytes("testtable,$www.hbase.org/,99999"),
        Bytes.toBytes("fam"), Bytes.toBytes(""), Long.MAX_VALUE, (byte[])null);
    assertTrue(KeyValue.META_COMPARATOR.compare(rowA, rowB) < 0);

    rowA = new KeyValue(Bytes.toBytes(".META.,testtable,www.hbase.org/,1234,4321"),
        Bytes.toBytes("fam"), Bytes.toBytes(""), Long.MAX_VALUE, (byte[])null);
    rowB = new KeyValue(Bytes.toBytes(".META.,testtable,www.hbase.org/%20,99999,99999"),
        Bytes.toBytes("fam"), Bytes.toBytes(""), Long.MAX_VALUE, (byte[])null);
    assertTrue(KeyValue.ROOT_COMPARATOR.compare(rowA, rowB) < 0);
  }

  private void metacomparisons(final KeyValue.MetaComparator c) {
    long now = System.currentTimeMillis();
    assertTrue(c.compare(new KeyValue(Bytes.toBytes(".META.,a,,0,1"), now),
      new KeyValue(Bytes.toBytes(".META.,a,,0,1"), now)) == 0);
    KeyValue a = new KeyValue(Bytes.toBytes(".META.,a,,0,1"), now);
    KeyValue b = new KeyValue(Bytes.toBytes(".META.,a,,0,2"), now);
    assertTrue(c.compare(a, b) < 0);
    assertTrue(c.compare(new KeyValue(Bytes.toBytes(".META.,a,,0,2"), now),
      new KeyValue(Bytes.toBytes(".META.,a,,0,1"), now)) > 0);
  }

  private void comparisons(final KeyValue.KVComparator c) {
    long now = System.currentTimeMillis();
    assertTrue(c.compare(new KeyValue(Bytes.toBytes(".META.,,1"), now),
      new KeyValue(Bytes.toBytes(".META.,,1"), now)) == 0);
    assertTrue(c.compare(new KeyValue(Bytes.toBytes(".META.,,1"), now),
      new KeyValue(Bytes.toBytes(".META.,,2"), now)) < 0);
    assertTrue(c.compare(new KeyValue(Bytes.toBytes(".META.,,2"), now),
      new KeyValue(Bytes.toBytes(".META.,,1"), now)) > 0);
  }

  public void testBinaryKeys() throws Exception {
    Set<KeyValue> set = new TreeSet<KeyValue>(KeyValue.COMPARATOR);
    final byte [] fam = Bytes.toBytes("col");
    final byte [] qf = Bytes.toBytes("umn");
    final byte [] nb = new byte[0];
    KeyValue [] keys = {new KeyValue(Bytes.toBytes("aaaaa,\u0000\u0000,2"), fam, qf, 2, nb),
      new KeyValue(Bytes.toBytes("aaaaa,\u0001,3"), fam, qf, 3, nb),
      new KeyValue(Bytes.toBytes("aaaaa,,1"), fam, qf, 1, nb),
      new KeyValue(Bytes.toBytes("aaaaa,\u1000,5"), fam, qf, 5, nb),
      new KeyValue(Bytes.toBytes("aaaaa,a,4"), fam, qf, 4, nb),
      new KeyValue(Bytes.toBytes("a,a,0"), fam, qf, 0, nb),
    };
    // Add to set with bad comparator
    for (int i = 0; i < keys.length; i++) {
      set.add(keys[i]);
    }
    // This will output the keys incorrectly.
    boolean assertion = false;
    int count = 0;
    try {
      for (KeyValue k: set) {
        assertTrue(count++ == k.getTimestamp());
      }
    } catch (junit.framework.AssertionFailedError e) {
      // Expected
      assertion = true;
    }
    assertTrue(assertion);
    // Make set with good comparator
    set = new TreeSet<KeyValue>(new KeyValue.MetaComparator());
    for (int i = 0; i < keys.length; i++) {
      set.add(keys[i]);
    }
    count = 0;
    for (KeyValue k: set) {
      assertTrue(count++ == k.getTimestamp());
    }
    // Make up -ROOT- table keys.
    KeyValue [] rootKeys = {
        new KeyValue(Bytes.toBytes(".META.,aaaaa,\u0000\u0000,0,2"), fam, qf, 2, nb),
        new KeyValue(Bytes.toBytes(".META.,aaaaa,\u0001,0,3"), fam, qf, 3, nb),
        new KeyValue(Bytes.toBytes(".META.,aaaaa,,0,1"), fam, qf, 1, nb),
        new KeyValue(Bytes.toBytes(".META.,aaaaa,\u1000,0,5"), fam, qf, 5, nb),
        new KeyValue(Bytes.toBytes(".META.,aaaaa,a,0,4"), fam, qf, 4, nb),
        new KeyValue(Bytes.toBytes(".META.,,0"), fam, qf, 0, nb),
      };
    // This will output the keys incorrectly.
    set = new TreeSet<KeyValue>(new KeyValue.MetaComparator());
    // Add to set with bad comparator
    for (int i = 0; i < keys.length; i++) {
      set.add(rootKeys[i]);
    }
    assertion = false;
    count = 0;
    try {
      for (KeyValue k: set) {
        assertTrue(count++ == k.getTimestamp());
      }
    } catch (junit.framework.AssertionFailedError e) {
      // Expected
      assertion = true;
    }
    // Now with right comparator
    set = new TreeSet<KeyValue>(new KeyValue.RootComparator());
    // Add to set with bad comparator
    for (int i = 0; i < keys.length; i++) {
      set.add(rootKeys[i]);
    }
    count = 0;
    for (KeyValue k: set) {
      assertTrue(count++ == k.getTimestamp());
    }
  }

  public void testStackedUpKeyValue() {
    // Test multiple KeyValues in a single blob.

    // TODO actually write this test!

  }

  private final byte[] rowA = Bytes.toBytes("rowA");
  private final byte[] rowB = Bytes.toBytes("rowB");

  private final byte[] family = Bytes.toBytes("family");
  private final byte[] qualA = Bytes.toBytes("qfA");

  private void assertKVLess(KeyValue.KVComparator c,
                            KeyValue less,
                            KeyValue greater) {
    int cmp = c.compare(less,greater);
    assertTrue(cmp < 0);
    cmp = c.compare(greater,less);
    assertTrue(cmp > 0);
  }

  private void assertKVLessWithoutRow(KeyValue.KeyComparator c, int common, KeyValue less,
      KeyValue greater) {
    int cmp = c.compareIgnoringPrefix(common, less.getBuffer(), less.getOffset()
        + KeyValue.ROW_OFFSET, less.getKeyLength(), greater.getBuffer(),
        greater.getOffset() + KeyValue.ROW_OFFSET, greater.getKeyLength());
    assertTrue(cmp < 0);
    cmp = c.compareIgnoringPrefix(common, greater.getBuffer(), greater.getOffset()
        + KeyValue.ROW_OFFSET, greater.getKeyLength(), less.getBuffer(),
        less.getOffset() + KeyValue.ROW_OFFSET, less.getKeyLength());
    assertTrue(cmp > 0);
  }

  public void testCompareWithoutRow() {
    final KeyValue.KeyComparator c = KeyValue.KEY_COMPARATOR;
    byte[] row = Bytes.toBytes("row");

    byte[] fa = Bytes.toBytes("fa");
    byte[] fami = Bytes.toBytes("fami");
    byte[] fami1 = Bytes.toBytes("fami1");

    byte[] qual0 = Bytes.toBytes("");
    byte[] qual1 = Bytes.toBytes("qf1");
    byte[] qual2 = Bytes.toBytes("qf2");
    long ts = 1;

    // 'fa:'
    KeyValue kv_0 = new KeyValue(row, fa, qual0, ts, Type.Put);
    // 'fami:'
    KeyValue kv0_0 = new KeyValue(row, fami, qual0, ts, Type.Put);
    // 'fami:qf1'
    KeyValue kv0_1 = new KeyValue(row, fami, qual1, ts, Type.Put);
    // 'fami:qf2'
    KeyValue kv0_2 = new KeyValue(row, fami, qual2, ts, Type.Put);
    // 'fami1:'
    KeyValue kv1_0 = new KeyValue(row, fami1, qual0, ts, Type.Put);

    // 'fami:qf1' < 'fami:qf2'
    assertKVLessWithoutRow(c, 0, kv0_1, kv0_2);
    // 'fami:qf1' < 'fami1:'
    assertKVLessWithoutRow(c, 0, kv0_1, kv1_0);

    // Test comparison by skipping the same prefix bytes.
    /***
     * KeyValue Format and commonLength:
     * |_keyLen_|_valLen_|_rowLen_|_rowKey_|_famiLen_|_fami_|_Quali_|....
     * ------------------|-------commonLength--------|--------------
     */
    int commonLength = KeyValue.ROW_LENGTH_SIZE + KeyValue.FAMILY_LENGTH_SIZE
        + row.length;
    // 'fa:' < 'fami:'. They have commonPrefix + 2 same prefix bytes.
    assertKVLessWithoutRow(c, commonLength + 2, kv_0, kv0_0);
    // 'fami:' < 'fami:qf1'. They have commonPrefix + 4 same prefix bytes.
    assertKVLessWithoutRow(c, commonLength + 4, kv0_0, kv0_1);
    // 'fami:qf1' < 'fami1:'. They have commonPrefix + 4 same prefix bytes.
    assertKVLessWithoutRow(c, commonLength + 4, kv0_1, kv1_0);
    // 'fami:qf1' < 'fami:qf2'. They have commonPrefix + 6 same prefix bytes.
    assertKVLessWithoutRow(c, commonLength + 6, kv0_1, kv0_2);
  }

  public void testFirstLastOnRow() {
    final KVComparator c = KeyValue.COMPARATOR;
    long ts = 1;

    // These are listed in sort order (ie: every one should be less
    // than the one on the next line).
    final KeyValue firstOnRowA = KeyValue.createFirstOnRow(rowA);
    final KeyValue kvA_1 = new KeyValue(rowA, null, null, ts, Type.Put);
    final KeyValue kvA_2 = new KeyValue(rowA, family, qualA, ts, Type.Put);
        
    final KeyValue lastOnRowA = KeyValue.createLastOnRow(rowA);
    final KeyValue firstOnRowB = KeyValue.createFirstOnRow(rowB);
    final KeyValue kvB = new KeyValue(rowB, family, qualA, ts, Type.Put);

    assertKVLess(c, firstOnRowA, firstOnRowB);
    assertKVLess(c, firstOnRowA, kvA_1);
    assertKVLess(c, firstOnRowA, kvA_2);
    assertKVLess(c, kvA_1, kvA_2);
    assertKVLess(c, kvA_2, firstOnRowB);
    assertKVLess(c, kvA_1, firstOnRowB);

    assertKVLess(c, lastOnRowA, firstOnRowB);
    assertKVLess(c, firstOnRowB, kvB);
    assertKVLess(c, lastOnRowA, kvB);

    assertKVLess(c, kvA_2, lastOnRowA);
    assertKVLess(c, kvA_1, lastOnRowA);
    assertKVLess(c, firstOnRowA, lastOnRowA);
  }

  public void testCreateKeyOnly() throws Exception {
    long ts = 1;
    byte [] value = Bytes.toBytes("a real value");
    byte [] evalue = new byte[0]; // empty value

    for (byte[] val : new byte[][]{value, evalue}) {
      for (boolean useLen : new boolean[]{false,true}) {
        KeyValue kv1 = new KeyValue(rowA, family, qualA, ts, val);
        KeyValue kv1ko = kv1.createKeyOnly(useLen);
        // keys are still the same
        assertTrue(kv1.equals(kv1ko));
        // but values are not
        assertTrue(kv1ko.getValue().length == (useLen?Bytes.SIZEOF_INT:0));
        if (useLen) {
          assertEquals(kv1.getValueLength(), Bytes.toInt(kv1ko.getValue()));
        }
      }
    }
  }

  public void testCreateKeyValueFromKey() {
    KeyValue kv = new KeyValue(Bytes.toBytes("myRow"), Bytes.toBytes("myCF"),
        Bytes.toBytes("myQualifier"), 12345L, Bytes.toBytes("myValue"));
    int initialPadding = 10;
    int endingPadding = 20;
    int keyLen = kv.getKeyLength();
    byte[] tmpArr = new byte[initialPadding + endingPadding + keyLen];
    System.arraycopy(kv.getBuffer(), kv.getKeyOffset(), tmpArr,
        initialPadding, keyLen);
    KeyValue kvFromKey = KeyValue.createKeyValueFromKey(tmpArr, initialPadding,
        keyLen);
    assertEquals(keyLen, kvFromKey.getKeyLength());
    assertEquals(KeyValue.ROW_OFFSET + keyLen, kvFromKey.getBuffer().length);
    System.err.println("kv=" + kv);
    System.err.println("kvFromKey=" + kvFromKey);
    assertEquals(kvFromKey.toString(),
        kv.toString().replaceAll("=[0-9]+", "=0"));
  }

  /**
   * The row cache is cleared and re-read for the new value
   *
   * @throws IOException
   */
  public void testReadFields() throws IOException {
    KeyValue kv1 = new KeyValue(Bytes.toBytes("row1"), Bytes.toBytes("cf1"),
        Bytes.toBytes("qualifier1"), 12345L, Bytes.toBytes("value1"));
    kv1.getRow(); // set row cache of kv1
    KeyValue kv2 = new KeyValue(Bytes.toBytes("row2"), Bytes.toBytes("cf2"),
        Bytes.toBytes("qualifier2"), 12345L, Bytes.toBytes("value2"));
    kv1.readFields(new DataInputStream(new ByteArrayInputStream(WritableUtils
        .toByteArray(kv2))));
    // check equality
    assertEquals(kv1, kv2);
    // check cache state (getRow() return the cached value if the cache is set)
    assertTrue(Bytes.equals(kv1.getRow(), kv2.getRow()));
  }

  /**
   * Tests that getTimestamp() does always return the proper timestamp, even after updating it.
   * See HBASE-6265.
   */
  public void testGetTimestamp() {
    KeyValue kv = new KeyValue(Bytes.toBytes("myRow"), Bytes.toBytes("myCF"),
      Bytes.toBytes("myQualifier"), HConstants.LATEST_TIMESTAMP,
      Bytes.toBytes("myValue"));
    long time1 = kv.getTimestamp();
    kv.updateLatestStamp(Bytes.toBytes(12345L));
    long time2 = kv.getTimestamp();
    assertEquals(HConstants.LATEST_TIMESTAMP, time1);
    assertEquals(12345L, time2);
  }

  @org.junit.Rule
  public org.apache.hadoop.hbase.ResourceCheckerJUnitRule cu =
    new org.apache.hadoop.hbase.ResourceCheckerJUnitRule();
}

