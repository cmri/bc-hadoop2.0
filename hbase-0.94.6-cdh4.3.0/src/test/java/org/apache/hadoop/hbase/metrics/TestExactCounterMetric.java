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

package org.apache.hadoop.hbase.metrics;

import java.util.List;

import junit.framework.Assert;

import org.apache.hadoop.hbase.SmallTests;
import org.apache.hadoop.hbase.util.Pair;
import org.junit.Test;
import org.junit.experimental.categories.Category;

@Category(SmallTests.class)
public class TestExactCounterMetric {

  @Test
  public void testBasic() {
    final ExactCounterMetric counter = new ExactCounterMetric("testCounter", null);
    for (int i = 1; i <= 10; i++) {
      for (int j = 0; j < i; j++) {
        counter.update(i + "");
      }
    }
    
    List<Pair<String, Long>> topFive = counter.getTop(5);
    Long i = 10L;
    for (Pair<String, Long> entry : topFive) {
      Assert.assertEquals(i + "", entry.getFirst());
      Assert.assertEquals(i, entry.getSecond());
      i--;
    }
  }
}
