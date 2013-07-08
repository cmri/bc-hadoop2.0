/**
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
package org.apache.hadoop.hbase.filter;

import static org.junit.Assert.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.hadoop.hbase.*;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.regionserver.HRegion;
import org.apache.hadoop.hbase.regionserver.InternalScanner;
import org.apache.hadoop.hbase.util.Bytes;
import org.junit.Test;
import org.junit.experimental.categories.Category;

@Category(SmallTests.class)
public class TestColumnPrefixFilter {

  private final static HBaseTestingUtility TEST_UTIL = new
      HBaseTestingUtility();

  @Test
  public void testColumnPrefixFilter() throws IOException {
    String family = "Family";
    HTableDescriptor htd = new HTableDescriptor("TestColumnPrefixFilter");
    htd.addFamily(new HColumnDescriptor(family));
    HRegionInfo info = new HRegionInfo(htd.getName(), null, null, false);
    HRegion region = HRegion.createHRegion(info, TEST_UTIL.
      getDataTestDir(), TEST_UTIL.getConfiguration(), htd);
    try {
      List<String> rows = generateRandomWords(100, "row");
      List<String> columns = generateRandomWords(10000, "column");
      long maxTimestamp = 2;

      List<KeyValue> kvList = new ArrayList<KeyValue>();

      Map<String, List<KeyValue>> prefixMap = new HashMap<String,
          List<KeyValue>>();

      prefixMap.put("p", new ArrayList<KeyValue>());
      prefixMap.put("s", new ArrayList<KeyValue>());

      String valueString = "ValueString";

      for (String row: rows) {
        Put p = new Put(Bytes.toBytes(row));
        p.setWriteToWAL(false);
        for (String column: columns) {
          for (long timestamp = 1; timestamp <= maxTimestamp; timestamp++) {
            KeyValue kv = KeyValueTestUtil.create(row, family, column, timestamp,
                valueString);
            p.add(kv);
            kvList.add(kv);
            for (String s: prefixMap.keySet()) {
              if (column.startsWith(s)) {
                prefixMap.get(s).add(kv);
              }
            }
          }
        }
        region.put(p);
      }

      ColumnPrefixFilter filter;
      Scan scan = new Scan();
      scan.setMaxVersions();
      for (String s: prefixMap.keySet()) {
        filter = new ColumnPrefixFilter(Bytes.toBytes(s));

        scan.setFilter(filter);

        InternalScanner scanner = region.getScanner(scan);
        List<KeyValue> results = new ArrayList<KeyValue>();
        while(scanner.next(results));
        assertEquals(prefixMap.get(s).size(), results.size());
      }
    } finally {
      region.close();
      region.getLog().closeAndDelete();
    }

    region.close();
    region.getLog().closeAndDelete();
  }

  @Test
  public void testColumnPrefixFilterWithFilterList() throws IOException {
    String family = "Family";
    HTableDescriptor htd = new HTableDescriptor("TestColumnPrefixFilter");
    htd.addFamily(new HColumnDescriptor(family));
    HRegionInfo info = new HRegionInfo(htd.getName(), null, null, false);
    HRegion region = HRegion.createHRegion(info, TEST_UTIL.
      getDataTestDir(), TEST_UTIL.getConfiguration(), htd);
    try {
      List<String> rows = generateRandomWords(100, "row");
      List<String> columns = generateRandomWords(10000, "column");
      long maxTimestamp = 2;

      List<KeyValue> kvList = new ArrayList<KeyValue>();

      Map<String, List<KeyValue>> prefixMap = new HashMap<String,
          List<KeyValue>>();

      prefixMap.put("p", new ArrayList<KeyValue>());
      prefixMap.put("s", new ArrayList<KeyValue>());

      String valueString = "ValueString";

      for (String row: rows) {
        Put p = new Put(Bytes.toBytes(row));
        p.setWriteToWAL(false);
        for (String column: columns) {
          for (long timestamp = 1; timestamp <= maxTimestamp; timestamp++) {
            KeyValue kv = KeyValueTestUtil.create(row, family, column, timestamp,
                valueString);
            p.add(kv);
            kvList.add(kv);
            for (String s: prefixMap.keySet()) {
              if (column.startsWith(s)) {
                prefixMap.get(s).add(kv);
              }
            }
          }
        }
        region.put(p);
      }

      ColumnPrefixFilter filter;
      Scan scan = new Scan();
      scan.setMaxVersions();
      for (String s: prefixMap.keySet()) {
        filter = new ColumnPrefixFilter(Bytes.toBytes(s));

        //this is how this test differs from the one above
        FilterList filterList = new FilterList(FilterList.Operator.MUST_PASS_ALL);
        filterList.addFilter(filter);
        scan.setFilter(filterList);

        InternalScanner scanner = region.getScanner(scan);
        List<KeyValue> results = new ArrayList<KeyValue>();
        while(scanner.next(results));
        assertEquals(prefixMap.get(s).size(), results.size());
      }
    } finally {
      region.close();
      region.getLog().closeAndDelete();
    }

    region.close();
    region.getLog().closeAndDelete();
  }

  List<String> generateRandomWords(int numberOfWords, String suffix) {
    Set<String> wordSet = new HashSet<String>();
    for (int i = 0; i < numberOfWords; i++) {
      int lengthOfWords = (int) (Math.random()*2) + 1;
      char[] wordChar = new char[lengthOfWords];
      for (int j = 0; j < wordChar.length; j++) {
        wordChar[j] = (char) (Math.random() * 26 + 97);
      }
      String word;
      if (suffix == null) {
        word = new String(wordChar);
      } else {
        word = new String(wordChar) + suffix;
      }
      wordSet.add(word);
    }
    List<String> wordList = new ArrayList<String>(wordSet);
    return wordList;
  }

  @org.junit.Rule
  public org.apache.hadoop.hbase.ResourceCheckerJUnitRule cu =
    new org.apache.hadoop.hbase.ResourceCheckerJUnitRule();
}

