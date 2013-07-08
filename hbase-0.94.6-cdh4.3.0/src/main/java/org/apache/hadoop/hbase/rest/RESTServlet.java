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
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.hbase.rest;

import java.io.IOException;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.client.HBaseAdmin;
import org.apache.hadoop.hbase.client.HTablePool;
import org.apache.hadoop.hbase.rest.metrics.RESTMetrics;

/**
 * Singleton class encapsulating global REST servlet state and functions.
 */
public class RESTServlet implements Constants {
  private static RESTServlet INSTANCE;
  private final Configuration conf;
  private final HTablePool pool;
  private final RESTMetrics metrics = new RESTMetrics();
  private final HBaseAdmin admin;

  /**
   * @return the RESTServlet singleton instance
   * @throws IOException
   */
  public synchronized static RESTServlet getInstance() throws IOException {
    assert(INSTANCE != null);
    return INSTANCE;
  }

  /**
   * @param conf Existing configuration to use in rest servlet
   * @return the RESTServlet singleton instance
   * @throws IOException
   */
  public synchronized static RESTServlet getInstance(Configuration conf)
  throws IOException {
    if (INSTANCE == null) {
      INSTANCE = new RESTServlet(conf);
    }
    return INSTANCE;
  }

  public synchronized static void stop() {
    if (INSTANCE != null)  INSTANCE = null;
  }

  /**
   * Constructor with existing configuration
   * @param conf existing configuration
   * @throws IOException.
   */
  RESTServlet(Configuration conf) throws IOException {
    this.conf = conf;
    int maxSize = conf.getInt("hbase.rest.htablepool.size", 10);
    this.pool = new HTablePool(conf, maxSize);
    this.admin = new HBaseAdmin(conf);
  }

  HBaseAdmin getAdmin() {
    return admin;
  }

  HTablePool getTablePool() {
    return pool;
  }

  Configuration getConfiguration() {
    return conf;
  }

  RESTMetrics getMetrics() {
    return metrics;
  }

  /**
   * Helper method to determine if server should
   * only respond to GET HTTP method requests.
   * @return boolean for server read-only state
   */
  boolean isReadOnly() {
    return getConfiguration().getBoolean("hbase.rest.readonly", false);
  }
}
