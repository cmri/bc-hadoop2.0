/**
 * Copyright 2011 The Apache Software Foundation
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
package org.apache.hadoop.hbase;

import java.util.Collection;
import java.util.regex.Pattern;

import org.apache.hadoop.hbase.util.Addressing;
import org.apache.hadoop.hbase.util.Bytes;

/**
 * Instance of an HBase ServerName.
 * A server name is used uniquely identifying a server instance and is made
 * of the combination of hostname, port, and startcode. The startcode
 * distingushes restarted servers on same hostname and port (startcode is
 * usually timestamp of server startup). The {@link #toString()} format of
 * ServerName is safe to use in the  filesystem and as znode name up in
 * ZooKeeper.  Its format is:
 * <code>&lt;hostname> '{@link #SERVERNAME_SEPARATOR}' &lt;port> '{@link #SERVERNAME_SEPARATOR}' &lt;startcode></code>.
 * For example, if hostname is <code>example.org</code>, port is <code>1234</code>,
 * and the startcode for the regionserver is <code>1212121212</code>, then
 * the {@link #toString()} would be <code>example.org,1234,1212121212</code>.
 * 
 * <p>You can obtain a versioned serialized form of this class by calling
 * {@link #getVersionedBytes()}.  To deserialize, call {@link #parseVersionedServerName(byte[])}
 * 
 * <p>Immutable.
 */
public class ServerName implements Comparable<ServerName> {
  /**
   * Version for this class.
   * Its a short rather than a byte so I can for sure distinguish between this
   * version of this class and the version previous to this which did not have
   * a version.
   */
  private static final short VERSION = 0;
  static final byte [] VERSION_BYTES = Bytes.toBytes(VERSION);

  /**
   * What to use if no startcode supplied.
   */
  public static final int NON_STARTCODE = -1;

  /**
   * This character is used as separator between server hostname, port and
   * startcode.
   */
  public static final String SERVERNAME_SEPARATOR = ",";

  public static Pattern SERVERNAME_PATTERN =
    Pattern.compile("[^" + SERVERNAME_SEPARATOR + "]+" +
      SERVERNAME_SEPARATOR + Addressing.VALID_PORT_REGEX +
      SERVERNAME_SEPARATOR + Addressing.VALID_PORT_REGEX + "$");

  /**
   * What to use if server name is unknown.
   */
  public static final String UNKNOWN_SERVERNAME = "#unknown#";

  private final String servername;
  private final String hostname;
  private final int port;
  private final long startcode;

  /**
   * Cached versioned bytes of this ServerName instance.
   * @see #getVersionedBytes()
   */
  private byte [] bytes;

  public ServerName(final String hostname, final int port, final long startcode) {
    this.hostname = hostname;
    this.port = port;
    this.startcode = startcode;
    this.servername = getServerName(hostname, port, startcode);
  }

  public ServerName(final String serverName) {
    this(parseHostname(serverName), parsePort(serverName),
      parseStartcode(serverName));
  }

  public ServerName(final String hostAndPort, final long startCode) {
    this(Addressing.parseHostname(hostAndPort),
      Addressing.parsePort(hostAndPort), startCode);
  }

  public static String parseHostname(final String serverName) {
    if (serverName == null || serverName.length() <= 0) {
      throw new IllegalArgumentException("Passed hostname is null or empty");
    }
    int index = serverName.indexOf(SERVERNAME_SEPARATOR);
    return serverName.substring(0, index);
  }

  public static int parsePort(final String serverName) {
    String [] split = serverName.split(SERVERNAME_SEPARATOR);
    return Integer.parseInt(split[1]);
  }

  public static long parseStartcode(final String serverName) {
    int index = serverName.lastIndexOf(SERVERNAME_SEPARATOR);
    return Long.parseLong(serverName.substring(index + 1));
  }

  @Override
  public String toString() {
    return getServerName();
  }

  /**
   * @return {@link #getServerName()} as bytes with a short-sized prefix with
   * the ServerName#VERSION of this class.
   */
  public synchronized byte [] getVersionedBytes() {
    if (this.bytes == null) {
      this.bytes = Bytes.add(VERSION_BYTES, Bytes.toBytes(getServerName()));
    }
    return this.bytes;
  }

  public String getServerName() {
    return servername;
  }

  public String getHostname() {
    return hostname;
  }

  public int getPort() {
    return port;
  }

  public long getStartcode() {
    return startcode;
  }

  /**
   * @param hostName
   * @param port
   * @param startcode
   * @return Server name made of the concatenation of hostname, port and
   * startcode formatted as <code>&lt;hostname> ',' &lt;port> ',' &lt;startcode></code>
   */
  public static String getServerName(String hostName, int port, long startcode) {
    final StringBuilder name = new StringBuilder(hostName.length() + 1 + 5 + 1 + 13);
    name.append(hostName);
    name.append(SERVERNAME_SEPARATOR);
    name.append(port);
    name.append(SERVERNAME_SEPARATOR);
    name.append(startcode);
    return name.toString();
  }

  /**
   * @param hostAndPort String in form of &lt;hostname> ':' &lt;port>
   * @param startcode
   * @return Server name made of the concatenation of hostname, port and
   * startcode formatted as <code>&lt;hostname> ',' &lt;port> ',' &lt;startcode></code>
   */
  public static String getServerName(final String hostAndPort,
      final long startcode) {
    int index = hostAndPort.indexOf(":");
    if (index <= 0) throw new IllegalArgumentException("Expected <hostname> ':' <port>");
    return getServerName(hostAndPort.substring(0, index),
      Integer.parseInt(hostAndPort.substring(index + 1)), startcode);
  }

  /**
   * @return Hostname and port formatted as described at
   * {@link Addressing#createHostAndPortStr(String, int)}
   */
  public String getHostAndPort() {
    return Addressing.createHostAndPortStr(this.hostname, this.port);
  }

  /**
   * @param serverName ServerName in form specified by {@link #getServerName()}
   * @return The server start code parsed from <code>servername</code>
   */
  public static long getServerStartcodeFromServerName(final String serverName) {
    int index = serverName.lastIndexOf(SERVERNAME_SEPARATOR);
    return Long.parseLong(serverName.substring(index + 1));
  }

  /**
   * Utility method to excise the start code from a server name
   * @param inServerName full server name
   * @return server name less its start code
   */
  public static String getServerNameLessStartCode(String inServerName) {
    if (inServerName != null && inServerName.length() > 0) {
      int index = inServerName.lastIndexOf(SERVERNAME_SEPARATOR);
      if (index > 0) {
        return inServerName.substring(0, index);
      }
    }
    return inServerName;
  }

  @Override
  public int compareTo(ServerName other) {
    int compare = this.getHostname().toLowerCase().
      compareTo(other.getHostname().toLowerCase());
    if (compare != 0) return compare;
    compare = this.getPort() - other.getPort();
    if (compare != 0) return compare;
    return (int)(this.getStartcode() - other.getStartcode());
  }

  @Override
  public int hashCode() {
    return getServerName().hashCode();
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null) return false;
    if (!(o instanceof ServerName)) return false;
    return this.compareTo((ServerName)o) == 0;
  }


  /**
   * @return ServerName with matching hostname and port.
   */
  public static ServerName findServerWithSameHostnamePort(final Collection<ServerName> names,
      final ServerName serverName) {
    for (ServerName sn: names) {
      if (isSameHostnameAndPort(serverName, sn)) return sn;
    }
    return null;
  }

  /**
   * @param left
   * @param right
   * @return True if <code>other</code> has same hostname and port.
   */
  public static boolean isSameHostnameAndPort(final ServerName left,
      final ServerName right) {
    if (left == null) return false;
    if (right == null) return false;
    return left.getHostname().equals(right.getHostname()) &&
      left.getPort() == right.getPort();
  }

  /**
   * Use this method instantiating a {@link ServerName} from bytes
   * gotten from a call to {@link #getVersionedBytes()}.  Will take care of the
   * case where bytes were written by an earlier version of hbase.
   * @param versionedBytes Pass bytes gotten from a call to {@link #getVersionedBytes()}
   * @return A ServerName instance.
   * @see #getVersionedBytes()
   */
  public static ServerName parseVersionedServerName(final byte [] versionedBytes) {
    // Version is a short.
    short version = Bytes.toShort(versionedBytes);
    if (version == VERSION) {
      int length = versionedBytes.length - Bytes.SIZEOF_SHORT;
      return new ServerName(Bytes.toString(versionedBytes, Bytes.SIZEOF_SHORT, length));
    }
    // Presume the bytes were written with an old version of hbase and that the
    // bytes are actually a String of the form "'<hostname>' ':' '<port>'".
    return new ServerName(Bytes.toString(versionedBytes), NON_STARTCODE);
  }

  /**
   * @param str Either an instance of {@link ServerName#toString()} or a
   * "'<hostname>' ':' '<port>'".
   * @return A ServerName instance.
   */
  public static ServerName parseServerName(final String str) {
    return SERVERNAME_PATTERN.matcher(str).matches()? new ServerName(str):
      new ServerName(str, NON_STARTCODE);
  }
}
