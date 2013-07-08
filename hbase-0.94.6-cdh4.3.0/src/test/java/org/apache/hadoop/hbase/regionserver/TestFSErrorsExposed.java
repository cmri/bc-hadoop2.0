/*
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

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.SoftReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.FilterFileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.PositionedReadable;
import org.apache.hadoop.hbase.*;
import org.apache.hadoop.hbase.client.HBaseAdmin;
import org.apache.hadoop.hbase.client.HTable;
import org.apache.hadoop.hbase.fs.HFileSystem;
import org.apache.hadoop.hbase.io.hfile.CacheConfig;
import org.apache.hadoop.hbase.io.hfile.HFileScanner;
import org.apache.hadoop.hbase.io.hfile.NoOpDataBlockEncoder;
import org.apache.hadoop.hbase.regionserver.StoreFile.BloomType;
import org.apache.hadoop.hbase.util.Bytes;
import org.junit.Test;
import org.junit.experimental.categories.Category;


/**
 * Test cases that ensure that file system level errors are bubbled up
 * appropriately to clients, rather than swallowed.
 */
@Category(MediumTests.class)
public class TestFSErrorsExposed {
  private static final Log LOG = LogFactory.getLog(TestFSErrorsExposed.class);

  HBaseTestingUtility util = new HBaseTestingUtility();

  /**
   * Injects errors into the pread calls of an on-disk file, and makes
   * sure those bubble up to the HFile scanner
   */
  @Test
  public void testHFileScannerThrowsErrors() throws IOException {
    Path hfilePath = new Path(new Path(
        util.getDataTestDir("internalScannerExposesErrors"),
        "regionname"), "familyname");
    HFileSystem hfs = (HFileSystem)util.getTestFileSystem();
    FaultyFileSystem faultyfs = new FaultyFileSystem(hfs.getBackingFs());
    FileSystem fs = new HFileSystem(faultyfs);
    CacheConfig cacheConf = new CacheConfig(util.getConfiguration());
    StoreFile.Writer writer = new StoreFile.WriterBuilder(
        util.getConfiguration(), cacheConf, hfs, 2*1024)
            .withOutputDir(hfilePath)
            .build();
    TestStoreFile.writeStoreFile(
        writer, Bytes.toBytes("cf"), Bytes.toBytes("qual"));

    StoreFile sf = new StoreFile(fs, writer.getPath(),
        util.getConfiguration(), cacheConf, StoreFile.BloomType.NONE,
        NoOpDataBlockEncoder.INSTANCE);

    StoreFile.Reader reader = sf.createReader();
    HFileScanner scanner = reader.getScanner(false, true);

    FaultyInputStream inStream = faultyfs.inStreams.get(0).get();
    assertNotNull(inStream);

    scanner.seekTo();
    // Do at least one successful read
    assertTrue(scanner.next());

    faultyfs.startFaults();

    try {
      int scanned=0;
      while (scanner.next()) {
        scanned++;
      }
      fail("Scanner didn't throw after faults injected");
    } catch (IOException ioe) {
      LOG.info("Got expected exception", ioe);
      assertTrue(ioe.getMessage().contains("Fault"));
    }
    reader.close(true); // end of test so evictOnClose
  }

  /**
   * Injects errors into the pread calls of an on-disk file, and makes
   * sure those bubble up to the StoreFileScanner
   */
  @Test
  public void testStoreFileScannerThrowsErrors() throws IOException {
    Path hfilePath = new Path(new Path(
        util.getDataTestDir("internalScannerExposesErrors"),
        "regionname"), "familyname");
    HFileSystem hfs = (HFileSystem)util.getTestFileSystem();
    FaultyFileSystem faultyfs = new FaultyFileSystem(hfs.getBackingFs());
    HFileSystem fs = new HFileSystem(faultyfs);
    CacheConfig cacheConf = new CacheConfig(util.getConfiguration());
    StoreFile.Writer writer = new StoreFile.WriterBuilder(
        util.getConfiguration(), cacheConf, hfs, 2 * 1024)
            .withOutputDir(hfilePath)
            .build();
    TestStoreFile.writeStoreFile(
        writer, Bytes.toBytes("cf"), Bytes.toBytes("qual"));

    StoreFile sf = new StoreFile(fs, writer.getPath(), util.getConfiguration(),
        cacheConf, BloomType.NONE, NoOpDataBlockEncoder.INSTANCE);

    List<StoreFileScanner> scanners = StoreFileScanner.getScannersForStoreFiles(
        Collections.singletonList(sf), false, true, false);
    KeyValueScanner scanner = scanners.get(0);

    FaultyInputStream inStream = faultyfs.inStreams.get(0).get();
    assertNotNull(inStream);

    scanner.seek(KeyValue.LOWESTKEY);
    // Do at least one successful read
    assertNotNull(scanner.next());
    faultyfs.startFaults();

    try {
      int scanned=0;
      while (scanner.next() != null) {
        scanned++;
      }
      fail("Scanner didn't throw after faults injected");
    } catch (IOException ioe) {
      LOG.info("Got expected exception", ioe);
      assertTrue(ioe.getMessage().contains("Could not iterate"));
    }
    scanner.close();
  }

  /**
   * Cluster test which starts a region server with a region, then
   * removes the data from HDFS underneath it, and ensures that
   * errors are bubbled to the client.
   */
  @Test
  public void testFullSystemBubblesFSErrors() throws Exception {
    try {
      // We set it not to run or it will trigger server shutdown while sync'ing
      // because all the datanodes are bad
      util.getConfiguration().setInt(
          "hbase.regionserver.optionallogflushinterval", Integer.MAX_VALUE);
      util.startMiniCluster(1);
      byte[] tableName = Bytes.toBytes("table");
      byte[] fam = Bytes.toBytes("fam");

      HBaseAdmin admin = new HBaseAdmin(util.getConfiguration());
      HTableDescriptor desc = new HTableDescriptor(tableName);
      desc.addFamily(new HColumnDescriptor(fam)
          .setMaxVersions(1)
          .setBlockCacheEnabled(false)
      );
      admin.createTable(desc);
      // Make it fail faster.
      util.getConfiguration().setInt("hbase.client.retries.number", 1);
      // Make a new Configuration so it makes a new connection that has the
      // above configuration on it; else we use the old one w/ 10 as default.
      HTable table = new HTable(new Configuration(util.getConfiguration()), tableName);

      // Load some data
      util.loadTable(table, fam);
      table.flushCommits();
      util.flush();
      util.countRows(table);

      // Kill the DFS cluster
      util.getDFSCluster().shutdownDataNodes();

      try {
        util.countRows(table);
        fail("Did not fail to count after removing data");
      } catch (Exception e) {
        LOG.info("Got expected error", e);
        assertTrue(e.getMessage().contains("Could not seek"));
      }

    } finally {
      util.shutdownMiniCluster();
    }
  }

  static class FaultyFileSystem extends FilterFileSystem {
    List<SoftReference<FaultyInputStream>> inStreams =
      new ArrayList<SoftReference<FaultyInputStream>>();

    public FaultyFileSystem(FileSystem testFileSystem) {
      super(testFileSystem);
    }

    @Override
    public FSDataInputStream open(Path p, int bufferSize) throws IOException  {
      FSDataInputStream orig = fs.open(p, bufferSize);
      FaultyInputStream faulty = new FaultyInputStream(orig);
      inStreams.add(new SoftReference<FaultyInputStream>(faulty));
      return faulty;
    }

    /**
     * Starts to simulate faults on all streams opened so far
     */
    public void startFaults() {
      for (SoftReference<FaultyInputStream> is: inStreams) {
        is.get().startFaults();
      }
    } 
  }

  static class FaultyInputStream extends FSDataInputStream {
    boolean faultsStarted = false;

    public FaultyInputStream(InputStream in) throws IOException {
      super(in);
    }

    public void startFaults() {
      faultsStarted = true;
    }

    public int read(long position, byte[] buffer, int offset, int length)
      throws IOException {
      injectFault();
      return ((PositionedReadable)in).read(position, buffer, offset, length);
    }

    private void injectFault() throws IOException {
      if (faultsStarted) {
        throw new IOException("Fault injected");
      }
    }
  }



  @org.junit.Rule
  public org.apache.hadoop.hbase.ResourceCheckerJUnitRule cu =
    new org.apache.hadoop.hbase.ResourceCheckerJUnitRule();
}

