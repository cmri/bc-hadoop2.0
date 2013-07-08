/*
 * Copyright 2011 The Apache Software Foundation
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

import static org.junit.Assert.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Random;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.HBaseTestingUtility;
import org.apache.hadoop.hbase.HColumnDescriptor;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.HRegionInfo;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.MediumTests;
import org.apache.hadoop.hbase.fs.HFileSystem;
import org.apache.hadoop.hbase.io.hfile.BlockCache;
import org.apache.hadoop.hbase.io.hfile.BlockCacheKey;
import org.apache.hadoop.hbase.io.hfile.BlockType;
import org.apache.hadoop.hbase.io.hfile.CacheConfig;
import org.apache.hadoop.hbase.io.hfile.Compression;
import org.apache.hadoop.hbase.io.hfile.HFile;
import org.apache.hadoop.hbase.io.hfile.HFileBlock;
import org.apache.hadoop.hbase.io.hfile.HFileReaderV2;
import org.apache.hadoop.hbase.io.hfile.HFileScanner;
import org.apache.hadoop.hbase.io.hfile.TestHFileWriterV2;
import org.apache.hadoop.hbase.regionserver.StoreFile.BloomType;
import org.apache.hadoop.hbase.regionserver.wal.HLog;
import org.apache.hadoop.hbase.util.Bytes;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

/**
 * Tests {@link HFile} cache-on-write functionality for data blocks, non-root
 * index blocks, and Bloom filter blocks, as specified by the column family. 
 */
@RunWith(Parameterized.class)
@Category(MediumTests.class)
public class TestCacheOnWriteInSchema {

  private static final Log LOG = LogFactory.getLog(TestCacheOnWriteInSchema.class);

  private static final HBaseTestingUtility TEST_UTIL = new HBaseTestingUtility();
  private static final String DIR = TEST_UTIL.getDataTestDir("TestCacheOnWriteInSchema").toString();
  private static final byte [] table = Bytes.toBytes("table");
  private static byte [] family = Bytes.toBytes("family");
  private static final int NUM_KV = 25000;
  private static final Random rand = new Random(12983177L);
  /** The number of valid key types possible in a store file */
  private static final int NUM_VALID_KEY_TYPES =
      KeyValue.Type.values().length - 2;

  private static enum CacheOnWriteType {
    DATA_BLOCKS(BlockType.DATA, BlockType.ENCODED_DATA),
    BLOOM_BLOCKS(BlockType.BLOOM_CHUNK),
    INDEX_BLOCKS(BlockType.LEAF_INDEX, BlockType.INTERMEDIATE_INDEX);

    private final BlockType blockType1;
    private final BlockType blockType2;

    private CacheOnWriteType(BlockType blockType) {
      this(blockType, blockType);
    }

    private CacheOnWriteType(BlockType blockType1, BlockType blockType2) {
      this.blockType1 = blockType1;
      this.blockType2 = blockType2;
    }

    public boolean shouldBeCached(BlockType blockType) {
      return blockType == blockType1 || blockType == blockType2;
    }

    public void modifyFamilySchema(HColumnDescriptor family) {
      switch (this) {
      case DATA_BLOCKS:
        family.setCacheDataOnWrite(true);
        break;
      case BLOOM_BLOCKS:
        family.setCacheBloomsOnWrite(true);
        break;
      case INDEX_BLOCKS:
        family.setCacheIndexesOnWrite(true);
        break;
      }
    }
  }

  private final CacheOnWriteType cowType;
  private Configuration conf;
  private final String testDescription;
  private Store store;
  private FileSystem fs;

  public TestCacheOnWriteInSchema(CacheOnWriteType cowType) {
    this.cowType = cowType;
    testDescription = "[cacheOnWrite=" + cowType + "]";
    System.out.println(testDescription);
  }

  @Parameters
  public static Collection<Object[]> getParameters() {
    List<Object[]> cowTypes = new ArrayList<Object[]>();
    for (CacheOnWriteType cowType : CacheOnWriteType.values()) {
      cowTypes.add(new Object[] { cowType });
    }
    return cowTypes;
  }

  @Before
  public void setUp() throws IOException {
    conf = TEST_UTIL.getConfiguration();
    conf.setInt(HFile.FORMAT_VERSION_KEY, HFile.MAX_FORMAT_VERSION);
    conf.setBoolean(CacheConfig.CACHE_BLOCKS_ON_WRITE_KEY, false);
    conf.setBoolean(CacheConfig.CACHE_INDEX_BLOCKS_ON_WRITE_KEY, false);
    conf.setBoolean(CacheConfig.CACHE_BLOOM_BLOCKS_ON_WRITE_KEY, false);

    fs = HFileSystem.get(conf);

    // Create the schema
    HColumnDescriptor hcd = new HColumnDescriptor(family);
    hcd.setBloomFilterType(BloomType.ROWCOL);
    cowType.modifyFamilySchema(hcd);
    HTableDescriptor htd = new HTableDescriptor(table);
    htd.addFamily(hcd);

    // Create a store based on the schema
    Path basedir = new Path(DIR);
    Path logdir = new Path(DIR+"/logs");
    Path oldLogDir = new Path(basedir, HConstants.HREGION_OLDLOGDIR_NAME);
    fs.delete(logdir, true);
    HRegionInfo info = new HRegionInfo(htd.getName(), null, null, false);
    HLog hlog = new HLog(fs, logdir, oldLogDir, conf);
    HRegion region = new HRegion(basedir, hlog, fs, conf, info, htd, null);
    store = new Store(basedir, region, hcd, fs, conf);
  }

  @After
  public void tearDown() {
    try {
      fs.delete(new Path(DIR), true);
    } catch (IOException e) {
      LOG.error("Could not delete " + DIR, e);
    }
  }

  @Test
  public void testCacheOnWriteInSchema() throws IOException {
    // Write some random data into the store
    StoreFile.Writer writer = store.createWriterInTmp(Integer.MAX_VALUE,
        Compression.Algorithm.NONE, false);
    writeStoreFile(writer);
    writer.close();
    // Verify the block types of interest were cached on write
    readStoreFile(writer.getPath());
  }

  private void readStoreFile(Path path) throws IOException {
    CacheConfig cacheConf = store.getCacheConfig(); 
    BlockCache cache = cacheConf.getBlockCache();
    StoreFile sf = new StoreFile(fs, path, conf, cacheConf,
        BloomType.ROWCOL, null);
    store.passSchemaMetricsTo(sf);
    HFileReaderV2 reader = (HFileReaderV2) sf.createReader().getHFileReader();
    try {
      // Open a scanner with (on read) caching disabled
      HFileScanner scanner = reader.getScanner(false, false);
      assertTrue(testDescription, scanner.seekTo());
      // Cribbed from io.hfile.TestCacheOnWrite
      long offset = 0;
      HFileBlock prevBlock = null;
      while (offset < reader.getTrailer().getLoadOnOpenDataOffset()) {
        long onDiskSize = -1;
        if (prevBlock != null) {
          onDiskSize = prevBlock.getNextBlockOnDiskSizeWithHeader();
        }
        // Flags: don't cache the block, use pread, this is not a compaction.
        // Also, pass null for expected block type to avoid checking it.
        HFileBlock block = reader.readBlock(offset, onDiskSize, false, true,
          false, null);
        BlockCacheKey blockCacheKey = new BlockCacheKey(reader.getName(),
          offset);
        boolean isCached = cache.getBlock(blockCacheKey, true, false) != null;
        boolean shouldBeCached = cowType.shouldBeCached(block.getBlockType());
        if (shouldBeCached != isCached) {
          throw new AssertionError(
            "shouldBeCached: " + shouldBeCached+ "\n" +
            "isCached: " + isCached + "\n" +
            "Test description: " + testDescription + "\n" +
            "block: " + block + "\n" +
            "blockCacheKey: " + blockCacheKey);
        }
        prevBlock = block;
        offset += block.getOnDiskSizeWithHeader();
      }
    } finally {
      reader.close();
    }
  }

  private static KeyValue.Type generateKeyType(Random rand) {
    if (rand.nextBoolean()) {
      // Let's make half of KVs puts.
      return KeyValue.Type.Put;
    } else {
      KeyValue.Type keyType =
          KeyValue.Type.values()[1 + rand.nextInt(NUM_VALID_KEY_TYPES)];
      if (keyType == KeyValue.Type.Minimum || keyType == KeyValue.Type.Maximum)
      {
        throw new RuntimeException("Generated an invalid key type: " + keyType
            + ". " + "Probably the layout of KeyValue.Type has changed.");
      }
      return keyType;
    }
  }

  private void writeStoreFile(StoreFile.Writer writer) throws IOException {
    final int rowLen = 32;
    for (int i = 0; i < NUM_KV; ++i) {
      byte[] k = TestHFileWriterV2.randomOrderedKey(rand, i);
      byte[] v = TestHFileWriterV2.randomValue(rand);
      int cfLen = rand.nextInt(k.length - rowLen + 1);
      KeyValue kv = new KeyValue(
          k, 0, rowLen,
          k, rowLen, cfLen,
          k, rowLen + cfLen, k.length - rowLen - cfLen,
          rand.nextLong(),
          generateKeyType(rand),
          v, 0, v.length);
      writer.append(kv);
    }
  }

  @org.junit.Rule
  public org.apache.hadoop.hbase.ResourceCheckerJUnitRule cu =
    new org.apache.hadoop.hbase.ResourceCheckerJUnitRule();
}

