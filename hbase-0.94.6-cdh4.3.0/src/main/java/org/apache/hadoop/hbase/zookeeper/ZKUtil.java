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
package org.apache.hadoop.hbase.zookeeper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.InetAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Properties;
import java.util.HashMap;
import java.util.Map;

import javax.security.auth.login.LoginException;
import javax.security.auth.login.AppConfigurationEntry;
import javax.security.auth.login.AppConfigurationEntry.LoginModuleControlFlag;
import org.apache.hadoop.security.SecurityUtil;
import org.apache.hadoop.security.authentication.util.KerberosUtil;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.EmptyWatcher;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.ServerName;
import org.apache.hadoop.hbase.executor.RegionTransitionData;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.Threads;
import org.apache.hadoop.hbase.zookeeper.ZKUtil.ZKUtilOp.CreateAndFailSilent;
import org.apache.hadoop.hbase.zookeeper.ZKUtil.ZKUtilOp.DeleteNodeFailSilent;
import org.apache.hadoop.hbase.zookeeper.ZKUtil.ZKUtilOp.SetData;
import org.apache.zookeeper.AsyncCallback;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.KeeperException.NoNodeException;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.client.ZooKeeperSaslClient;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.server.ZooKeeperSaslServer;
import org.apache.zookeeper.proto.CreateRequest;
import org.apache.zookeeper.proto.DeleteRequest;
import org.apache.zookeeper.proto.SetDataRequest;

/**
 * Internal HBase utility class for ZooKeeper.
 *
 * <p>Contains only static methods and constants.
 *
 * <p>Methods all throw {@link KeeperException} if there is an unexpected
 * zookeeper exception, so callers of these methods must handle appropriately.
 * If ZK is required for the operation, the server will need to be aborted.
 */
public class ZKUtil {
  private static final Log LOG = LogFactory.getLog(ZKUtil.class);

  // TODO: Replace this with ZooKeeper constant when ZOOKEEPER-277 is resolved.
  private static final char ZNODE_PATH_SEPARATOR = '/';
  private static int zkDumpConnectionTimeOut;

  /**
   * Creates a new connection to ZooKeeper, pulling settings and ensemble config
   * from the specified configuration object using methods from {@link ZKConfig}.
   *
   * Sets the connection status monitoring watcher to the specified watcher.
   *
   * @param conf configuration to pull ensemble and other settings from
   * @param watcher watcher to monitor connection changes
   * @return connection to zookeeper
   * @throws IOException if unable to connect to zk or config problem
   */
  public static RecoverableZooKeeper connect(Configuration conf, Watcher watcher)
  throws IOException {
    Properties properties = ZKConfig.makeZKProps(conf);
    String ensemble = ZKConfig.getZKQuorumServersString(properties);
    return connect(conf, ensemble, watcher);
  }

  public static RecoverableZooKeeper connect(Configuration conf, String ensemble,
      Watcher watcher)
  throws IOException {
    return connect(conf, ensemble, watcher, "");
  }

  public static RecoverableZooKeeper connect(Configuration conf, String ensemble,
      Watcher watcher, final String descriptor)
  throws IOException {
    if(ensemble == null) {
      throw new IOException("Unable to determine ZooKeeper ensemble");
    }
    int timeout = conf.getInt(HConstants.ZK_SESSION_TIMEOUT,
        HConstants.DEFAULT_ZK_SESSION_TIMEOUT);
    LOG.debug(descriptor + " opening connection to ZooKeeper with ensemble (" +
        ensemble + ")");
    int retry = conf.getInt("zookeeper.recovery.retry", 3);
    int retryIntervalMillis =
      conf.getInt("zookeeper.recovery.retry.intervalmill", 1000);
    zkDumpConnectionTimeOut = conf.getInt("zookeeper.dump.connection.timeout",
        1000);
    return new RecoverableZooKeeper(ensemble, timeout, watcher,
        retry, retryIntervalMillis);
  }

  /**
   * Log in the current zookeeper server process using the given configuration
   * keys for the credential file and login principal.
   *
   * <p><strong>This is only applicable when running on secure hbase</strong>
   * On regular HBase (without security features), this will safely be ignored.
   * </p>
   *
   * @param conf The configuration data to use
   * @param keytabFileKey Property key used to configure the path to the credential file
   * @param userNameKey Property key used to configure the login principal
   * @param hostname Current hostname to use in any credentials
   * @throws IOException underlying exception from SecurityUtil.login() call
   */
  public static void loginServer(Configuration conf, String keytabFileKey,
      String userNameKey, String hostname) throws IOException {
    login(conf, keytabFileKey, userNameKey, hostname,
          ZooKeeperSaslServer.LOGIN_CONTEXT_NAME_KEY,
          JaasConfiguration.SERVER_KEYTAB_KERBEROS_CONFIG_NAME);
  }

  /**
   * Log in the current zookeeper client using the given configuration
   * keys for the credential file and login principal.
   *
   * <p><strong>This is only applicable when running on secure hbase</strong>
   * On regular HBase (without security features), this will safely be ignored.
   * </p>
   *
   * @param conf The configuration data to use
   * @param keytabFileKey Property key used to configure the path to the credential file
   * @param userNameKey Property key used to configure the login principal
   * @param hostname Current hostname to use in any credentials
   * @throws IOException underlying exception from SecurityUtil.login() call
   */
  public static void loginClient(Configuration conf, String keytabFileKey,
      String userNameKey, String hostname) throws IOException {
    login(conf, keytabFileKey, userNameKey, hostname,
          ZooKeeperSaslClient.LOGIN_CONTEXT_NAME_KEY,
          JaasConfiguration.CLIENT_KEYTAB_KERBEROS_CONFIG_NAME);
  }

  /**
   * Log in the current process using the given configuration keys for the
   * credential file and login principal.
   *
   * <p><strong>This is only applicable when running on secure hbase</strong>
   * On regular HBase (without security features), this will safely be ignored.
   * </p>
   *
   * @param conf The configuration data to use
   * @param keytabFileKey Property key used to configure the path to the credential file
   * @param userNameKey Property key used to configure the login principal
   * @param hostname Current hostname to use in any credentials
   * @param loginContextProperty property name to expose the entry name
   * @param loginContextName jaas entry name
   * @throws IOException underlying exception from SecurityUtil.login() call
   */
  private static void login(Configuration conf, String keytabFileKey,
      String userNameKey, String hostname,
      String loginContextProperty, String loginContextName)
      throws IOException {
    if (!isSecureZooKeeper(conf))
      return;

    // User has specified a jaas.conf, keep this one as the good one.
    // HBASE_OPTS="-Djava.security.auth.login.config=jaas.conf"
    if (System.getProperty("java.security.auth.login.config") != null)
      return;

    // No keytab specified, no auth
    String keytabFilename = conf.get(keytabFileKey);
    if (keytabFilename == null) {
      LOG.warn("no keytab specified for: " + keytabFileKey);
      return;
    }

    String principalConfig = conf.get(userNameKey, System.getProperty("user.name"));
    String principalName = SecurityUtil.getServerPrincipal(principalConfig, hostname);

    // Initialize the "jaas.conf" for keyTab/principal,
    // If keyTab is not specified use the Ticket Cache.
    // and set the zookeeper login context name.
    JaasConfiguration jaasConf = new JaasConfiguration(loginContextName,
        principalName, keytabFilename);
    javax.security.auth.login.Configuration.setConfiguration(jaasConf);
    System.setProperty(loginContextProperty, loginContextName);
  }

  /**
   * A JAAS configuration that defines the login modules that we want to use for login.
   */
  private static class JaasConfiguration extends javax.security.auth.login.Configuration {
    private static final String SERVER_KEYTAB_KERBEROS_CONFIG_NAME =
      "zookeeper-server-keytab-kerberos";
    private static final String CLIENT_KEYTAB_KERBEROS_CONFIG_NAME =
      "zookeeper-client-keytab-kerberos";

    private static final Map<String, String> BASIC_JAAS_OPTIONS =
      new HashMap<String,String>();
    static {
      String jaasEnvVar = System.getenv("HBASE_JAAS_DEBUG");
      if (jaasEnvVar != null && "true".equalsIgnoreCase(jaasEnvVar)) {
        BASIC_JAAS_OPTIONS.put("debug", "true");
      }
    }

    private static final Map<String,String> KEYTAB_KERBEROS_OPTIONS =
      new HashMap<String,String>();
    static {
      KEYTAB_KERBEROS_OPTIONS.put("doNotPrompt", "true");
      KEYTAB_KERBEROS_OPTIONS.put("storeKey", "true");
      KEYTAB_KERBEROS_OPTIONS.put("refreshKrb5Config", "true");
      KEYTAB_KERBEROS_OPTIONS.putAll(BASIC_JAAS_OPTIONS);
    }

    private static final AppConfigurationEntry KEYTAB_KERBEROS_LOGIN =
      new AppConfigurationEntry(KerberosUtil.getKrb5LoginModuleName(),
                                LoginModuleControlFlag.REQUIRED,
                                KEYTAB_KERBEROS_OPTIONS);

    private static final AppConfigurationEntry[] KEYTAB_KERBEROS_CONF =
      new AppConfigurationEntry[]{KEYTAB_KERBEROS_LOGIN};

    private javax.security.auth.login.Configuration baseConfig;
    private final String loginContextName;
    private final boolean useTicketCache;
    private final String keytabFile;
    private final String principal;

    public JaasConfiguration(String loginContextName, String principal) {
      this(loginContextName, principal, null, true);
    }

    public JaasConfiguration(String loginContextName, String principal, String keytabFile) {
      this(loginContextName, principal, keytabFile, keytabFile == null || keytabFile.length() == 0);
    }

    private JaasConfiguration(String loginContextName, String principal,
                             String keytabFile, boolean useTicketCache) {
      try {
        this.baseConfig = javax.security.auth.login.Configuration.getConfiguration();
      } catch (SecurityException e) {
        this.baseConfig = null;
      }
      this.loginContextName = loginContextName;
      this.useTicketCache = useTicketCache;
      this.keytabFile = keytabFile;
      this.principal = principal;
      LOG.info("JaasConfiguration loginContextName=" + loginContextName +
               " principal=" + principal + " useTicketCache=" + useTicketCache +
               " keytabFile=" + keytabFile);
    }

    @Override
    public AppConfigurationEntry[] getAppConfigurationEntry(String appName) {
      if (loginContextName.equals(appName)) {
        if (!useTicketCache) {
          KEYTAB_KERBEROS_OPTIONS.put("keyTab", keytabFile);
          KEYTAB_KERBEROS_OPTIONS.put("useKeyTab", "true");
        }
        KEYTAB_KERBEROS_OPTIONS.put("principal", principal);
        KEYTAB_KERBEROS_OPTIONS.put("useTicketCache", useTicketCache ? "true" : "false");
        return KEYTAB_KERBEROS_CONF;
      }
      if (baseConfig != null) return baseConfig.getAppConfigurationEntry(appName);
      return(null);
    }
  }

  //
  // Helper methods
  //

  /**
   * Join the prefix znode name with the suffix znode name to generate a proper
   * full znode name.
   *
   * Assumes prefix does not end with slash and suffix does not begin with it.
   *
   * @param prefix beginning of znode name
   * @param suffix ending of znode name
   * @return result of properly joining prefix with suffix
   */
  public static String joinZNode(String prefix, String suffix) {
    return prefix + ZNODE_PATH_SEPARATOR + suffix;
  }

  /**
   * Returns the full path of the immediate parent of the specified node.
   * @param node path to get parent of
   * @return parent of path, null if passed the root node or an invalid node
   */
  public static String getParent(String node) {
    int idx = node.lastIndexOf(ZNODE_PATH_SEPARATOR);
    return idx <= 0 ? null : node.substring(0, idx);
  }

  /**
   * Get the name of the current node from the specified fully-qualified path.
   * @param path fully-qualified path
   * @return name of the current node
   */
  public static String getNodeName(String path) {
    return path.substring(path.lastIndexOf("/")+1);
  }

  /**
   * Get the key to the ZK ensemble for this configuration without
   * adding a name at the end
   * @param conf Configuration to use to build the key
   * @return ensemble key without a name
   */
  public static String getZooKeeperClusterKey(Configuration conf) {
    return getZooKeeperClusterKey(conf, null);
  }

  /**
   * Get the key to the ZK ensemble for this configuration and append
   * a name at the end
   * @param conf Configuration to use to build the key
   * @param name Name that should be appended at the end if not empty or null
   * @return ensemble key with a name (if any)
   */
  public static String getZooKeeperClusterKey(Configuration conf, String name) {
    String ensemble = conf.get(HConstants.ZOOKEEPER_QUORUM.replaceAll(
        "[\\t\\n\\x0B\\f\\r]", ""));
    StringBuilder builder = new StringBuilder(ensemble);
    builder.append(":");
    builder.append(conf.get(HConstants.ZOOKEEPER_CLIENT_PORT));
    builder.append(":");
    builder.append(conf.get(HConstants.ZOOKEEPER_ZNODE_PARENT));
    if (name != null && !name.isEmpty()) {
      builder.append(",");
      builder.append(name);
    }
    return builder.toString();
  }

  /**
   * Apply the settings in the given key to the given configuration, this is
   * used to communicate with distant clusters
   * @param conf configuration object to configure
   * @param key string that contains the 3 required configuratins
   * @throws IOException
   */
  public static void applyClusterKeyToConf(Configuration conf, String key)
      throws IOException{
    String[] parts = transformClusterKey(key);
    conf.set(HConstants.ZOOKEEPER_QUORUM, parts[0]);
    conf.set(HConstants.ZOOKEEPER_CLIENT_PORT, parts[1]);
    conf.set(HConstants.ZOOKEEPER_ZNODE_PARENT, parts[2]);
  }

  /**
   * Separate the given key into the three configurations it should contain:
   * hbase.zookeeper.quorum, hbase.zookeeper.client.port
   * and zookeeper.znode.parent
   * @param key
   * @return the three configuration in the described order
   * @throws IOException
   */
  public static String[] transformClusterKey(String key) throws IOException {
    String[] parts = key.split(":");
    if (parts.length != 3) {
      throw new IOException("Cluster key invalid, the format should be:" +
          HConstants.ZOOKEEPER_QUORUM + ":hbase.zookeeper.client.port:"
          + HConstants.ZOOKEEPER_ZNODE_PARENT);
    }
    return parts;
  }

  //
  // Existence checks and watches
  //

  /**
   * Watch the specified znode for delete/create/change events.  The watcher is
   * set whether or not the node exists.  If the node already exists, the method
   * returns true.  If the node does not exist, the method returns false.
   *
   * @param zkw zk reference
   * @param znode path of node to watch
   * @return true if znode exists, false if does not exist or error
   * @throws KeeperException if unexpected zookeeper exception
   */
  public static boolean watchAndCheckExists(ZooKeeperWatcher zkw, String znode)
  throws KeeperException {
    try {
      Stat s = zkw.getRecoverableZooKeeper().exists(znode, zkw);
      boolean exists = s != null ? true : false;
      if (exists) {
        LOG.debug(zkw.prefix("Set watcher on existing znode " + znode));
      } else {
        LOG.debug(zkw.prefix(znode+" does not exist. Watcher is set."));
      }
      return exists;
    } catch (KeeperException e) {
      LOG.warn(zkw.prefix("Unable to set watcher on znode " + znode), e);
      zkw.keeperException(e);
      return false;
    } catch (InterruptedException e) {
      LOG.warn(zkw.prefix("Unable to set watcher on znode " + znode), e);
      zkw.interruptedException(e);
      return false;
    }
  }

  /**
   * Check if the specified node exists.  Sets no watches.
   *
   * @param zkw zk reference
   * @param znode path of node to watch
   * @return version of the node if it exists, -1 if does not exist
   * @throws KeeperException if unexpected zookeeper exception
   */
  public static int checkExists(ZooKeeperWatcher zkw, String znode)
  throws KeeperException {
    try {
      Stat s = zkw.getRecoverableZooKeeper().exists(znode, null);
      return s != null ? s.getVersion() : -1;
    } catch (KeeperException e) {
      LOG.warn(zkw.prefix("Unable to set watcher on znode (" + znode + ")"), e);
      zkw.keeperException(e);
      return -1;
    } catch (InterruptedException e) {
      LOG.warn(zkw.prefix("Unable to set watcher on znode (" + znode + ")"), e);
      zkw.interruptedException(e);
      return -1;
    }
  }

  //
  // Znode listings
  //

  /**
   * Lists the children znodes of the specified znode.  Also sets a watch on
   * the specified znode which will capture a NodeDeleted event on the specified
   * znode as well as NodeChildrenChanged if any children of the specified znode
   * are created or deleted.
   *
   * Returns null if the specified node does not exist.  Otherwise returns a
   * list of children of the specified node.  If the node exists but it has no
   * children, an empty list will be returned.
   *
   * @param zkw zk reference
   * @param znode path of node to list and watch children of
   * @return list of children of the specified node, an empty list if the node
   *          exists but has no children, and null if the node does not exist
   * @throws KeeperException if unexpected zookeeper exception
   */
  public static List<String> listChildrenAndWatchForNewChildren(
      ZooKeeperWatcher zkw, String znode)
  throws KeeperException {
    try {
      List<String> children = zkw.getRecoverableZooKeeper().getChildren(znode, zkw);
      return children;
    } catch(KeeperException.NoNodeException ke) {
      LOG.debug(zkw.prefix("Unable to list children of znode " + znode + " " +
          "because node does not exist (not an error)"));
      return null;
    } catch (KeeperException e) {
      LOG.warn(zkw.prefix("Unable to list children of znode " + znode + " "), e);
      zkw.keeperException(e);
      return null;
    } catch (InterruptedException e) {
      LOG.warn(zkw.prefix("Unable to list children of znode " + znode + " "), e);
      zkw.interruptedException(e);
      return null;
    }
  }

  /**
   * List all the children of the specified znode, setting a watch for children
   * changes and also setting a watch on every individual child in order to get
   * the NodeCreated and NodeDeleted events.
   * @param zkw zookeeper reference
   * @param znode node to get children of and watch
   * @return list of znode names, null if the node doesn't exist
   * @throws KeeperException
   */
  public static List<String> listChildrenAndWatchThem(ZooKeeperWatcher zkw,
      String znode) throws KeeperException {
    List<String> children = listChildrenAndWatchForNewChildren(zkw, znode);
    if (children == null) {
      return null;
    }
    for (String child : children) {
      watchAndCheckExists(zkw, joinZNode(znode, child));
    }
    return children;
  }

  /**
   * Lists the children of the specified znode without setting any watches.
   *
   * Used to list the currently online regionservers and their addresses.
   *
   * Sets no watches at all, this method is best effort.
   *
   * Returns an empty list if the node has no children.  Returns null if the
   * parent node itself does not exist.
   *
   * @param zkw zookeeper reference
   * @param znode node to get children of as addresses
   * @return list of data of children of specified znode, empty if no children,
   *         null if parent does not exist
   * @throws KeeperException if unexpected zookeeper exception
   */
  public static List<String> listChildrenNoWatch(
      ZooKeeperWatcher zkw, String znode)
  throws KeeperException {
    List<String> children = null;
    try {
      // List the children without watching
      children = zkw.getRecoverableZooKeeper().getChildren(znode, null);
    } catch(KeeperException.NoNodeException nne) {
      return null;
    } catch(InterruptedException ie) {
      zkw.interruptedException(ie);
    }
    return children;
  }

  /**
   * Simple class to hold a node path and node data.
   */
  public static class NodeAndData {
    private String node;
    private byte [] data;
    public NodeAndData(String node, byte [] data) {
      this.node = node;
      this.data = data;
    }
    public String getNode() {
      return node;
    }
    public byte [] getData() {
      return data;
    }
    @Override
    public String toString() {
      return node + " (" + RegionTransitionData.fromBytes(data) + ")";
    }
    public boolean isEmpty() {
      return (data.length == 0);
    }
  }

  /**
   * Checks if the specified znode has any children.  Sets no watches.
   *
   * Returns true if the node exists and has children.  Returns false if the
   * node does not exist or if the node does not have any children.
   *
   * Used during master initialization to determine if the master is a
   * failed-over-to master or the first master during initial cluster startup.
   * If the directory for regionserver ephemeral nodes is empty then this is
   * a cluster startup, if not then it is not cluster startup.
   *
   * @param zkw zk reference
   * @param znode path of node to check for children of
   * @return true if node has children, false if not or node does not exist
   * @throws KeeperException if unexpected zookeeper exception
   */
  public static boolean nodeHasChildren(ZooKeeperWatcher zkw, String znode)
  throws KeeperException {
    try {
      return !zkw.getRecoverableZooKeeper().getChildren(znode, null).isEmpty();
    } catch(KeeperException.NoNodeException ke) {
      LOG.debug(zkw.prefix("Unable to list children of znode " + znode + " " +
      "because node does not exist (not an error)"));
      return false;
    } catch (KeeperException e) {
      LOG.warn(zkw.prefix("Unable to list children of znode " + znode), e);
      zkw.keeperException(e);
      return false;
    } catch (InterruptedException e) {
      LOG.warn(zkw.prefix("Unable to list children of znode " + znode), e);
      zkw.interruptedException(e);
      return false;
    }
  }

  /**
   * Get the number of children of the specified node.
   *
   * If the node does not exist or has no children, returns 0.
   *
   * Sets no watches at all.
   *
   * @param zkw zk reference
   * @param znode path of node to count children of
   * @return number of children of specified node, 0 if none or parent does not
   *         exist
   * @throws KeeperException if unexpected zookeeper exception
   */
  public static int getNumberOfChildren(ZooKeeperWatcher zkw, String znode)
  throws KeeperException {
    try {
      Stat stat = zkw.getRecoverableZooKeeper().exists(znode, null);
      return stat == null ? 0 : stat.getNumChildren();
    } catch(KeeperException e) {
      LOG.warn(zkw.prefix("Unable to get children of node " + znode));
      zkw.keeperException(e);
    } catch(InterruptedException e) {
      zkw.interruptedException(e);
    }
    return 0;
  }

  //
  // Data retrieval
  //

  /**
   * Get znode data. Does not set a watcher.
   * @return ZNode data
   */
  public static byte [] getData(ZooKeeperWatcher zkw, String znode)
  throws KeeperException {
    try {
      byte [] data = zkw.getRecoverableZooKeeper().getData(znode, null, null);
      logRetrievedMsg(zkw, znode, data, false);
      return data;
    } catch (KeeperException.NoNodeException e) {
      LOG.debug(zkw.prefix("Unable to get data of znode " + znode + " " +
        "because node does not exist (not an error)"));
      return null;
    } catch (KeeperException e) {
      LOG.warn(zkw.prefix("Unable to get data of znode " + znode), e);
      zkw.keeperException(e);
      return null;
    } catch (InterruptedException e) {
      LOG.warn(zkw.prefix("Unable to get data of znode " + znode), e);
      zkw.interruptedException(e);
      return null;
    }
  }

  /**
   * Get the data at the specified znode and set a watch.
   *
   * Returns the data and sets a watch if the node exists.  Returns null and no
   * watch is set if the node does not exist or there is an exception.
   *
   * @param zkw zk reference
   * @param znode path of node
   * @return data of the specified znode, or null
   * @throws KeeperException if unexpected zookeeper exception
   */
  public static byte [] getDataAndWatch(ZooKeeperWatcher zkw, String znode)
  throws KeeperException {
    return getDataInternal(zkw, znode, null, true);
  }

  /**
   * Get the data at the specified znode and set a watch.
   *
   * Returns the data and sets a watch if the node exists.  Returns null and no
   * watch is set if the node does not exist or there is an exception.
   *
   * @param zkw zk reference
   * @param znode path of node
   * @param stat object to populate the version of the znode
   * @return data of the specified znode, or null
   * @throws KeeperException if unexpected zookeeper exception
   */
  public static byte[] getDataAndWatch(ZooKeeperWatcher zkw, String znode,
      Stat stat) throws KeeperException {
    return getDataInternal(zkw, znode, stat, true);
  }

  private static byte[] getDataInternal(ZooKeeperWatcher zkw, String znode, Stat stat,
      boolean watcherSet)
      throws KeeperException {
    try {
      byte [] data = zkw.getRecoverableZooKeeper().getData(znode, zkw, stat);
      logRetrievedMsg(zkw, znode, data, watcherSet);
      return data;
    } catch (KeeperException.NoNodeException e) {
      LOG.debug(zkw.prefix("Unable to get data of znode " + znode + " " +
        "because node does not exist (not an error)"));
      return null;
    } catch (KeeperException e) {
      LOG.warn(zkw.prefix("Unable to get data of znode " + znode), e);
      zkw.keeperException(e);
      return null;
    } catch (InterruptedException e) {
      LOG.warn(zkw.prefix("Unable to get data of znode " + znode), e);
      zkw.interruptedException(e);
      return null;
    }
  }

  /**
   * Get the data at the specified znode without setting a watch.
   *
   * Returns the data if the node exists.  Returns null if the node does not
   * exist.
   *
   * Sets the stats of the node in the passed Stat object.  Pass a null stat if
   * not interested.
   *
   * @param zkw zk reference
   * @param znode path of node
   * @param stat node status to get if node exists
   * @return data of the specified znode, or null if node does not exist
   * @throws KeeperException if unexpected zookeeper exception
   */
  public static byte [] getDataNoWatch(ZooKeeperWatcher zkw, String znode,
      Stat stat)
  throws KeeperException {
    try {
      byte [] data = zkw.getRecoverableZooKeeper().getData(znode, null, stat);
      logRetrievedMsg(zkw, znode, data, false);
      return data;
    } catch (KeeperException.NoNodeException e) {
      LOG.debug(zkw.prefix("Unable to get data of znode " + znode + " " +
          "because node does not exist (not necessarily an error)"));
      return null;
    } catch (KeeperException e) {
      LOG.warn(zkw.prefix("Unable to get data of znode " + znode), e);
      zkw.keeperException(e);
      return null;
    } catch (InterruptedException e) {
      LOG.warn(zkw.prefix("Unable to get data of znode " + znode), e);
      zkw.interruptedException(e);
      return null;
    }
  }

  /**
   * Returns the date of child znodes of the specified znode.  Also sets a watch on
   * the specified znode which will capture a NodeDeleted event on the specified
   * znode as well as NodeChildrenChanged if any children of the specified znode
   * are created or deleted.
   *
   * Returns null if the specified node does not exist.  Otherwise returns a
   * list of children of the specified node.  If the node exists but it has no
   * children, an empty list will be returned.
   *
   * @param zkw zk reference
   * @param baseNode path of node to list and watch children of
   * @return list of data of children of the specified node, an empty list if the node
   *          exists but has no children, and null if the node does not exist
   * @throws KeeperException if unexpected zookeeper exception
   */
  public static List<NodeAndData> getChildDataAndWatchForNewChildren(
      ZooKeeperWatcher zkw, String baseNode) throws KeeperException {
    List<String> nodes =
      ZKUtil.listChildrenAndWatchForNewChildren(zkw, baseNode);
    List<NodeAndData> newNodes = new ArrayList<NodeAndData>();
    if (nodes != null) {
      for (String node : nodes) {
        String nodePath = ZKUtil.joinZNode(baseNode, node);
        byte[] data = ZKUtil.getDataAndWatch(zkw, nodePath);
        newNodes.add(new NodeAndData(nodePath, data));
      }
    }
    return newNodes;
  }

  /**
   * Update the data of an existing node with the expected version to have the
   * specified data.
   *
   * Throws an exception if there is a version mismatch or some other problem.
   *
   * Sets no watches under any conditions.
   *
   * @param zkw zk reference
   * @param znode
   * @param data
   * @param expectedVersion
   * @throws KeeperException if unexpected zookeeper exception
   * @throws KeeperException.BadVersionException if version mismatch
   */
  public static void updateExistingNodeData(ZooKeeperWatcher zkw, String znode,
      byte [] data, int expectedVersion)
  throws KeeperException {
    try {
      zkw.getRecoverableZooKeeper().setData(znode, data, expectedVersion);
    } catch(InterruptedException ie) {
      zkw.interruptedException(ie);
    }
  }

  //
  // Data setting
  //

  /**
   * Sets the data of the existing znode to be the specified data.  Ensures that
   * the current data has the specified expected version.
   *
   * <p>If the node does not exist, a {@link NoNodeException} will be thrown.
   *
   * <p>If their is a version mismatch, method returns null.
   *
   * <p>No watches are set but setting data will trigger other watchers of this
   * node.
   *
   * <p>If there is another problem, a KeeperException will be thrown.
   *
   * @param zkw zk reference
   * @param znode path of node
   * @param data data to set for node
   * @param expectedVersion version expected when setting data
   * @return true if data set, false if version mismatch
   * @throws KeeperException if unexpected zookeeper exception
   */
  public static boolean setData(ZooKeeperWatcher zkw, String znode,
      byte [] data, int expectedVersion)
  throws KeeperException, KeeperException.NoNodeException {
    try {
      return zkw.getRecoverableZooKeeper().setData(znode, data, expectedVersion) != null;
    } catch (InterruptedException e) {
      zkw.interruptedException(e);
      return false;
    }
  }

  /**
   * Set data into node creating node if it doesn't yet exist.
   * Does not set watch.
   *
   * WARNING: this is not atomic -- it is possible to get a 0-byte data value in the znode before
   * data is written
   *
   * @param zkw zk reference
   * @param znode path of node
   * @param data data to set for node
   * @throws KeeperException
   */
  public static void createSetData(final ZooKeeperWatcher zkw, final String znode,
      final byte [] data)
  throws KeeperException {
    if (checkExists(zkw, znode) == -1) {
      ZKUtil.createWithParents(zkw, znode);
    }
    ZKUtil.setData(zkw, znode, data);
  }

  /**
   * Sets the data of the existing znode to be the specified data.  The node
   * must exist but no checks are done on the existing data or version.
   *
   * <p>If the node does not exist, a {@link NoNodeException} will be thrown.
   *
   * <p>No watches are set but setting data will trigger other watchers of this
   * node.
   *
   * <p>If there is another problem, a KeeperException will be thrown.
   *
   * @param zkw zk reference
   * @param znode path of node
   * @param data data to set for node
   * @throws KeeperException if unexpected zookeeper exception
   */
  public static void setData(ZooKeeperWatcher zkw, String znode, byte [] data)
  throws KeeperException, KeeperException.NoNodeException {
    setData(zkw, (SetData)ZKUtilOp.setData(znode, data));
  }

  private static void setData(ZooKeeperWatcher zkw, SetData setData)
  throws KeeperException, KeeperException.NoNodeException {
    SetDataRequest sd = (SetDataRequest)toZooKeeperOp(zkw, setData).toRequestRecord();
    setData(zkw, sd.getPath(), sd.getData(), sd.getVersion());
  }

  /**
   * Returns whether or not secure authentication is enabled
   * (whether <code>hbase.security.authentication</code> is set to
   * <code>kerberos</code>.
   */
  public static boolean isSecureZooKeeper(Configuration conf) {
    // hbase shell need to use:
    //    -Djava.security.auth.login.config=user-jaas.conf
    // since each user has a different jaas.conf
    if (System.getProperty("java.security.auth.login.config") != null)
      return true;

    // Master & RSs uses hbase.zookeeper.client.*
    return("kerberos".equalsIgnoreCase(conf.get("hbase.security.authentication")) &&
         conf.get("hbase.zookeeper.client.keytab.file") != null);
  }

  private static ArrayList<ACL> createACL(ZooKeeperWatcher zkw, String node) {
    if (isSecureZooKeeper(zkw.getConfiguration())) {
      // Certain znodes are accessed directly by the client,
      // so they must be readable by non-authenticated clients
      if ((node.equals(zkw.baseZNode) == true) ||
          (node.equals(zkw.rootServerZNode) == true) ||
          (node.equals(zkw.masterAddressZNode) == true) ||
          (node.equals(zkw.clusterIdZNode) == true) ||
          (node.equals(zkw.rsZNode) == true) ||
          (node.equals(zkw.backupMasterAddressesZNode) == true) ||
          (node.startsWith(zkw.assignmentZNode) == true) ||
          (node.startsWith(zkw.masterTableZNode) == true) ||
          (node.startsWith(zkw.masterTableZNode92) == true)) {
        return ZooKeeperWatcher.CREATOR_ALL_AND_WORLD_READABLE;
      }
      return Ids.CREATOR_ALL_ACL;
    } else {
      return Ids.OPEN_ACL_UNSAFE;
    }
  }

  //
  // Node creation
  //

  /**
   *
   * Set the specified znode to be an ephemeral node carrying the specified
   * data.
   *
   * If the node is created successfully, a watcher is also set on the node.
   *
   * If the node is not created successfully because it already exists, this
   * method will also set a watcher on the node.
   *
   * If there is another problem, a KeeperException will be thrown.
   *
   * @param zkw zk reference
   * @param znode path of node
   * @param data data of node
   * @return true if node created, false if not, watch set in both cases
   * @throws KeeperException if unexpected zookeeper exception
   */
  public static boolean createEphemeralNodeAndWatch(ZooKeeperWatcher zkw,
      String znode, byte [] data)
  throws KeeperException {
    try {
      zkw.getRecoverableZooKeeper().create(znode, data, createACL(zkw, znode),
          CreateMode.EPHEMERAL);
    } catch (KeeperException.NodeExistsException nee) {
      if(!watchAndCheckExists(zkw, znode)) {
        // It did exist but now it doesn't, try again
        return createEphemeralNodeAndWatch(zkw, znode, data);
      }
      return false;
    } catch (InterruptedException e) {
      LOG.info("Interrupted", e);
      Thread.currentThread().interrupt();
    }
    return true;
  }

  /**
   * Creates the specified znode to be a persistent node carrying the specified
   * data.
   *
   * Returns true if the node was successfully created, false if the node
   * already existed.
   *
   * If the node is created successfully, a watcher is also set on the node.
   *
   * If the node is not created successfully because it already exists, this
   * method will also set a watcher on the node but return false.
   *
   * If there is another problem, a KeeperException will be thrown.
   *
   * @param zkw zk reference
   * @param znode path of node
   * @param data data of node
   * @return true if node created, false if not, watch set in both cases
   * @throws KeeperException if unexpected zookeeper exception
   */
  public static boolean createNodeIfNotExistsAndWatch(
      ZooKeeperWatcher zkw, String znode, byte [] data)
  throws KeeperException {
    try {
      zkw.getRecoverableZooKeeper().create(znode, data, createACL(zkw, znode),
          CreateMode.PERSISTENT);
    } catch (KeeperException.NodeExistsException nee) {
      try {
        zkw.getRecoverableZooKeeper().exists(znode, zkw);
      } catch (InterruptedException e) {
        zkw.interruptedException(e);
        return false;
      }
      return false;
    } catch (InterruptedException e) {
      zkw.interruptedException(e);
      return false;
    }
    return true;
  }

  /**
   * Creates the specified node with the specified data and watches it.
   *
   * <p>Throws an exception if the node already exists.
   *
   * <p>The node created is persistent and open access.
   *
   * <p>Returns the version number of the created node if successful.
   *
   * @param zkw zk reference
   * @param znode path of node to create
   * @param data data of node to create
   * @return version of node created
   * @throws KeeperException if unexpected zookeeper exception
   * @throws KeeperException.NodeExistsException if node already exists
   */
  public static int createAndWatch(ZooKeeperWatcher zkw,
      String znode, byte [] data)
  throws KeeperException, KeeperException.NodeExistsException {
    try {
      zkw.getRecoverableZooKeeper().create(znode, data, createACL(zkw, znode),
          CreateMode.PERSISTENT);
      return zkw.getRecoverableZooKeeper().exists(znode, zkw).getVersion();
    } catch (InterruptedException e) {
      zkw.interruptedException(e);
      return -1;
    }
  }

  /**
   * Async creates the specified node with the specified data.
   *
   * <p>Throws an exception if the node already exists.
   *
   * <p>The node created is persistent and open access.
   *
   * @param zkw zk reference
   * @param znode path of node to create
   * @param data data of node to create
   * @param cb
   * @param ctx
   * @throws KeeperException if unexpected zookeeper exception
   * @throws KeeperException.NodeExistsException if node already exists
   */
  public static void asyncCreate(ZooKeeperWatcher zkw,
      String znode, byte [] data, final AsyncCallback.StringCallback cb,
      final Object ctx) {
    zkw.getRecoverableZooKeeper().getZooKeeper().create(znode, data,
        createACL(zkw, znode), CreateMode.PERSISTENT, cb, ctx);
  }

  /**
   * Creates the specified node, iff the node does not exist.  Does not set a
   * watch and fails silently if the node already exists.
   *
   * The node created is persistent and open access.
   *
   * @param zkw zk reference
   * @param znode path of node
   * @throws KeeperException if unexpected zookeeper exception
   */
  public static void createAndFailSilent(ZooKeeperWatcher zkw,
      String znode) throws KeeperException {
    createAndFailSilent(zkw, znode, new byte[0]);
  }

  /**
   * Creates the specified node containing specified data, iff the node does not exist.  Does
   * not set a watch and fails silently if the node already exists.
   *
   * The node created is persistent and open access.
   *
   * @param zkw zk reference
   * @param znode path of node
   * @param data a byte array data to store in the znode
   * @throws KeeperException if unexpected zookeeper exception
   */
  public static void createAndFailSilent(ZooKeeperWatcher zkw,
      String znode, byte[] data) throws KeeperException {
    createAndFailSilent(zkw,
      (CreateAndFailSilent)ZKUtilOp.createAndFailSilent(znode, data));
  }

  private static void createAndFailSilent(ZooKeeperWatcher zkw, CreateAndFailSilent cafs)
  throws KeeperException {
    CreateRequest create = (CreateRequest)toZooKeeperOp(zkw, cafs).toRequestRecord();
    String znode = create.getPath();
    try {
      RecoverableZooKeeper zk = zkw.getRecoverableZooKeeper();
      if (zk.exists(znode, false) == null) {
        zk.create(znode, create.getData(), create.getAcl(), CreateMode.fromFlag(create.getFlags()));
      }
    } catch(KeeperException.NodeExistsException nee) {
    } catch(KeeperException.NoAuthException nee){
      try {
        if (null == zkw.getRecoverableZooKeeper().exists(znode, false)) {
          // If we failed to create the file and it does not already exist.
          throw(nee);
        }
      } catch (InterruptedException ie) {
        zkw.interruptedException(ie);
      }

    } catch(InterruptedException ie) {
      zkw.interruptedException(ie);
    }
  }

  /**
   * Creates the specified node and all parent nodes required for it to exist.
   *
   * No watches are set and no errors are thrown if the node already exists.
   *
   * The nodes created are persistent and open access.
   *
   * @param zkw zk reference
   * @param znode path of node
   * @throws KeeperException if unexpected zookeeper exception
   */
  public static void createWithParents(ZooKeeperWatcher zkw, String znode)
  throws KeeperException {
    createWithParents(zkw, znode, new byte[0]);
  }

  /**
   * Creates the specified node and all parent nodes required for it to exist.  The creation of
   * parent znodes is not atomic with the leafe znode creation but the data is written atomically
   * when the leaf node is created.
   *
   * No watches are set and no errors are thrown if the node already exists.
   *
   * The nodes created are persistent and open access.
   *
   * @param zkw zk reference
   * @param znode path of node
   * @throws KeeperException if unexpected zookeeper exception
   */
  public static void createWithParents(ZooKeeperWatcher zkw, String znode, byte[] data)
  throws KeeperException {
    try {
      if(znode == null) {
        return;
      }
      zkw.getRecoverableZooKeeper().create(znode, data, createACL(zkw, znode),
          CreateMode.PERSISTENT);
    } catch(KeeperException.NodeExistsException nee) {
      return;
    } catch(KeeperException.NoNodeException nne) {
      createWithParents(zkw, getParent(znode));
      createWithParents(zkw, znode);
    } catch(InterruptedException ie) {
      zkw.interruptedException(ie);
    }
  }

  //
  // Deletes
  //

  /**
   * Delete the specified node.  Sets no watches.  Throws all exceptions.
   */
  public static void deleteNode(ZooKeeperWatcher zkw, String node)
  throws KeeperException {
    deleteNode(zkw, node, -1);
  }

  /**
   * Delete the specified node with the specified version.  Sets no watches.
   * Throws all exceptions.
   */
  public static boolean deleteNode(ZooKeeperWatcher zkw, String node,
      int version)
  throws KeeperException {
    try {
      zkw.getRecoverableZooKeeper().delete(node, version);
      return true;
    } catch(KeeperException.BadVersionException bve) {
      return false;
    } catch(InterruptedException ie) {
      zkw.interruptedException(ie);
      return false;
    }
  }

  /**
   * Deletes the specified node.  Fails silent if the node does not exist.
   * @param zkw
   * @param node
   * @throws KeeperException
   */
  public static void deleteNodeFailSilent(ZooKeeperWatcher zkw, String node)
  throws KeeperException {
    deleteNodeFailSilent(zkw,
      (DeleteNodeFailSilent)ZKUtilOp.deleteNodeFailSilent(node));
  }

  private static void deleteNodeFailSilent(ZooKeeperWatcher zkw,
      DeleteNodeFailSilent dnfs) throws KeeperException {
    DeleteRequest delete = (DeleteRequest)toZooKeeperOp(zkw, dnfs).toRequestRecord();
    try {
      zkw.getRecoverableZooKeeper().delete(delete.getPath(), delete.getVersion());
    } catch(KeeperException.NoNodeException nne) {
    } catch(InterruptedException ie) {
      zkw.interruptedException(ie);
    }
  }

  /**
   * Delete the specified node and all of it's children.
   * <p>
   * If the node does not exist, just returns.
   * <p>
   * Sets no watches. Throws all exceptions besides dealing with deletion of
   * children.
   */
  public static void deleteNodeRecursively(ZooKeeperWatcher zkw, String node)
  throws KeeperException {
    try {
      List<String> children = ZKUtil.listChildrenNoWatch(zkw, node);
      // the node is already deleted, so we just finish
      if (children == null) return;

      if(!children.isEmpty()) {
        for(String child : children) {
          deleteNodeRecursively(zkw, joinZNode(node, child));
        }
      }
      zkw.getRecoverableZooKeeper().delete(node, -1);
    } catch(InterruptedException ie) {
      zkw.interruptedException(ie);
    }
  }

  /**
   * Delete all the children of the specified node but not the node itself.
   *
   * Sets no watches.  Throws all exceptions besides dealing with deletion of
   * children.
   */
  public static void deleteChildrenRecursively(ZooKeeperWatcher zkw, String node)
  throws KeeperException {
    List<String> children = ZKUtil.listChildrenNoWatch(zkw, node);
    if (children == null || children.isEmpty()) return;
    for(String child : children) {
      deleteNodeRecursively(zkw, joinZNode(node, child));
    }
  }

  /**
   * Represents an action taken by ZKUtil, e.g. createAndFailSilent.
   * These actions are higher-level than {@link ZKOp} actions, which represent
   * individual actions in the ZooKeeper API, like create.
   */
  public abstract static class ZKUtilOp {
    private String path;

    private ZKUtilOp(String path) {
      this.path = path;
    }

    /**
     * @return a createAndFailSilent ZKUtilOp
     */
    public static ZKUtilOp createAndFailSilent(String path, byte[] data) {
      return new CreateAndFailSilent(path, data);
    }

    /**
     * @return a deleteNodeFailSilent ZKUtilOP
     */
    public static ZKUtilOp deleteNodeFailSilent(String path) {
      return new DeleteNodeFailSilent(path);
    }

    /**
     * @return a setData ZKUtilOp
     */
    public static ZKUtilOp setData(String path, byte [] data) {
      return new SetData(path, data);
    }

    /**
     * @return path to znode where the ZKOp will occur
     */
    public String getPath() {
      return path;
    }

    /**
     * ZKUtilOp representing createAndFailSilent in ZooKeeper
     * (attempt to create node, ignore error if already exists)
     */
    public static class CreateAndFailSilent extends ZKUtilOp {
      private byte [] data;

      private CreateAndFailSilent(String path, byte [] data) {
        super(path);
        this.data = data;
      }

      public byte[] getData() {
        return data;
      }

      @Override
      public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CreateAndFailSilent)) return false;

        CreateAndFailSilent op = (CreateAndFailSilent) o;
        return getPath().equals(op.getPath()) && Arrays.equals(data, op.data);
      }
    }

    /**
     * ZKUtilOp representing deleteNodeFailSilent in ZooKeeper
     * (attempt to delete node, ignore error if node doesn't exist)
     */
    public static class DeleteNodeFailSilent extends ZKUtilOp {
      private DeleteNodeFailSilent(String path) {
        super(path);
      }

      @Override
      public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DeleteNodeFailSilent)) return false;

        return super.equals(o);
      }
    }

    /**
     * @return ZKUtilOp representing setData in ZooKeeper
     */
    public static class SetData extends ZKUtilOp {
      private byte [] data;

      private SetData(String path, byte [] data) {
        super(path);
        this.data = data;
      }

      public byte[] getData() {
        return data;
      }

      @Override
      public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SetData)) return false;

        SetData op = (SetData) o;
        return getPath().equals(op.getPath()) && Arrays.equals(data, op.data);
      }
    }
  }

  /**
   * Convert from ZKUtilOp to ZKOp
   */
  private static Op toZooKeeperOp(ZooKeeperWatcher zkw, ZKUtilOp op)
  throws UnsupportedOperationException {
    if(op == null) return null;

    if (op instanceof CreateAndFailSilent) {
      CreateAndFailSilent cafs = (CreateAndFailSilent)op;
      return Op.create(cafs.getPath(), cafs.getData(), createACL(zkw, cafs.getPath()),
        CreateMode.PERSISTENT);
    } else if (op instanceof DeleteNodeFailSilent) {
      DeleteNodeFailSilent dnfs = (DeleteNodeFailSilent)op;
      return Op.delete(dnfs.getPath(), -1);
    } else if (op instanceof SetData) {
      SetData sd = (SetData)op;
      return Op.setData(sd.getPath(), sd.getData(), -1);
    } else {
      throw new UnsupportedOperationException("Unexpected ZKUtilOp type: "
        + op.getClass().getName());
    }
  }

  /**
   * If hbase.zookeeper.useMulti is true, use ZooKeeper's multi-update functionality.
   * Otherwise, run the list of operations sequentially.
   *
   * If all of the following are true:
   * - runSequentialOnMultiFailure is true
   * - hbase.zookeeper.useMulti is true
   * - on calling multi, we get a ZooKeeper exception that can be handled by a sequential call(*)
   * Then:
   * - we retry the operations one-by-one (sequentially)
   *
   * Note *: an example is receiving a NodeExistsException from a "create" call.  Without multi,
   * a user could call "createAndFailSilent" to ensure that a node exists if they don't care who
   * actually created the node (i.e. the NodeExistsException from ZooKeeper is caught).
   * This will cause all operations in the multi to fail, however, because
   * the NodeExistsException that zk.create throws will fail the multi transaction.
   * In this case, if the previous conditions hold, the commands are run sequentially, which should
   * result in the correct final state, but means that the operations will not run atomically.
   *
   * @throws KeeperException
   */
  public static void multiOrSequential(ZooKeeperWatcher zkw, List<ZKUtilOp> ops,
      boolean runSequentialOnMultiFailure) throws KeeperException {
    if (ops == null) return;
    // NOTE: this and future versions of HBase are only supported to work with
    // versions of ZooKeeper with multi support (CDH4+), so it is safe to use
    // ZK.multi.
    boolean useMulti = zkw.getConfiguration().getBoolean(HConstants.ZOOKEEPER_USEMULTI, true);

    if (useMulti) {
      List<Op> zkOps = new LinkedList<Op>();
      for (ZKUtilOp op : ops) {
        zkOps.add(toZooKeeperOp(zkw, op));
      }
      try {
        zkw.getRecoverableZooKeeper().multi(zkOps);
      } catch (KeeperException ke) {
       switch (ke.code()) {
         case NODEEXISTS:
         case NONODE:
         case BADVERSION:
         case NOAUTH:
           // if we get an exception that could be solved by running sequentially
           // (and the client asked us to), then break out and run sequentially
           if (runSequentialOnMultiFailure) {
             LOG.info("On call to ZK.multi, received exception: " + ke.toString() + "."
               + "  Attempting to run operations sequentially because"
               + " runSequentialOnMultiFailure is: " + runSequentialOnMultiFailure + ".");
             processSequentially(zkw, ops);
             break;
           }
          default:
            throw ke;
        }
      } catch (InterruptedException ie) {
        zkw.interruptedException(ie);
      }
    } else {
      // run sequentially
      processSequentially(zkw, ops);
    }
  }

  private static void processSequentially(ZooKeeperWatcher zkw, List<ZKUtilOp> ops)
      throws KeeperException, NoNodeException {
    for (ZKUtilOp op : ops) {
      if (op instanceof CreateAndFailSilent) {
        createAndFailSilent(zkw, (CreateAndFailSilent) op);
      } else if (op instanceof DeleteNodeFailSilent) {
        deleteNodeFailSilent(zkw, (DeleteNodeFailSilent) op);
      } else if (op instanceof SetData) {
        setData(zkw, (SetData) op);
      } else {
        throw new UnsupportedOperationException("Unexpected ZKUtilOp type: "
            + op.getClass().getName());
      }
    }
  }

  //
  // ZooKeeper cluster information
  //

  /** @return String dump of everything in ZooKeeper. */
  public static String dump(ZooKeeperWatcher zkw) {
    StringBuilder sb = new StringBuilder();
    try {
      sb.append("HBase is rooted at ").append(zkw.baseZNode);
      sb.append("\nActive master address: ").append(
          ServerName.parseVersionedServerName(getData(zkw, zkw.masterAddressZNode)));
      sb.append("\nBackup master addresses:");
      for (String child : listChildrenNoWatch(zkw,
                                              zkw.backupMasterAddressesZNode)) {
        sb.append("\n ").append(child);
      }
      sb.append("\nRegion server holding ROOT: ").append(
          Bytes.toStringBinary(getData(zkw, zkw.rootServerZNode)));
      sb.append("\nRegion servers:");
      for (String child : listChildrenNoWatch(zkw, zkw.rsZNode)) {
        sb.append("\n ").append(child);
      }
      try {
        getReplicationZnodesDump(zkw, sb);
      } catch (KeeperException ke) {
        LOG.warn("Couldn't get the replication znode dump." + ke.getStackTrace());
      }
      sb.append("\nQuorum Server Statistics:");
      String[] servers = zkw.getQuorum().split(",");
      for (String server : servers) {
        sb.append("\n ").append(server);
        try {
          String[] stat = getServerStats(server, ZKUtil.zkDumpConnectionTimeOut);

          if (stat == null) {
            sb.append("[Error] invalid quorum server: " + server);
            break;
          }

          for (String s : stat) {
            sb.append("\n  ").append(s);
          }
        } catch (Exception e) {
          sb.append("\n  ERROR: ").append(e.getMessage());
        }
      }
    } catch (KeeperException ke) {
      sb.append("\nFATAL ZooKeeper Exception!\n");
      sb.append("\n" + ke.getMessage());
    }
    return sb.toString();
  }

  private static void getReplicationZnodesDump(ZooKeeperWatcher zkw, StringBuilder sb)
      throws KeeperException {
    String replicationZNodeName = zkw.getConfiguration().get("zookeeper.znode.replication",
      "replication");
    String replicationZnode = joinZNode(zkw.baseZNode, replicationZNodeName);
    if (ZKUtil.checkExists(zkw, replicationZnode) == -1)
      return;
    // do a ls -r on this znode
    List<String> stack = new LinkedList<String>();
    stack.add(replicationZnode);
    do {
      String znodeToProcess = stack.remove(stack.size() - 1);
      sb.append("\n").append(znodeToProcess).append(": ")
          .append(Bytes.toString(ZKUtil.getData(zkw, znodeToProcess)));
      for (String zNodeChild : ZKUtil.listChildrenNoWatch(zkw, znodeToProcess)) {
        stack.add(ZKUtil.joinZNode(znodeToProcess, zNodeChild));
      }
    } while (stack.size() > 0);
  }
  /**
   * Gets the statistics from the given server.
   *
   * @param server  The server to get the statistics from.
   * @param timeout  The socket timeout to use.
   * @return The array of response strings.
   * @throws IOException When the socket communication fails.
   */
  public static String[] getServerStats(String server, int timeout)
  throws IOException {
    String[] sp = server.split(":");
    if (sp == null || sp.length == 0) {
      return null;
    }

    String host = sp[0];
    int port = sp.length > 1 ? Integer.parseInt(sp[1])
        : HConstants.DEFAULT_ZOOKEPER_CLIENT_PORT;

    Socket socket = new Socket();
    InetSocketAddress sockAddr = new InetSocketAddress(host, port);
    socket.connect(sockAddr, timeout);

    socket.setSoTimeout(timeout);
    PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
    BufferedReader in = new BufferedReader(new InputStreamReader(
      socket.getInputStream()));
    out.println("stat");
    out.flush();
    ArrayList<String> res = new ArrayList<String>();
    while (true) {
      String line = in.readLine();
      if (line != null) {
        res.add(line);
      } else {
        break;
      }
    }
    socket.close();
    return res.toArray(new String[res.size()]);
  }

  private static void logRetrievedMsg(final ZooKeeperWatcher zkw,
      final String znode, final byte [] data, final boolean watcherSet) {
    if (!LOG.isDebugEnabled()) return;
    LOG.debug(zkw.prefix("Retrieved " + ((data == null)? 0: data.length) +
      " byte(s) of data from znode " + znode +
      (watcherSet? " and set watcher; ": "; data=") +
      (data == null? "null": data.length == 0? "empty": (
          znode.startsWith(zkw.assignmentZNode) ?
              RegionTransitionData.fromBytes(data).toString()
              : StringUtils.abbreviate(Bytes.toStringBinary(data), 32)))));
  }

  /**
   * Waits for HBase installation's base (parent) znode to become available.
   * @throws IOException on ZK errors
   */
  public static void waitForBaseZNode(Configuration conf) throws IOException {
    LOG.info("Waiting until the base znode is available");
    String parentZNode = conf.get(HConstants.ZOOKEEPER_ZNODE_PARENT,
        HConstants.DEFAULT_ZOOKEEPER_ZNODE_PARENT);
    ZooKeeper zk = new ZooKeeper(ZKConfig.getZKQuorumServersString(conf),
        conf.getInt(HConstants.ZK_SESSION_TIMEOUT,
        HConstants.DEFAULT_ZK_SESSION_TIMEOUT), EmptyWatcher.instance);

    final int maxTimeMs = 10000;
    final int maxNumAttempts = maxTimeMs / HConstants.SOCKET_RETRY_WAIT_MS;

    KeeperException keeperEx = null;
    try {
      try {
        for (int attempt = 0; attempt < maxNumAttempts; ++attempt) {
          try {
            if (zk.exists(parentZNode, false) != null) {
              LOG.info("Parent znode exists: " + parentZNode);
              keeperEx = null;
              break;
            }
          } catch (KeeperException e) {
            keeperEx = e;
          }
          Threads.sleepWithoutInterrupt(HConstants.SOCKET_RETRY_WAIT_MS);
        }
      } finally {
        zk.close();
      }
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
    }

    if (keeperEx != null) {
      throw new IOException(keeperEx);
    }
  }

  /**
   * Recursively print the current state of ZK (non-transactional)
   * @param root name of the root directory in zk to print
   * @throws KeeperException
   */
  public static void logZKTree(ZooKeeperWatcher zkw, String root) {
    if (!LOG.isDebugEnabled()) return;
    LOG.debug("Current zk system:");
    String prefix = "|-";
    LOG.debug(prefix + root);
    try {
      logZKTree(zkw, root, prefix);
    } catch (KeeperException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Helper method to print the current state of the ZK tree.
   * @see #logZKTree(ZooKeeperWatcher, String)
   * @throws KeeperException if an unexpected exception occurs
   */
  protected static void logZKTree(ZooKeeperWatcher zkw, String root, String prefix) throws KeeperException {
    List<String> children = ZKUtil.listChildrenNoWatch(zkw, root);
    if (children == null) return;
    for (String child : children) {
      LOG.debug(prefix + child);
      String node = ZKUtil.joinZNode(root.equals("/") ? "" : root, child);
      logZKTree(zkw, node, prefix + "---");
    }
  }

}
