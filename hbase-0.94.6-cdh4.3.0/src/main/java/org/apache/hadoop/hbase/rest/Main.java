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

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.PosixParser;
import org.apache.commons.cli.ParseException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.rest.filter.GzipFilter;
import org.apache.hadoop.hbase.security.User;
import org.apache.hadoop.hbase.util.InfoServer;
import org.apache.hadoop.hbase.util.Strings;
import org.apache.hadoop.hbase.util.VersionInfo;
import org.apache.hadoop.net.DNS;

import java.util.List;
import java.util.ArrayList;

import org.mortbay.jetty.Connector;
import org.mortbay.jetty.Server;
import org.mortbay.jetty.nio.SelectChannelConnector;
import org.mortbay.jetty.servlet.Context;
import org.mortbay.jetty.servlet.ServletHolder;
import org.mortbay.thread.QueuedThreadPool;

import com.sun.jersey.spi.container.servlet.ServletContainer;

/**
 * Main class for launching REST gateway as a servlet hosted by Jetty.
 * <p>
 * The following options are supported:
 * <ul>
 * <li>-p --port : service port</li>
 * <li>-ro --readonly : server mode</li>
 * </ul>
 */
public class Main implements Constants {

  private static void printUsageAndExit(Options options, int exitCode) {
    HelpFormatter formatter = new HelpFormatter();
    formatter.printHelp("bin/hbase rest start", "", options,
      "\nTo run the REST server as a daemon, execute " +
      "bin/hbase-daemon.sh start|stop rest [--infoport <port>] [-p <port>] [-ro]\n", true);
    System.exit(exitCode);
  }

  /**
   * The main method for the HBase rest server.
   * @param args command-line arguments
   * @throws Exception exception
   */
  public static void main(String[] args) throws Exception {
    Log LOG = LogFactory.getLog("RESTServer");

    VersionInfo.logVersion();
    Configuration conf = HBaseConfiguration.create();
    // login the server principal (if using secure Hadoop)
    if (User.isSecurityEnabled() && User.isHBaseSecurityEnabled(conf)) {
      String machineName = Strings.domainNamePointerToHostName(
        DNS.getDefaultHost(conf.get("hbase.rest.dns.interface", "default"),
          conf.get("hbase.rest.dns.nameserver", "default")));
      User.login(conf, "hbase.rest.keytab.file", "hbase.rest.kerberos.principal",
        machineName);
    }

    RESTServlet servlet = RESTServlet.getInstance(conf);

    Options options = new Options();
    options.addOption("p", "port", true, "Port to bind to [default: 8080]");
    options.addOption("ro", "readonly", false, "Respond only to GET HTTP " +
      "method requests [default: false]");
    options.addOption(null, "infoport", true, "Port for web UI");

    CommandLine commandLine = null;
    try {
      commandLine = new PosixParser().parse(options, args);
    } catch (ParseException e) {
      LOG.error("Could not parse: ", e);
      printUsageAndExit(options, -1);
    }

    // check for user-defined port setting, if so override the conf
    if (commandLine != null && commandLine.hasOption("port")) {
      String val = commandLine.getOptionValue("port");
      servlet.getConfiguration()
          .setInt("hbase.rest.port", Integer.valueOf(val));
      LOG.debug("port set to " + val);
    }

    // check if server should only process GET requests, if so override the conf
    if (commandLine != null && commandLine.hasOption("readonly")) {
      servlet.getConfiguration().setBoolean("hbase.rest.readonly", true);
      LOG.debug("readonly set to true");
    }

    // check for user-defined info server port setting, if so override the conf
    if (commandLine != null && commandLine.hasOption("infoport")) {
      String val = commandLine.getOptionValue("infoport");
      servlet.getConfiguration()
          .setInt("hbase.rest.info.port", Integer.valueOf(val));
      LOG.debug("Web UI port set to " + val);
    }

    @SuppressWarnings("unchecked")
    List<String> remainingArgs = commandLine != null ?
        commandLine.getArgList() : new ArrayList<String>();
    if (remainingArgs.size() != 1) {
      printUsageAndExit(options, 1);
    }

    String command = remainingArgs.get(0);
    if ("start".equals(command)) {
      // continue and start container
    } else if ("stop".equals(command)) {
      System.exit(1);
    } else {
      printUsageAndExit(options, 1);
    }

    // set up the Jersey servlet container for Jetty
    ServletHolder sh = new ServletHolder(ServletContainer.class);
    sh.setInitParameter(
      "com.sun.jersey.config.property.resourceConfigClass",
      ResourceConfig.class.getCanonicalName());
    sh.setInitParameter("com.sun.jersey.config.property.packages",
      "jetty");

    // set up Jetty and run the embedded server

    Server server = new Server();

    Connector connector = new SelectChannelConnector();
    connector.setPort(servlet.getConfiguration().getInt("hbase.rest.port", 8080));
    connector.setHost(servlet.getConfiguration().get("hbase.rest.host", "0.0.0.0"));

    server.addConnector(connector);

    // Set the default max thread number to 100 to limit
    // the number of concurrent requests so that REST server doesn't OOM easily.
    // Jetty set the default max thread number to 250, if we don't set it.
    //
    // Our default min thread number 2 is the same as that used by Jetty.
    int maxThreads = servlet.getConfiguration().getInt("hbase.rest.threads.max", 100);
    int minThreads = servlet.getConfiguration().getInt("hbase.rest.threads.min", 2);
    QueuedThreadPool threadPool = new QueuedThreadPool(maxThreads);
    threadPool.setMinThreads(minThreads);
    server.setThreadPool(threadPool);

    server.setSendServerVersion(false);
    server.setSendDateHeader(false);
    server.setStopAtShutdown(true);

    // set up context
    Context context = new Context(server, "/", Context.SESSIONS);
    context.addServlet(sh, "/*");
    context.addFilter(GzipFilter.class, "/*", 0);

    // Put up info server.
    int port = conf.getInt("hbase.rest.info.port", 8085);
    if (port >= 0) {
      conf.setLong("startcode", System.currentTimeMillis());
      String a = conf.get("hbase.rest.info.bindAddress", "0.0.0.0");
      InfoServer infoServer = new InfoServer("rest", a, port, false, conf);
      infoServer.setAttribute("hbase.conf", conf);
      infoServer.start();
    }

    // start server
    server.start();
    server.join();
  }
}