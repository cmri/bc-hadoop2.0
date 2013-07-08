/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. The ASF
 * licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package org.apache.hadoop.hbase.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import org.apache.hadoop.hbase.SmallTests;
import org.junit.Test;
import org.junit.experimental.categories.Category;

@Category(SmallTests.class)
public class TestLoadTestKVGenerator {

  private static final int MIN_LEN = 10;
  private static final int MAX_LEN = 20;

  private Random rand = new Random(28937293L);
  private LoadTestKVGenerator gen = new LoadTestKVGenerator(MIN_LEN, MAX_LEN);

  @Test
  public void testValueLength() {
    for (int i = 0; i < 1000; ++i) {
      byte[] v = gen.generateRandomSizeValue(i,
          String.valueOf(rand.nextInt()));
      assertTrue(MIN_LEN <= v.length);
      assertTrue(v.length <= MAX_LEN);
    }
  }

  @Test
  public void testVerification() {
    for (int i = 0; i < 1000; ++i) {
      for (int qualIndex = 0; qualIndex < 20; ++qualIndex) {
        String qual = String.valueOf(qualIndex);
        byte[] v = gen.generateRandomSizeValue(i, qual);
        String rowKey = LoadTestKVGenerator.md5PrefixedKey(i);
        assertTrue(LoadTestKVGenerator.verify(rowKey, qual, v));
        v[0]++;
        assertFalse(LoadTestKVGenerator.verify(rowKey, qual, v));
      }
    }
  }

  @Test
  public void testCorrectAndUniqueKeys() {
    Set<String> keys = new HashSet<String>();
    for (int i = 0; i < 1000; ++i) {
      String k = LoadTestKVGenerator.md5PrefixedKey(i);
      assertFalse(keys.contains(k));
      assertTrue(k.endsWith("-" + i));
      keys.add(k);
    }
  }

}
