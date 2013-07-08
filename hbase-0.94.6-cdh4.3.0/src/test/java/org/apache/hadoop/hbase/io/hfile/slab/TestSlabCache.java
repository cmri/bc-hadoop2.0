/**
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
package org.apache.hadoop.hbase.io.hfile.slab;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.MediumTests;
import org.apache.hadoop.hbase.io.hfile.CacheTestUtils;
import org.apache.hadoop.hbase.io.hfile.slab.SlabCache.SlabStats;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.Ignore;
import org.junit.experimental.categories.Category;

/**
 * Basic test of SlabCache. Puts and gets.
 * <p>
 *
 * Tests will ensure that blocks that are uncached are identical to the ones
 * being cached, and that the cache never exceeds its capacity. Note that its
 * fine if the cache evicts before it reaches max capacity - Guava Mapmaker may
 * choose to evict at any time.
 *
 */
// Starts 50 threads, high variability of execution time => Medium
@Category(MediumTests.class)
public class TestSlabCache {
  static final int CACHE_SIZE = 1000000;
  static final int NUM_BLOCKS = 101;
  static final int BLOCK_SIZE = CACHE_SIZE / NUM_BLOCKS;
  static final int NUM_THREADS = 50;
  static final int NUM_QUERIES = 10000;
  SlabCache cache;

  @Before
  public void setup() {
    cache = new SlabCache(CACHE_SIZE + BLOCK_SIZE * 2, BLOCK_SIZE);
    cache.addSlabByConf(new Configuration());
  }

  @After
  public void tearDown() {
    cache.shutdown();
  }

  @Test
  public void testElementPlacement() {
    assertEquals(cache.getHigherBlock(BLOCK_SIZE).getKey().intValue(),
        (BLOCK_SIZE * 11 / 10));
    assertEquals(cache.getHigherBlock((BLOCK_SIZE * 2)).getKey()
        .intValue(), (BLOCK_SIZE * 21 / 10));
  }

 @Test
  public void testCacheSimple() throws Exception {
    CacheTestUtils.testCacheSimple(cache, BLOCK_SIZE, NUM_QUERIES);
  }

  @Test
  public void testCacheMultiThreaded() throws Exception {
    CacheTestUtils.testCacheMultiThreaded(cache, BLOCK_SIZE, NUM_THREADS,
        NUM_QUERIES, 0.80);
  }

  @Test
  public void testCacheMultiThreadedSingleKey() throws Exception {
    CacheTestUtils.hammerSingleKey(cache, BLOCK_SIZE, NUM_THREADS, NUM_QUERIES);
  }

  @Test
  public void testCacheMultiThreadedEviction() throws Exception {
    CacheTestUtils.hammerEviction(cache, BLOCK_SIZE, 10, NUM_QUERIES);
  }

  @Test
  /*Just checks if ranges overlap*/
  public void testStatsArithmetic(){
    SlabStats test = cache.requestStats;
    for(int i = 0; i < test.NUMDIVISIONS; i++){
      assertTrue("Upper for index " + i + " is " + test.getUpperBound(i) +
          " lower " + test.getLowerBound(i + 1),
          test.getUpperBound(i) <= test.getLowerBound(i + 1));
    }
  }

  @Test
  public void testHeapSizeChanges(){
    CacheTestUtils.testHeapSizeChanges(cache, BLOCK_SIZE);
  }

  @org.junit.Rule
  public org.apache.hadoop.hbase.ResourceCheckerJUnitRule cu =
    new org.apache.hadoop.hbase.ResourceCheckerJUnitRule();
}

