/*
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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

import java.io.File;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.HBaseTestingUtility;
import org.apache.hadoop.hbase.SmallTests;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * Test TestDynamicClassLoader
 */
@Category(SmallTests.class)
public class TestDynamicClassLoader {
  private static final Log LOG = LogFactory.getLog(TestDynamicClassLoader.class);

  private static final Configuration conf = HBaseConfiguration.create();

  private static final HBaseTestingUtility TEST_UTIL = new HBaseTestingUtility();

  static {
    conf.set("hbase.dynamic.jars.dir", TEST_UTIL.getDataTestDir().toString());
  }

  @Test
  public void testLoadClassFromLocalPath() throws Exception {
    ClassLoader parent = TestDynamicClassLoader.class.getClassLoader();
    DynamicClassLoader classLoader = new DynamicClassLoader(conf, parent);

    String className = "TestLoadClassFromLocalPath";
    deleteClass(className);
    try {
      classLoader.loadClass(className);
      fail("Should not be able to load class " + className);
    } catch (ClassNotFoundException cnfe) {
      // expected, move on
    }

    try {
      String folder = TEST_UTIL.getDataTestDir().toString();
      ClassLoaderTestHelper.buildJar(folder, className, null, localDirPath());
      classLoader.loadClass(className);
    } catch (ClassNotFoundException cnfe) {
      LOG.error("Should be able to load class " + className, cnfe);
      fail(cnfe.getMessage());
    }
  }

  @Test
  public void testLoadClassFromAnotherPath() throws Exception {
    ClassLoader parent = TestDynamicClassLoader.class.getClassLoader();
    DynamicClassLoader classLoader = new DynamicClassLoader(conf, parent);

    String className = "TestLoadClassFromAnotherPath";
    deleteClass(className);
    try {
      classLoader.loadClass(className);
      fail("Should not be able to load class " + className);
    } catch (ClassNotFoundException cnfe) {
      // expected, move on
    }

    try {
      String folder = TEST_UTIL.getDataTestDir().toString();
      ClassLoaderTestHelper.buildJar(folder, className, null);
      classLoader.loadClass(className);
    } catch (ClassNotFoundException cnfe) {
      LOG.error("Should be able to load class " + className, cnfe);
      fail(cnfe.getMessage());
    }
  }

  private String localDirPath() {
    return conf.get("hbase.local.dir")
      + File.separator + "jars" + File.separator;
  }

  private void deleteClass(String className) throws Exception {
    String jarFileName = className + ".jar";
    File file = new File(TEST_UTIL.getDataTestDir().toString(), jarFileName);
    file.delete();
    assertFalse("Should be deleted: " + file.getPath(), file.exists());

    file = new File(conf.get("hbase.dynamic.jars.dir"), jarFileName);
    file.delete();
    assertFalse("Should be deleted: " + file.getPath(), file.exists());

    file = new File(localDirPath(), jarFileName);
    file.delete();
    assertFalse("Should be deleted: " + file.getPath(), file.exists());
  }

  @org.junit.Rule
  public org.apache.hadoop.hbase.ResourceCheckerJUnitRule cu =
    new org.apache.hadoop.hbase.ResourceCheckerJUnitRule();
}
