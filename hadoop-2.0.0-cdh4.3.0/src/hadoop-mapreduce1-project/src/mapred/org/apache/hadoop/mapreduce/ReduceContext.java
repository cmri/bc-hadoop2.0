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

package org.apache.hadoop.mapreduce;

import java.io.IOException;
import java.util.Iterator;
import java.util.NoSuchElementException;

import org.apache.hadoop.mapred.RawKeyValueIterator;

/**
 * The context passed to the {@link Reducer}.
 * @param <KEYIN> the class of the input keys
 * @param <VALUEIN> the class of the input values
 * @param <KEYOUT> the class of the output keys
 * @param <VALUEOUT> the class of the output values
 */
public interface ReduceContext<KEYIN,VALUEIN,KEYOUT,VALUEOUT>
    extends TaskInputOutputContext<KEYIN,VALUEIN,KEYOUT,VALUEOUT> {

  /** Start processing next unique key. */
  public boolean nextKey() throws IOException,InterruptedException;

  /**
   * Iterate through the values for the current key, reusing the same value 
   * object, which is stored in the context.
   * @return the series of values associated with the current key. All of the 
   * objects returned directly and indirectly from this method are reused.
   */
  public Iterable<VALUEIN> getValues() throws IOException, InterruptedException;

  /**
   * {@link Iterator} to iterate over values for a given group of records.
   */
  interface ValueIterator<VALUEIN> extends Iterator<VALUEIN> {

  }
}


