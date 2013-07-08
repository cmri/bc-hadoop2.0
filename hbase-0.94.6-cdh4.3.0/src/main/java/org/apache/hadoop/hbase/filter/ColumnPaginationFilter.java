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

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;

import org.apache.hadoop.hbase.KeyValue;
import com.google.common.base.Preconditions;

/**
 * A filter, based on the ColumnCountGetFilter, takes two arguments: limit and offset.
 * This filter can be used for row-based indexing, where references to other tables are stored across many columns,
 * in order to efficient lookups and paginated results for end users. Only most recent versions are considered
 * for pagination.
 */
public class ColumnPaginationFilter extends FilterBase
{
  private int limit = 0;
  private int offset = 0;
  private int count = 0;

    /**
     * Used during serialization. Do not use.
     */
  public ColumnPaginationFilter()
  {
    super();
  }

  public ColumnPaginationFilter(final int limit, final int offset)
  {
    Preconditions.checkArgument(limit >= 0, "limit must be positive %s", limit);
    Preconditions.checkArgument(offset >= 0, "offset must be positive %s", offset);
    this.limit = limit;
    this.offset = offset;
  }

  /**
   * @return limit
   */
  public int getLimit() {
    return limit;
  }

  /**
   * @return offset
   */
  public int getOffset() {
    return offset;
  }

  @Override
  public ReturnCode filterKeyValue(KeyValue v)
  {
    if(count >= offset + limit)
    {
      return ReturnCode.NEXT_ROW;
    }

    ReturnCode code = count < offset ? ReturnCode.NEXT_COL :
                                       ReturnCode.INCLUDE_AND_NEXT_COL;
    count++;
    return code;
  }

  @Override
  public void reset()
  {
    this.count = 0;
  }

  public static Filter createFilterFromArguments(ArrayList<byte []> filterArguments) {
    Preconditions.checkArgument(filterArguments.size() == 2,
                                "Expected 2 but got: %s", filterArguments.size());
    int limit = ParseFilter.convertByteArrayToInt(filterArguments.get(0));
    int offset = ParseFilter.convertByteArrayToInt(filterArguments.get(1));
    return new ColumnPaginationFilter(limit, offset);
  }

  public void readFields(DataInput in) throws IOException
  {
    this.limit = in.readInt();
    this.offset = in.readInt();
  }

  public void write(DataOutput out) throws IOException
  {
    out.writeInt(this.limit);
    out.writeInt(this.offset);
  }

  @Override
  public String toString() {
    return String.format("%s (%d, %d)", this.getClass().getSimpleName(),
        this.limit, this.offset);
  }
}
