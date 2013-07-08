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

package org.apache.hadoop.hbase.util;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.ByteBuffer;

import junit.framework.TestCase;
import org.apache.hadoop.hbase.SmallTests;
import org.junit.experimental.categories.Category;

@Category(SmallTests.class)
public class TestByteBloomFilter extends TestCase {

  public void testBasicBloom() throws Exception {
    ByteBloomFilter bf1 = new ByteBloomFilter(1000, (float)0.01, Hash.MURMUR_HASH, 0);
    ByteBloomFilter bf2 = new ByteBloomFilter(1000, (float)0.01, Hash.MURMUR_HASH, 0);
    bf1.allocBloom();
    bf2.allocBloom();

    // test 1: verify no fundamental false negatives or positives
    byte[] key1 = {1,2,3,4,5,6,7,8,9};
    byte[] key2 = {1,2,3,4,5,6,7,8,7};

    bf1.add(key1);
    bf2.add(key2);

    assertTrue(bf1.contains(key1));
    assertFalse(bf1.contains(key2));
    assertFalse(bf2.contains(key1));
    assertTrue(bf2.contains(key2));

    byte [] bkey = {1,2,3,4};
    byte [] bval = "this is a much larger byte array".getBytes();

    bf1.add(bkey);
    bf1.add(bval, 1, bval.length-1);

    assertTrue( bf1.contains(bkey) );
    assertTrue( bf1.contains(bval, 1, bval.length-1) );
    assertFalse( bf1.contains(bval) );
    assertFalse( bf1.contains(bval) );

    // test 2: serialization & deserialization.
    // (convert bloom to byte array & read byte array back in as input)
    ByteArrayOutputStream bOut = new ByteArrayOutputStream();
    bf1.writeBloom(new DataOutputStream(bOut));
    ByteBuffer bb = ByteBuffer.wrap(bOut.toByteArray());
    ByteBloomFilter newBf1 = new ByteBloomFilter(1000, (float)0.01,
        Hash.MURMUR_HASH, 0);
    assertTrue(newBf1.contains(key1, bb));
    assertFalse(newBf1.contains(key2, bb));
    assertTrue( newBf1.contains(bkey, bb) );
    assertTrue( newBf1.contains(bval, 1, bval.length-1, bb) );
    assertFalse( newBf1.contains(bval, bb) );
    assertFalse( newBf1.contains(bval, bb) );

    System.out.println("Serialized as " + bOut.size() + " bytes");
    assertTrue(bOut.size() - bf1.byteSize < 10); //... allow small padding
  }

  public void testBloomFold() throws Exception {
    // test: foldFactor < log(max/actual)
    ByteBloomFilter b = new ByteBloomFilter(1003, (float) 0.01,
        Hash.MURMUR_HASH, 2);
    b.allocBloom();
    long origSize = b.getByteSize();
    assertEquals(1204, origSize);
    for (int i = 0; i < 12; ++i) {
      b.add(Bytes.toBytes(i));
    }
    b.compactBloom();
    assertEquals(origSize>>2, b.getByteSize());
    int falsePositives = 0;
    for (int i = 0; i < 25; ++i) {
      if (b.contains(Bytes.toBytes(i))) {
        if(i >= 12) falsePositives++;
      } else {
        assertFalse(i < 12);
      }
    }
    assertTrue(falsePositives <= 1);

    // test: foldFactor > log(max/actual)
  }

  public void testBloomPerf() throws Exception {
    // add
    float err = (float)0.01;
    ByteBloomFilter b = new ByteBloomFilter(10*1000*1000, (float)err, Hash.MURMUR_HASH, 3);
    b.allocBloom();
    long startTime =  System.currentTimeMillis();
    long origSize = b.getByteSize();
    for (int i = 0; i < 1*1000*1000; ++i) {
      b.add(Bytes.toBytes(i));
    }
    long endTime = System.currentTimeMillis();
    System.out.println("Total Add time = " + (endTime - startTime) + "ms");

    // fold
    startTime = System.currentTimeMillis();
    b.compactBloom();
    endTime = System.currentTimeMillis();
    System.out.println("Total Fold time = " + (endTime - startTime) + "ms");
    assertTrue(origSize >= b.getByteSize()<<3);

    // test
    startTime = System.currentTimeMillis();
    int falsePositives = 0;
    for (int i = 0; i < 2*1000*1000; ++i) {

      if (b.contains(Bytes.toBytes(i))) {
        if(i >= 1*1000*1000) falsePositives++;
      } else {
        assertFalse(i < 1*1000*1000);
      }
    }
    endTime = System.currentTimeMillis();
    System.out.println("Total Contains time = " + (endTime - startTime) + "ms");
    System.out.println("False Positive = " + falsePositives);
    assertTrue(falsePositives <= (1*1000*1000)*err);

    // test: foldFactor > log(max/actual)
  }

  public void testSizing() {
    int bitSize = 8 * 128 * 1024; // 128 KB
    double errorRate = 0.025; // target false positive rate

    // How many keys can we store in a Bloom filter of this size maintaining
    // the given false positive rate, not taking into account that the n
    long maxKeys = ByteBloomFilter.idealMaxKeys(bitSize, errorRate);
    assertEquals(136570, maxKeys);

    // A reverse operation: how many bits would we need to store this many keys
    // and keep the same low false positive rate?
    long bitSize2 = ByteBloomFilter.computeBitSize(maxKeys, errorRate);

    // The bit size comes out a little different due to rounding.
    assertTrue(Math.abs(bitSize2 - bitSize) * 1.0 / bitSize < 1e-5);
  }

  public void testFoldableByteSize() {
    assertEquals(128, ByteBloomFilter.computeFoldableByteSize(1000, 5));
    assertEquals(640, ByteBloomFilter.computeFoldableByteSize(5001, 4));
  }


  @org.junit.Rule
  public org.apache.hadoop.hbase.ResourceCheckerJUnitRule cu =
    new org.apache.hadoop.hbase.ResourceCheckerJUnitRule();
}

