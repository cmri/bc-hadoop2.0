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
package org.apache.hadoop.hbase.filter;

import org.apache.hadoop.hbase.KeyValue;

import java.io.DataOutput;
import java.io.IOException;
import java.io.DataInput;
import java.util.List;
import java.util.ArrayList;

import com.google.common.base.Preconditions;

/**
 * A filter that will only return the first KV from each row.
 * <p>
 * This filter can be used to more efficiently perform row count operations.
 */
public class FirstKeyOnlyFilter extends FilterBase {
  private boolean foundKV = false;

  public FirstKeyOnlyFilter() {
  }

  public void reset() {
    foundKV = false;
  }

  public ReturnCode filterKeyValue(KeyValue v) {
    if(foundKV) return ReturnCode.NEXT_ROW;
    foundKV = true;
    return ReturnCode.INCLUDE;
  }

  public static Filter createFilterFromArguments(ArrayList<byte []> filterArguments) {
    Preconditions.checkArgument(filterArguments.size() == 0,
                                "Expected 0 but got: %s", filterArguments.size());
    return new FirstKeyOnlyFilter();
  }

  public void write(DataOutput out) throws IOException {
  }

  public void readFields(DataInput in) throws IOException {
  }
}
