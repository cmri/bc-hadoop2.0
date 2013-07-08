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
package org.apache.hadoop.hbase.mapreduce;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.HBaseTestingUtility;
import org.apache.hadoop.hbase.HColumnDescriptor;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.HRegionInfo;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.MediumTests;
import org.apache.hadoop.hbase.mapreduce.HLogInputFormat.HLogRecordReader;
import org.apache.hadoop.hbase.regionserver.wal.HLog;
import org.apache.hadoop.hbase.regionserver.wal.WALEdit;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.JobContext;
import org.apache.hadoop.mapreduce.MapReduceTestUtil;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * JUnit tests for the HLogRecordReader
 */
@Category(MediumTests.class)
public class TestHLogRecordReader {
  private final static HBaseTestingUtility TEST_UTIL = new HBaseTestingUtility();
  private static Configuration conf;
  private static FileSystem fs;
  private static Path hbaseDir;
  private static final byte [] tableName = Bytes.toBytes(getName());
  private static final byte [] rowName = tableName;
  private static final HRegionInfo info = new HRegionInfo(tableName,
      Bytes.toBytes(""), Bytes.toBytes(""), false);
  private static final byte [] family = Bytes.toBytes("column");
  private static final byte [] value = Bytes.toBytes("value");
  private static HTableDescriptor htd;
  private static Path logDir;
  private static Path oldLogDir;

  private static String getName() {
    return "TestHLogRecordReader";
  }

  @Before
  public void setUp() throws Exception {
    FileStatus[] entries = fs.listStatus(hbaseDir);
    for (FileStatus dir : entries) {
      fs.delete(dir.getPath(), true);
    }

  }
  @BeforeClass
  public static void setUpBeforeClass() throws Exception {
    // Make block sizes small.
    conf = TEST_UTIL.getConfiguration();
    conf.setInt("dfs.blocksize", 1024 * 1024);
    conf.setInt("dfs.replication", 1);
    TEST_UTIL.startMiniDFSCluster(1);

    conf = TEST_UTIL.getConfiguration();
    fs = TEST_UTIL.getDFSCluster().getFileSystem();

    hbaseDir = TEST_UTIL.createRootDir();
    logDir = new Path(hbaseDir, HConstants.HREGION_LOGDIR_NAME);
    oldLogDir = new Path(hbaseDir, HConstants.HREGION_OLDLOGDIR_NAME);
    htd = new HTableDescriptor(tableName);
    htd.addFamily(new HColumnDescriptor(family));
  }

  @AfterClass
  public static void tearDownAfterClass() throws Exception {
    TEST_UTIL.shutdownMiniCluster();
  }

  /**
   * Test partial reads from the log based on passed time range
   * @throws Exception
   */
  @Test
  public void testPartialRead() throws Exception {
    HLog log = new HLog(fs, logDir, oldLogDir, conf);
    long ts = System.currentTimeMillis();
    WALEdit edit = new WALEdit();
    edit.add(new KeyValue(rowName, family, Bytes.toBytes("1"),
        ts, value));
    log.append(info, tableName, edit,
      ts, htd);
    edit = new WALEdit();
    edit.add(new KeyValue(rowName, family, Bytes.toBytes("2"),
        ts+1, value));
    log.append(info, tableName, edit,
        ts+1, htd);
    log.rollWriter();

    Thread.sleep(1);
    long ts1 = System.currentTimeMillis();

    edit = new WALEdit();
    edit.add(new KeyValue(rowName, family, Bytes.toBytes("3"),
        ts1+1, value));
    log.append(info, tableName, edit,
        ts1+1, htd);
    edit = new WALEdit();
    edit.add(new KeyValue(rowName, family, Bytes.toBytes("4"),
        ts1+2, value));
    log.append(info, tableName, edit,
        ts1+2, htd);
    log.close();

    HLogInputFormat input = new HLogInputFormat();
    Configuration jobConf = new Configuration(conf);
    jobConf.set("mapred.input.dir", logDir.toString());
    jobConf.setLong(HLogInputFormat.END_TIME_KEY, ts);

    // only 1st file is considered, and only its 1st entry is used
    List<InputSplit> splits = input.getSplits(MapreduceTestingShim.createJobContext(jobConf));
    assertEquals(1, splits.size());
    testSplit(splits.get(0), Bytes.toBytes("1"));

    jobConf.setLong(HLogInputFormat.START_TIME_KEY, ts+1);
    jobConf.setLong(HLogInputFormat.END_TIME_KEY, ts1+1);
    splits = input.getSplits(MapreduceTestingShim.createJobContext(jobConf));
    // both files need to be considered
    assertEquals(2, splits.size());
    // only the 2nd entry from the 1st file is used
    testSplit(splits.get(0), Bytes.toBytes("2"));
    // only the 1nd entry from the 2nd file is used
    testSplit(splits.get(1), Bytes.toBytes("3"));
  }

  /**
   * Test basic functionality
   * @throws Exception
   */
  @Test
  public void testHLogRecordReader() throws Exception {
    HLog log = new HLog(fs, logDir, oldLogDir, conf);
    byte [] value = Bytes.toBytes("value");
    WALEdit edit = new WALEdit();
    edit.add(new KeyValue(rowName, family, Bytes.toBytes("1"),
        System.currentTimeMillis(), value));
    log.append(info, tableName, edit,
      System.currentTimeMillis(), htd);

    Thread.sleep(1); // make sure 2nd log gets a later timestamp
    long secondTs = System.currentTimeMillis();
    log.rollWriter();

    edit = new WALEdit();
    edit.add(new KeyValue(rowName, family, Bytes.toBytes("2"),
        System.currentTimeMillis(), value));
    log.append(info, tableName, edit,
      System.currentTimeMillis(), htd);
    log.close();
    long thirdTs = System.currentTimeMillis();

    // should have 2 log files now
    HLogInputFormat input = new HLogInputFormat();
    Configuration jobConf = new Configuration(conf);
    jobConf.set("mapred.input.dir", logDir.toString());

    // make sure both logs are found
    List<InputSplit> splits = input.getSplits(MapreduceTestingShim.createJobContext(jobConf));
    assertEquals(2, splits.size());

    // should return exactly one KV
    testSplit(splits.get(0), Bytes.toBytes("1"));
    // same for the 2nd split
    testSplit(splits.get(1), Bytes.toBytes("2"));

    // now test basic time ranges:

    // set an endtime, the 2nd log file can be ignored completely.
    jobConf.setLong(HLogInputFormat.END_TIME_KEY, secondTs-1);
    splits = input.getSplits(MapreduceTestingShim.createJobContext(jobConf));
    assertEquals(1, splits.size());
    testSplit(splits.get(0), Bytes.toBytes("1"));

    // now set a start time
    jobConf.setLong(HLogInputFormat.END_TIME_KEY, Long.MAX_VALUE);
    jobConf.setLong(HLogInputFormat.START_TIME_KEY, thirdTs);
    splits = input.getSplits(MapreduceTestingShim.createJobContext(jobConf));
    // both logs need to be considered
    assertEquals(2, splits.size());
    // but both readers skip all edits
    testSplit(splits.get(0));
    testSplit(splits.get(1));
  }

  /**
   * Create a new reader from the split, and match the edits against the passed columns.
   */
  private void testSplit(InputSplit split, byte[]... columns) throws Exception {
    HLogRecordReader reader = new HLogRecordReader();
    reader.initialize(split, MapReduceTestUtil.createDummyMapTaskAttemptContext(conf));

    for (byte[] column : columns) {
      assertTrue(reader.nextKeyValue());
      assertTrue(Bytes
          .equals(column, reader.getCurrentValue().getKeyValues().get(0).getQualifier()));
    }
    assertFalse(reader.nextKeyValue());
    reader.close();
  }

  @org.junit.Rule
  public org.apache.hadoop.hbase.ResourceCheckerJUnitRule cu =
    new org.apache.hadoop.hbase.ResourceCheckerJUnitRule();
}
