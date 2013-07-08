/**
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

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.io.HbaseObjectWritable;
import org.apache.hadoop.io.Writable;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Implementation of {@link Filter} that represents an ordered List of Filters
 * which will be evaluated with a specified boolean operator {@link Operator#MUST_PASS_ALL}
 * (<code>!AND</code>) or {@link Operator#MUST_PASS_ONE} (<code>!OR</code>).
 * Since you can use Filter Lists as children of Filter Lists, you can create a
 * hierarchy of filters to be evaluated.
 * Defaults to {@link Operator#MUST_PASS_ALL}.
 * <p>TODO: Fix creation of Configuration on serialization and deserialization.
 */
public class FilterList implements Filter {
  /** set operator */
  public static enum Operator {
    /** !AND */
    MUST_PASS_ALL,
    /** !OR */
    MUST_PASS_ONE
  }

  private static final Configuration conf = HBaseConfiguration.create();
  private static final int MAX_LOG_FILTERS = 5;
  private Operator operator = Operator.MUST_PASS_ALL;
  private List<Filter> filters = new ArrayList<Filter>();

  /**
   * Default constructor, filters nothing. Required though for RPC
   * deserialization.
   */
  public FilterList() {
    super();
  }

  /**
   * Constructor that takes a set of {@link Filter}s. The default operator
   * MUST_PASS_ALL is assumed.
   *
   * @param rowFilters list of filters
   */
  public FilterList(final List<Filter> rowFilters) {
    this.filters = rowFilters;
  }

  /**
   * Constructor that takes a var arg number of {@link Filter}s. The fefault operator
   * MUST_PASS_ALL is assumed.
   * @param rowFilters
   */
  public FilterList(final Filter... rowFilters) {
    this.filters = Arrays.asList(rowFilters);
  }

  /**
   * Constructor that takes an operator.
   *
   * @param operator Operator to process filter set with.
   */
  public FilterList(final Operator operator) {
    this.operator = operator;
  }

  /**
   * Constructor that takes a set of {@link Filter}s and an operator.
   *
   * @param operator Operator to process filter set with.
   * @param rowFilters Set of row filters.
   */
  public FilterList(final Operator operator, final List<Filter> rowFilters) {
    this.filters = rowFilters;
    this.operator = operator;
  }

  /**
   * Constructor that takes a var arg number of {@link Filter}s and an operator.
   *
   * @param operator Operator to process filter set with.
   * @param rowFilters Filters to use
   */
  public FilterList(final Operator operator, final Filter... rowFilters) {
    this.filters = Arrays.asList(rowFilters);
    this.operator = operator;
  }

  /**
   * Get the operator.
   *
   * @return operator
   */
  public Operator getOperator() {
    return operator;
  }

  /**
   * Get the filters.
   *
   * @return filters
   */
  public List<Filter> getFilters() {
    return filters;
  }

  /**
   * Add a filter.
   *
   * @param filter another filter
   */
  public void addFilter(Filter filter) {
    this.filters.add(filter);
  }

  @Override
  public void reset() {
    for (Filter filter : filters) {
      filter.reset();
    }
  }

  @Override
  public boolean filterRowKey(byte[] rowKey, int offset, int length) {
    for (Filter filter : filters) {
      if (this.operator == Operator.MUST_PASS_ALL) {
        if (filter.filterAllRemaining() ||
            filter.filterRowKey(rowKey, offset, length)) {
          return true;
        }
      } else if (this.operator == Operator.MUST_PASS_ONE) {
        if (!filter.filterAllRemaining() &&
            !filter.filterRowKey(rowKey, offset, length)) {
          return false;
        }
      }
    }
    return this.operator == Operator.MUST_PASS_ONE;
  }

  @Override
  public boolean filterAllRemaining() {
    for (Filter filter : filters) {
      if (filter.filterAllRemaining()) {
        if (operator == Operator.MUST_PASS_ALL) {
          return true;
        }
      } else {
        if (operator == Operator.MUST_PASS_ONE) {
          return false;
        }
      }
    }
    return operator == Operator.MUST_PASS_ONE;
  }

  @Override
  public KeyValue transform(KeyValue v) {
    KeyValue current = v;
    for (Filter filter : filters) {
      current = filter.transform(current);
    }
    return current;
  }

  @Override
  public ReturnCode filterKeyValue(KeyValue v) {
    ReturnCode rc = operator == Operator.MUST_PASS_ONE?
        ReturnCode.SKIP: ReturnCode.INCLUDE;
    for (Filter filter : filters) {
      if (operator == Operator.MUST_PASS_ALL) {
        if (filter.filterAllRemaining()) {
          return ReturnCode.NEXT_ROW;
        }
        ReturnCode code = filter.filterKeyValue(v);
        switch (code) {
        // Override INCLUDE and continue to evaluate.
        case INCLUDE_AND_NEXT_COL:
          rc = ReturnCode.INCLUDE_AND_NEXT_COL;
        case INCLUDE:
          continue;
        default:
          return code;
        }
      } else if (operator == Operator.MUST_PASS_ONE) {
        if (filter.filterAllRemaining()) {
          continue;
        }

        switch (filter.filterKeyValue(v)) {
        case INCLUDE:
          if (rc != ReturnCode.INCLUDE_AND_NEXT_COL) {
            rc = ReturnCode.INCLUDE;
          }
          break;
        case INCLUDE_AND_NEXT_COL:
          rc = ReturnCode.INCLUDE_AND_NEXT_COL;
          // must continue here to evaluate all filters
        case NEXT_ROW:
        case SKIP:
          // continue;
        }
      }
    }
    return rc;
  }

  @Override
  public void filterRow(List<KeyValue> kvs) {
    for (Filter filter : filters) {
      filter.filterRow(kvs);
    }
  }

  @Override
  public boolean hasFilterRow() {
    for (Filter filter : filters) {
      if(filter.hasFilterRow()) {
    	return true;
      }
    }
    return false;
  }

  @Override
  public boolean filterRow() {
    for (Filter filter : filters) {
      if (operator == Operator.MUST_PASS_ALL) {
        if (filter.filterRow()) {
          return true;
        }
      } else if (operator == Operator.MUST_PASS_ONE) {
        if (!filter.filterRow()) {
          return false;
        }
      }
    }
    return  operator == Operator.MUST_PASS_ONE;
  }

  public void readFields(final DataInput in) throws IOException {
    byte opByte = in.readByte();
    operator = Operator.values()[opByte];
    int size = in.readInt();
    if (size > 0) {
      filters = new ArrayList<Filter>(size);
      for (int i = 0; i < size; i++) {
        Filter filter = (Filter)HbaseObjectWritable.readObject(in, conf);
        filters.add(filter);
      }
    }
  }

  public void write(final DataOutput out) throws IOException {
    out.writeByte(operator.ordinal());
    out.writeInt(filters.size());
    for (Filter filter : filters) {
      HbaseObjectWritable.writeObject(out, filter, Writable.class, conf);
    }
  }

  @Override
  public KeyValue getNextKeyHint(KeyValue currentKV) {
    KeyValue keyHint = null;
    for (Filter filter : filters) {
      KeyValue curKeyHint = filter.getNextKeyHint(currentKV);
      if (curKeyHint == null && operator == Operator.MUST_PASS_ONE) {
        // If we ever don't have a hint and this is must-pass-one, then no hint
        return null;
      }
      if (curKeyHint != null) {
        // If this is the first hint we find, set it
        if (keyHint == null) {
          keyHint = curKeyHint;
          continue;
        }
        // There is an existing hint
        if (operator == Operator.MUST_PASS_ALL &&
            KeyValue.COMPARATOR.compare(keyHint, curKeyHint) < 0) {
          // If all conditions must pass, we can keep the max hint
          keyHint = curKeyHint;
        } else if (operator == Operator.MUST_PASS_ONE &&
            KeyValue.COMPARATOR.compare(keyHint, curKeyHint) > 0) {
          // If any condition can pass, we need to keep the min hint
          keyHint = curKeyHint;
        }
      }
    }
    return keyHint;
  }

  public boolean isFamilyEssential(byte[] name) {
    for (Filter filter : filters) {
      if (FilterBase.isFamilyEssential(filter, name)) {
        return true;
      }
    }
    return false;
  }

  @Override
  public String toString() {
    return toString(MAX_LOG_FILTERS);
  }

  protected String toString(int maxFilters) {
    int endIndex = this.filters.size() < maxFilters
        ? this.filters.size() : maxFilters;
    return String.format("%s %s (%d/%d): %s",
        this.getClass().getSimpleName(),
        this.operator == Operator.MUST_PASS_ALL ? "AND" : "OR",
        endIndex,
        this.filters.size(),
        this.filters.subList(0, endIndex).toString());
  }
}
