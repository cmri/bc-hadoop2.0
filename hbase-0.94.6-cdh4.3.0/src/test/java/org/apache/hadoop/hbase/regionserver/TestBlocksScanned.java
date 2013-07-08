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
package org.apache.hadoop.hbase.regionserver;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.hadoop.hbase.HBaseTestCase;
import org.apache.hadoop.hbase.HBaseTestingUtility;
import org.apache.hadoop.hbase.HColumnDescriptor;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.SmallTests;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.io.hfile.Compression;
import org.apache.hadoop.hbase.io.hfile.BlockType.BlockCategory;
import org.apache.hadoop.hbase.regionserver.metrics.SchemaMetrics;
import org.apache.hadoop.hbase.regionserver.metrics.SchemaMetrics.BlockMetricType;
import org.apache.hadoop.hbase.util.Bytes;
import org.junit.Assert;
import org.junit.Test;
import org.junit.experimental.categories.Category;

@SuppressWarnings("deprecation")
@Category(SmallTests.class)
public class TestBlocksScanned extends HBaseTestCase {
  private static byte [] TABLE = Bytes.toBytes("TestBlocksScanned");
  private static byte [] FAMILY = Bytes.toBytes("family");
  private static byte [] COL = Bytes.toBytes("col");
  private static byte [] START_KEY = Bytes.toBytes("aaa");
  private static byte [] END_KEY = Bytes.toBytes("zzz");
  private static int BLOCK_SIZE = 70;

  private static HBaseTestingUtility TEST_UTIL = null;
  private static HTableDescriptor TESTTABLEDESC = null;

   @Override
   public void setUp() throws Exception {
     super.setUp();
     SchemaMetrics.setUseTableNameInTest(true);
     TEST_UTIL = new HBaseTestingUtility();
     TESTTABLEDESC = new HTableDescriptor(TABLE);

     TESTTABLEDESC.addFamily(
         new HColumnDescriptor(FAMILY)
         .setMaxVersions(10)
         .setBlockCacheEnabled(true)
         .setBlocksize(BLOCK_SIZE)
         .setCompressionType(Compression.Algorithm.NONE)
     );
   }

   @Test
  public void testBlocksScanned() throws Exception {
    HRegion r = createNewHRegion(TESTTABLEDESC, START_KEY, END_KEY,
        TEST_UTIL.getConfiguration());
    addContent(r, FAMILY, COL);
    r.flushcache();

    // Get the per-cf metrics
    SchemaMetrics schemaMetrics =
      SchemaMetrics.getInstance(Bytes.toString(TABLE), Bytes.toString(FAMILY));
    Map<String, Long> schemaMetricSnapshot = SchemaMetrics.getMetricsSnapshot();

    // Do simple test of getting one row only first.
    Scan scan = new Scan(Bytes.toBytes("aaa"), Bytes.toBytes("aaz"));
    scan.addColumn(FAMILY, COL);
    scan.setMaxVersions(1);

    InternalScanner s = r.getScanner(scan);
    List<KeyValue> results = new ArrayList<KeyValue>();
    while (s.next(results));
    s.close();

    int expectResultSize = 'z' - 'a';
    Assert.assertEquals(expectResultSize, results.size());

    int kvPerBlock = (int) Math.ceil(BLOCK_SIZE / (double) results.get(0).getLength());
    Assert.assertEquals(2, kvPerBlock);

    long expectDataBlockRead = (long) Math.ceil(expectResultSize / (double) kvPerBlock);
    long expectIndexBlockRead = expectDataBlockRead;

    verifyDataAndIndexBlockRead(schemaMetricSnapshot, schemaMetrics,
        expectDataBlockRead, expectIndexBlockRead);
  }

  private void verifyDataAndIndexBlockRead(Map<String, Long> previousMetricSnapshot,
      SchemaMetrics schemaMetrics, long expectDataBlockRead, long expectedIndexBlockRead){
    Map<String, Long> currentMetricsSnapshot = SchemaMetrics.getMetricsSnapshot();
    Map<String, Long> diffs =
      SchemaMetrics.diffMetrics(previousMetricSnapshot, currentMetricsSnapshot);

    long dataBlockRead = SchemaMetrics.getLong(diffs,
        schemaMetrics.getBlockMetricName(BlockCategory.DATA, false, BlockMetricType.READ_COUNT));
    long indexBlockRead = SchemaMetrics.getLong(diffs,
        schemaMetrics.getBlockMetricName(BlockCategory.INDEX, false, BlockMetricType.READ_COUNT));

    Assert.assertEquals(expectDataBlockRead, dataBlockRead);
    Assert.assertEquals(expectedIndexBlockRead, indexBlockRead);
  }
}
