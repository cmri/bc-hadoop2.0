/**
 * Copyright 2008 The Apache Software Foundation
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

import java.lang.ref.ReferenceQueue;
import java.lang.ref.SoftReference;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.hadoop.conf.Configuration;


/**
 * Simple one RFile soft reference cache.
 */
public class SimpleBlockCache implements BlockCache {
  private static class Ref extends SoftReference<Cacheable> {
    public BlockCacheKey blockId;
    public Ref(BlockCacheKey blockId, Cacheable block, ReferenceQueue q) {
      super(block, q);
      this.blockId = blockId;
    }
  }
  private Map<BlockCacheKey,Ref> cache =
    new HashMap<BlockCacheKey,Ref>();

  private ReferenceQueue q = new ReferenceQueue();
  public int dumps = 0;

  /**
   * Constructor
   */
  public SimpleBlockCache() {
    super();
  }

  void processQueue() {
    Ref r;
    while ( (r = (Ref)q.poll()) != null) {
      cache.remove(r.blockId);
      dumps++;
    }
  }

  /**
   * @return the size
   */
  public synchronized long size() {
    processQueue();
    return cache.size();
  }

  public synchronized Cacheable getBlock(BlockCacheKey cacheKey, boolean caching, boolean repeat) {
    processQueue(); // clear out some crap.
    Ref ref = cache.get(cacheKey);
    if (ref == null)
      return null;
    return ref.get();
  }

  public synchronized void cacheBlock(BlockCacheKey cacheKey, Cacheable block) {
    cache.put(cacheKey, new Ref(cacheKey, block, q));
  }

  public synchronized void cacheBlock(BlockCacheKey cacheKey, Cacheable block,
      boolean inMemory) {
    cache.put(cacheKey, new Ref(cacheKey, block, q));
  }

  @Override
  public boolean evictBlock(BlockCacheKey cacheKey) {
    return cache.remove(cacheKey) != null;
  }

  public void shutdown() {
    // noop
  }

  @Override
  public CacheStats getStats() {
    // TODO: implement this if we ever actually use this block cache
    return null;
  }

  @Override
  public long getFreeSize() {
    // TODO: implement this if we ever actually use this block cache
    return 0;
  }

  @Override
  public long getCurrentSize() {
    // TODO: implement this if we ever actually use this block cache
    return 0;
  }

  @Override
  public long getEvictedCount() {
    // TODO: implement this if we ever actually use this block cache
    return 0;
  }

  @Override
  public int evictBlocksByHfileName(String string) {
    throw new UnsupportedOperationException();
  }

  @Override
  public List<BlockCacheColumnFamilySummary> getBlockCacheColumnFamilySummaries(Configuration conf) {
    throw new UnsupportedOperationException();
  }

  @Override
  public long getBlockCount() {
    // TODO: implement this if we ever actually use this block cache
    return 0;
  }

}

