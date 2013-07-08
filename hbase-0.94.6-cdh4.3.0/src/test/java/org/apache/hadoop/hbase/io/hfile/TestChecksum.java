/*
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
package org.apache.hadoop.hbase.io.hfile;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.DataOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.HBaseTestingUtility;
import org.apache.hadoop.hbase.MediumTests;
import org.apache.hadoop.hbase.fs.HFileSystem;
import org.apache.hadoop.hbase.io.hfile.Compression.Algorithm;
import org.apache.hadoop.hbase.util.ChecksumType;

import static org.apache.hadoop.hbase.io.hfile.Compression.Algorithm.*;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;

@Category(MediumTests.class)
public class TestChecksum {
  // change this value to activate more logs
  private static final boolean detailedLogging = true;
  private static final boolean[] BOOLEAN_VALUES = new boolean[] { false, true };

  private static final Log LOG = LogFactory.getLog(TestHFileBlock.class);

  static final Compression.Algorithm[] COMPRESSION_ALGORITHMS = {
      NONE, GZ };

  static final int[] BYTES_PER_CHECKSUM = {
      50, 500, 688, 16*1024, (16*1024+980), 64 * 1024};

  private static final HBaseTestingUtility TEST_UTIL =
    new HBaseTestingUtility();
  private FileSystem fs;
  private HFileSystem hfs;

  @Before
  public void setUp() throws Exception {
    fs = HFileSystem.get(TEST_UTIL.getConfiguration());
    hfs = (HFileSystem)fs;
  }

  /**
   * Introduce checksum failures and check that we can still read
   * the data
   */
  @Test
  public void testChecksumCorruption() throws IOException {
    for (Compression.Algorithm algo : COMPRESSION_ALGORITHMS) {
      for (boolean pread : new boolean[] { false, true }) {
        LOG.info("testChecksumCorruption: Compression algorithm: " + algo +
                   ", pread=" + pread);
        Path path = new Path(TEST_UTIL.getDataTestDir(), "blocks_v2_"
            + algo);
        FSDataOutputStream os = fs.create(path);
        HFileBlock.Writer hbw = new HFileBlock.Writer(algo, null,
            true, 1, HFile.DEFAULT_CHECKSUM_TYPE,
            HFile.DEFAULT_BYTES_PER_CHECKSUM);
        long totalSize = 0;
        for (int blockId = 0; blockId < 2; ++blockId) {
          DataOutputStream dos = hbw.startWriting(BlockType.DATA);
          for (int i = 0; i < 1234; ++i)
            dos.writeInt(i);
          hbw.writeHeaderAndData(os);
          totalSize += hbw.getOnDiskSizeWithHeader();
        }
        os.close();

        // Use hbase checksums. 
        assertEquals(true, hfs.useHBaseChecksum());

        // Do a read that purposely introduces checksum verification failures.
        FSDataInputStream is = fs.open(path);
        HFileBlock.FSReader hbr = new FSReaderV2Test(is, algo,
            totalSize, HFile.MAX_FORMAT_VERSION, fs, path);
        HFileBlock b = hbr.readBlockData(0, -1, -1, pread);
        b.sanityCheck();
        assertEquals(4936, b.getUncompressedSizeWithoutHeader());
        assertEquals(algo == GZ ? 2173 : 4936, 
                     b.getOnDiskSizeWithoutHeader() - b.totalChecksumBytes());
        // read data back from the hfile, exclude header and checksum
        ByteBuffer bb = b.getBufferWithoutHeader(); // read back data
        DataInputStream in = new DataInputStream(
                               new ByteArrayInputStream(
                                 bb.array(), bb.arrayOffset(), bb.limit()));

        // assert that we encountered hbase checksum verification failures
        // but still used hdfs checksums and read data successfully.
        assertEquals(1, HFile.getChecksumFailuresCount());
        validateData(in);

        // A single instance of hbase checksum failure causes the reader to
        // switch off hbase checksum verification for the next 100 read
        // requests. Verify that this is correct.
        for (int i = 0; i < 
             HFileBlock.CHECKSUM_VERIFICATION_NUM_IO_THRESHOLD + 1; i++) {
          b = hbr.readBlockData(0, -1, -1, pread);
          assertEquals(0, HFile.getChecksumFailuresCount());
        }
        // The next read should have hbase checksum verification reanabled,
        // we verify this by assertng that there was a hbase-checksum failure.
        b = hbr.readBlockData(0, -1, -1, pread);
        assertEquals(1, HFile.getChecksumFailuresCount());

        // Since the above encountered a checksum failure, we switch
        // back to not checking hbase checksums.
        b = hbr.readBlockData(0, -1, -1, pread);
        assertEquals(0, HFile.getChecksumFailuresCount());
        is.close();

        // Now, use a completely new reader. Switch off hbase checksums in 
        // the configuration. In this case, we should not detect
        // any retries within hbase. 
        HFileSystem newfs = new HFileSystem(TEST_UTIL.getConfiguration(), false);
        assertEquals(false, newfs.useHBaseChecksum());
        is = newfs.open(path);
        hbr = new FSReaderV2Test(is, algo,
            totalSize, HFile.MAX_FORMAT_VERSION, newfs, path);
        b = hbr.readBlockData(0, -1, -1, pread);
        is.close();
        b.sanityCheck();
        assertEquals(4936, b.getUncompressedSizeWithoutHeader());
        assertEquals(algo == GZ ? 2173 : 4936, 
                     b.getOnDiskSizeWithoutHeader() - b.totalChecksumBytes());
        // read data back from the hfile, exclude header and checksum
        bb = b.getBufferWithoutHeader(); // read back data
        in = new DataInputStream(new ByteArrayInputStream(
                                 bb.array(), bb.arrayOffset(), bb.limit()));

        // assert that we did not encounter hbase checksum verification failures
        // but still used hdfs checksums and read data successfully.
        assertEquals(0, HFile.getChecksumFailuresCount());
        validateData(in);
      }
    }
  }

  /** 
   * Test different values of bytesPerChecksum
   */
  @Test
  public void testChecksumChunks() throws IOException {
    Compression.Algorithm algo = NONE;
    for (boolean pread : new boolean[] { false, true }) {
      for (int bytesPerChecksum : BYTES_PER_CHECKSUM) {
        Path path = new Path(TEST_UTIL.getDataTestDir(), "checksumChunk_" + 
                             algo + bytesPerChecksum);
        FSDataOutputStream os = fs.create(path);
        HFileBlock.Writer hbw = new HFileBlock.Writer(algo, null,
          true, 1,HFile.DEFAULT_CHECKSUM_TYPE, bytesPerChecksum);

        // write one block. The block has data
        // that is at least 6 times more than the checksum chunk size
        long dataSize = 0;
        DataOutputStream dos = hbw.startWriting(BlockType.DATA);
        for (; dataSize < 6 * bytesPerChecksum;) {
          for (int i = 0; i < 1234; ++i) {
            dos.writeInt(i);
            dataSize += 4;
          }
        }
        hbw.writeHeaderAndData(os);
        long totalSize = hbw.getOnDiskSizeWithHeader();
        os.close();

        long expectedChunks = ChecksumUtil.numChunks(
                               dataSize + HFileBlock.HEADER_SIZE_WITH_CHECKSUMS,
                               bytesPerChecksum);
        LOG.info("testChecksumChunks: pread=" + pread +
                   ", bytesPerChecksum=" + bytesPerChecksum +
                   ", fileSize=" + totalSize +
                   ", dataSize=" + dataSize +
                   ", expectedChunks=" + expectedChunks);

        // Verify hbase checksums. 
        assertEquals(true, hfs.useHBaseChecksum());

        // Read data back from file.
        FSDataInputStream is = fs.open(path);
        FSDataInputStream nochecksum = hfs.getNoChecksumFs().open(path);
        HFileBlock.FSReader hbr = new HFileBlock.FSReaderV2(is, nochecksum, 
            algo, totalSize, HFile.MAX_FORMAT_VERSION, hfs, path);
        HFileBlock b = hbr.readBlockData(0, -1, -1, pread);
        is.close();
        b.sanityCheck();
        assertEquals(dataSize, b.getUncompressedSizeWithoutHeader());

        // verify that we have the expected number of checksum chunks
        assertEquals(totalSize, HFileBlock.HEADER_SIZE_WITH_CHECKSUMS + dataSize +
                     expectedChunks * HFileBlock.CHECKSUM_SIZE);

        // assert that we did not encounter hbase checksum verification failures
        assertEquals(0, HFile.getChecksumFailuresCount());
      }
    }
  }

  /** 
   * Test to ensure that these is at least one valid checksum implementation
   */
  @Test
  public void testChecksumAlgorithm() throws IOException {
    ChecksumType type = ChecksumType.CRC32;
    assertEquals(ChecksumType.nameToType(type.getName()), type);
    assertEquals(ChecksumType.valueOf(type.toString()), type);
  }

  private void validateData(DataInputStream in) throws IOException {
    // validate data
    for (int i = 0; i < 1234; i++) {
      int val = in.readInt();
      if (val != i) {
        String msg = "testChecksumCorruption: data mismatch at index " +
                     i + " expected " + i + " found " + val;
        LOG.warn(msg);
        assertEquals(i, val);
      }
    }
  }

  @org.junit.Rule
  public org.apache.hadoop.hbase.ResourceCheckerJUnitRule cu =
    new org.apache.hadoop.hbase.ResourceCheckerJUnitRule();

  /**
   * A class that introduces hbase-checksum failures while 
   * reading  data from hfiles. This should trigger the hdfs level
   * checksum validations.
   */
  static private class FSReaderV2Test extends HFileBlock.FSReaderV2 {

    FSReaderV2Test(FSDataInputStream istream, Algorithm algo,
                   long fileSize, int minorVersion, FileSystem fs,
                   Path path) throws IOException {
      super(istream, istream, algo, fileSize, minorVersion, 
            (HFileSystem)fs, path);
    }

    @Override
    protected boolean validateBlockChecksum(HFileBlock block, 
      byte[] data, int hdrSize) throws IOException {
      return false;  // checksum validation failure
    }
  }
}

