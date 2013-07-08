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

package org.apache.hadoop.hbase.ipc;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.WritableByteChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.io.HbaseObjectWritable;
import org.apache.hadoop.hbase.io.WritableWithSize;
import org.apache.hadoop.hbase.monitoring.MonitoredRPCHandler;
import org.apache.hadoop.hbase.monitoring.TaskMonitor;
import org.apache.hadoop.hbase.security.User;
import org.apache.hadoop.hbase.util.ByteBufferOutputStream;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.SizeBasedThrottler;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.io.WritableUtils;
import org.apache.hadoop.ipc.RPC.VersionMismatch;
import org.apache.hadoop.util.ReflectionUtils;
import org.apache.hadoop.util.StringUtils;
import org.cliffc.high_scale_lib.Counter;

import com.google.common.base.Function;
import com.google.common.util.concurrent.ThreadFactoryBuilder;

/** An abstract IPC service.  IPC calls take a single {@link Writable} as a
 * parameter, and return a {@link Writable} as their value.  A service runs on
 * a port and is defined by a parameter class and a value class.
 *
 *
 * <p>Copied local so can fix HBASE-900.
 *
 * @see HBaseClient
 */
public abstract class HBaseServer implements RpcServer {

  /**
   * The first four bytes of Hadoop RPC connections
   */
  public static final ByteBuffer HEADER = ByteBuffer.wrap("hrpc".getBytes());
  public static final byte CURRENT_VERSION = 3;

  /**
   * How many calls/handler are allowed in the queue.
   */
  private static final int DEFAULT_MAX_CALLQUEUE_LENGTH_PER_HANDLER = 10;

  /**
   * The maximum size that we can hold in the IPC queue
   */
  private static final int DEFAULT_MAX_CALLQUEUE_SIZE =
    1024 * 1024 * 1024;

  static final int BUFFER_INITIAL_SIZE = 1024;

  private static final String WARN_DELAYED_CALLS =
      "hbase.ipc.warn.delayedrpc.number";

  private static final int DEFAULT_WARN_DELAYED_CALLS = 1000;

  private final int warnDelayedCalls;

  private AtomicInteger delayedCalls;

  public static final Log LOG =
    LogFactory.getLog("org.apache.hadoop.ipc.HBaseServer");
  protected static final Log TRACELOG =
      LogFactory.getLog("org.apache.hadoop.ipc.HBaseServer.trace");

  protected static final ThreadLocal<RpcServer> SERVER =
    new ThreadLocal<RpcServer>();
  private volatile boolean started = false;

  private static final Map<String, Class<? extends VersionedProtocol>>
      PROTOCOL_CACHE =
      new ConcurrentHashMap<String, Class<? extends VersionedProtocol>>();

  static Class<? extends VersionedProtocol> getProtocolClass(
      String protocolName, Configuration conf)
  throws ClassNotFoundException {
    Class<? extends VersionedProtocol> protocol =
        PROTOCOL_CACHE.get(protocolName);

    if (protocol == null) {
      protocol = (Class<? extends VersionedProtocol>)
          conf.getClassByName(protocolName);
      PROTOCOL_CACHE.put(protocolName, protocol);
    }
    return protocol;
  }

  /** Returns the server instance called under or null.  May be called under
   * {@link #call(Class, Writable, long, MonitoredRPCHandler)} implementations,
   * and under {@link Writable} methods of paramters and return values.
   * Permits applications to access the server context.
   * @return HBaseServer
   */
  public static RpcServer get() {
    return SERVER.get();
  }

  /** This is set to Call object before Handler invokes an RPC and reset
   * after the call returns.
   */
  protected static final ThreadLocal<Call> CurCall = new ThreadLocal<Call>();

  /** Returns the remote side ip address when invoked inside an RPC
   *  Returns null incase of an error.
   *  @return InetAddress
   */
  public static InetAddress getRemoteIp() {
    Call call = CurCall.get();
    if (call != null) {
      return call.connection.socket.getInetAddress();
    }
    return null;
  }
  /** Returns remote address as a string when invoked inside an RPC.
   *  Returns null in case of an error.
   *  @return String
   */
  public static String getRemoteAddress() {
    Call call = CurCall.get();
    if (call != null) {
      return call.connection.getHostAddress();
    }
    return null;
  }

  protected String bindAddress;
  protected int port;                             // port we listen on
  private int handlerCount;                       // number of handler threads
  private int priorityHandlerCount;
  private int readThreads;                        // number of read threads
  protected Class<? extends Writable> paramClass; // class of call parameters
  protected int maxIdleTime;                      // the maximum idle time after
                                                  // which a client may be
                                                  // disconnected
  protected int thresholdIdleConnections;         // the number of idle
                                                  // connections after which we
                                                  // will start cleaning up idle
                                                  // connections
  int maxConnectionsToNuke;                       // the max number of
                                                  // connections to nuke
                                                  // during a cleanup

  protected HBaseRpcMetrics  rpcMetrics;

  protected Configuration conf;

  private int maxQueueLength;
  private int maxQueueSize;
  protected int socketSendBufferSize;
  protected final boolean tcpNoDelay;   // if T then disable Nagle's Algorithm
  protected final boolean tcpKeepAlive; // if T then use keepalives
  protected final long purgeTimeout;    // in milliseconds

  // responseQueuesSizeThrottler is shared among all responseQueues,
  // it bounds memory occupied by responses in all responseQueues
  final SizeBasedThrottler responseQueuesSizeThrottler;

  // RESPONSE_QUEUE_MAX_SIZE limits total size of responses in every response queue
  private static final long DEFAULT_RESPONSE_QUEUES_MAX_SIZE = 1024 * 1024 * 1024; // 1G
  private static final String RESPONSE_QUEUES_MAX_SIZE = "ipc.server.response.queue.maxsize";

  volatile protected boolean running = true;         // true while server runs
  protected BlockingQueue<Call> callQueue; // queued calls
  protected final Counter callQueueSize = new Counter();
  protected BlockingQueue<Call> priorityCallQueue;

  protected int highPriorityLevel;  // what level a high priority call is at

  protected final List<Connection> connectionList =
    Collections.synchronizedList(new LinkedList<Connection>());
  //maintain a list
  //of client connections
  private Listener listener = null;
  protected Responder responder = null;
  protected int numConnections = 0;
  private Handler[] handlers = null;
  private Handler[] priorityHandlers = null;
  /** replication related queue; */
  protected BlockingQueue<Call> replicationQueue;
  private int numOfReplicationHandlers = 0;
  private Handler[] replicationHandlers = null;
  protected HBaseRPCErrorHandler errorHandler = null;

  /**
   * A convenience method to bind to a given address and report
   * better exceptions if the address is not a valid host.
   * @param socket the socket to bind
   * @param address the address to bind to
   * @param backlog the number of connections allowed in the queue
   * @throws BindException if the address can't be bound
   * @throws UnknownHostException if the address isn't a valid host name
   * @throws IOException other random errors from bind
   */
  public static void bind(ServerSocket socket, InetSocketAddress address,
                          int backlog) throws IOException {
    try {
      socket.bind(address, backlog);
    } catch (BindException e) {
      BindException bindException =
        new BindException("Problem binding to " + address + " : " +
            e.getMessage());
      bindException.initCause(e);
      throw bindException;
    } catch (SocketException e) {
      // If they try to bind to a different host's address, give a better
      // error message.
      if ("Unresolved address".equals(e.getMessage())) {
        throw new UnknownHostException("Invalid hostname for server: " +
                                       address.getHostName());
      }
      throw e;
    }
  }

  /** A call queued for handling. */
  protected class Call implements RpcCallContext {
    protected int id;                             // the client's call id
    protected Writable param;                     // the parameter passed
    protected Connection connection;              // connection to client
    protected long timestamp;      // the time received when response is null
                                   // the time served when response is not null
    protected ByteBuffer response;                // the response for this call
    protected boolean delayResponse;
    protected Responder responder;
    protected boolean delayReturnValue;           // if the return value should be
                                                  // set at call completion
    protected long size;                          // size of current call
    protected boolean isError;

    public Call(int id, Writable param, Connection connection,
        Responder responder, long size) {
      this.id = id;
      this.param = param;
      this.connection = connection;
      this.timestamp = System.currentTimeMillis();
      this.response = null;
      this.delayResponse = false;
      this.responder = responder;
      this.isError = false;
      this.size = size;
    }

    @Override
    public String toString() {
      return param.toString() + " from " + connection.toString();
    }

    protected synchronized void setResponse(Object value, Status status,
        String errorClass, String error) {
      // Avoid overwriting an error value in the response.  This can happen if
      // endDelayThrowing is called by another thread before the actual call
      // returning.
      if (this.isError)
        return;
      if (errorClass != null) {
        this.isError = true;
      }
      Writable result = null;
      if (value instanceof Writable) {
        result = (Writable) value;
      } else {
        /* We might have a null value and errors. Avoid creating a
         * HbaseObjectWritable, because the constructor fails on null. */
        if (value != null) {
          result = new HbaseObjectWritable(value);
        }
      }

      int size = BUFFER_INITIAL_SIZE;
      if (result instanceof WritableWithSize) {
        // get the size hint.
        WritableWithSize ohint = (WritableWithSize) result;
        long hint = ohint.getWritableSize() + Bytes.SIZEOF_BYTE +
          (2 * Bytes.SIZEOF_INT);
        if (hint > Integer.MAX_VALUE) {
          // oops, new problem.
          IOException ioe =
            new IOException("Result buffer size too large: " + hint);
          errorClass = ioe.getClass().getName();
          error = StringUtils.stringifyException(ioe);
        } else {
          size = (int)hint;
        }
      }

      ByteBufferOutputStream buf = new ByteBufferOutputStream(size);
      DataOutputStream out = new DataOutputStream(buf);
      try {
        // Call id.
        out.writeInt(this.id);
        // Write flag.
        byte flag = (error != null)?
          ResponseFlag.getErrorAndLengthSet(): ResponseFlag.getLengthSetOnly();
        out.writeByte(flag);
        // Place holder for length set later below after we
        // fill the buffer with data.
        out.writeInt(0xdeadbeef);
        out.writeInt(status.state);
      } catch (IOException e) {
        errorClass = e.getClass().getName();
        error = StringUtils.stringifyException(e);
      }

      try {
        if (error == null) {
          result.write(out);
        } else {
          WritableUtils.writeString(out, errorClass);
          WritableUtils.writeString(out, error);
        }
      } catch (IOException e) {
        LOG.warn("Error sending response to call: ", e);
      }

      // Set the length into the ByteBuffer after call id and after
      // byte flag.
      ByteBuffer bb = buf.getByteBuffer();
      int bufSiz = bb.remaining();
      // Move to the size location in our ByteBuffer past call.id
      // and past the byte flag.
      bb.position(Bytes.SIZEOF_INT + Bytes.SIZEOF_BYTE); 
      bb.putInt(bufSiz);
      bb.position(0);
      this.response = bb;
    }

    @Override
    public synchronized void endDelay(Object result) throws IOException {
      assert this.delayResponse;
      assert this.delayReturnValue || result == null;
      this.delayResponse = false;
      delayedCalls.decrementAndGet();
      if (this.delayReturnValue)
        this.setResponse(result, Status.SUCCESS, null, null);
      this.responder.doRespond(this);
    }

    @Override
    public synchronized void endDelay() throws IOException {
      this.endDelay(null);
    }

    @Override
    public synchronized void startDelay(boolean delayReturnValue) {
      assert !this.delayResponse;
      this.delayResponse = true;
      this.delayReturnValue = delayReturnValue;
      int numDelayed = delayedCalls.incrementAndGet();
      if (numDelayed > warnDelayedCalls) {
        LOG.warn("Too many delayed calls: limit " + warnDelayedCalls +
            " current " + numDelayed);
      }
    }

    @Override
    public synchronized void endDelayThrowing(Throwable t) throws IOException {
      this.setResponse(null, Status.ERROR, t.getClass().toString(),
          StringUtils.stringifyException(t));
      this.delayResponse = false;
      this.sendResponseIfReady();
    }

    @Override
    public synchronized boolean isDelayed() {
      return this.delayResponse;
    }

    @Override
    public synchronized boolean isReturnValueDelayed() {
      return this.delayReturnValue;
    }
    
    @Override
    public void throwExceptionIfCallerDisconnected() throws CallerDisconnectedException {
      if (!connection.channel.isOpen()) {
        long afterTime = System.currentTimeMillis() - timestamp;
        throw new CallerDisconnectedException(
            "Aborting call " + this + " after " + afterTime + " ms, since " +
            "caller disconnected");
      }
    }

    public long getSize() {
      return this.size;
    }

    /**
     * If we have a response, and delay is not set, then respond
     * immediately.  Otherwise, do not respond to client.  This is
     * called the by the RPC code in the context of the Handler thread.
     */
    public synchronized void sendResponseIfReady() throws IOException {
      if (!this.delayResponse) {
        this.responder.doRespond(this);
      }
    }
  }

  /** Listens on the socket. Creates jobs for the handler threads*/
  private class Listener extends Thread {

    private ServerSocketChannel acceptChannel = null; //the accept channel
    private Selector selector = null; //the selector that we use for the server
    private Reader[] readers = null;
    private int currentReader = 0;
    private InetSocketAddress address; //the address we bind at
    private Random rand = new Random();
    private long lastCleanupRunTime = 0; //the last time when a cleanup connec-
                                         //-tion (for idle connections) ran
    private long cleanupInterval = 10000; //the minimum interval between
                                          //two cleanup runs
    private int backlogLength = conf.getInt("ipc.server.listen.queue.size", 128);

    private ExecutorService readPool;

    public Listener() throws IOException {
      address = new InetSocketAddress(bindAddress, port);
      // Create a new server socket and set to non blocking mode
      acceptChannel = ServerSocketChannel.open();
      acceptChannel.configureBlocking(false);

      // Bind the server socket to the local host and port
      bind(acceptChannel.socket(), address, backlogLength);
      port = acceptChannel.socket().getLocalPort(); //Could be an ephemeral port
      // create a selector;
      selector= Selector.open();

      readers = new Reader[readThreads];
      readPool = Executors.newFixedThreadPool(readThreads,
        new ThreadFactoryBuilder().setNameFormat(
          "IPC Reader %d on port " + port).setDaemon(true).build());
      for (int i = 0; i < readThreads; ++i) {
        Reader reader = new Reader();
        readers[i] = reader;
        readPool.execute(reader);
      }

      // Register accepts on the server socket with the selector.
      acceptChannel.register(selector, SelectionKey.OP_ACCEPT);
      this.setName("IPC Server listener on " + port);
      this.setDaemon(true);
    }


    private class Reader implements Runnable {
      private volatile boolean adding = false;
      private final Selector readSelector;

      Reader() throws IOException {
        this.readSelector = Selector.open();
      }
      public void run() {
        LOG.info("Starting " + getName());
        try {
          doRunLoop();
        } finally {
          try {
            readSelector.close();
          } catch (IOException ioe) {
            LOG.error("Error closing read selector in " + getName(), ioe);
          }
        }
      }

      private synchronized void doRunLoop() {
        while (running) {
          SelectionKey key = null;
          try {
            readSelector.select();
            while (adding) {
              this.wait(1000);
            }

            Iterator<SelectionKey> iter = readSelector.selectedKeys().iterator();
            while (iter.hasNext()) {
              key = iter.next();
              iter.remove();
              if (key.isValid()) {
                if (key.isReadable()) {
                  doRead(key);
                }
              }
              key = null;
            }
          } catch (InterruptedException e) {
            if (running) {                      // unexpected -- log it
              LOG.info(getName() + " unexpectedly interrupted: " +
                  StringUtils.stringifyException(e));
            }
          } catch (IOException ex) {
            LOG.error("Error in Reader", ex);
          }
        }
      }

      /**
       * This gets reader into the state that waits for the new channel
       * to be registered with readSelector. If it was waiting in select()
       * the thread will be woken up, otherwise whenever select() is called
       * it will return even if there is nothing to read and wait
       * in while(adding) for finishAdd call
       */
      public void startAdd() {
        adding = true;
        readSelector.wakeup();
      }

      public synchronized SelectionKey registerChannel(SocketChannel channel)
        throws IOException {
        return channel.register(readSelector, SelectionKey.OP_READ);
      }

      public synchronized void finishAdd() {
        adding = false;
        this.notify();
      }
    }

    /** cleanup connections from connectionList. Choose a random range
     * to scan and also have a limit on the number of the connections
     * that will be cleanedup per run. The criteria for cleanup is the time
     * for which the connection was idle. If 'force' is true then all
     * connections will be looked at for the cleanup.
     * @param force all connections will be looked at for cleanup
     */
    private void cleanupConnections(boolean force) {
      if (force || numConnections > thresholdIdleConnections) {
        long currentTime = System.currentTimeMillis();
        if (!force && (currentTime - lastCleanupRunTime) < cleanupInterval) {
          return;
        }
        int start = 0;
        int end = numConnections - 1;
        if (!force) {
          start = rand.nextInt() % numConnections;
          end = rand.nextInt() % numConnections;
          int temp;
          if (end < start) {
            temp = start;
            start = end;
            end = temp;
          }
        }
        int i = start;
        int numNuked = 0;
        while (i <= end) {
          Connection c;
          synchronized (connectionList) {
            try {
              c = connectionList.get(i);
            } catch (Exception e) {return;}
          }
          if (c.timedOut(currentTime)) {
            if (LOG.isDebugEnabled())
              LOG.debug(getName() + ": disconnecting client " + c.getHostAddress());
            closeConnection(c);
            numNuked++;
            end--;
            //noinspection UnusedAssignment
            c = null;
            if (!force && numNuked == maxConnectionsToNuke) break;
          }
          else i++;
        }
        lastCleanupRunTime = System.currentTimeMillis();
      }
    }

    @Override
    public void run() {
      LOG.info(getName() + ": starting");
      SERVER.set(HBaseServer.this);

      while (running) {
        SelectionKey key = null;
        try {
          selector.select(); // FindBugs IS2_INCONSISTENT_SYNC
          Iterator<SelectionKey> iter = selector.selectedKeys().iterator();
          while (iter.hasNext()) {
            key = iter.next();
            iter.remove();
            try {
              if (key.isValid()) {
                if (key.isAcceptable())
                  doAccept(key);
              }
            } catch (IOException ignored) {
            }
            key = null;
          }
        } catch (OutOfMemoryError e) {
          if (errorHandler != null) {
            if (errorHandler.checkOOME(e)) {
              LOG.info(getName() + ": exiting on OOME");
              closeCurrentConnection(key, e);
              cleanupConnections(true);
              return;
            }
          } else {
            // we can run out of memory if we have too many threads
            // log the event and sleep for a minute and give
            // some thread(s) a chance to finish
            LOG.warn("Out of Memory in server select", e);
            closeCurrentConnection(key, e);
            cleanupConnections(true);
            try { Thread.sleep(60000); } catch (Exception ignored) {}
      }
        } catch (Exception e) {
          closeCurrentConnection(key, e);
        }
        cleanupConnections(false);
      }
      LOG.info("Stopping " + this.getName());

      synchronized (this) {
        try {
          acceptChannel.close();
          selector.close();
        } catch (IOException ignored) { }

        selector= null;
        acceptChannel= null;

        // clean up all connections
        while (!connectionList.isEmpty()) {
          closeConnection(connectionList.remove(0));
        }
      }
    }

    private void closeCurrentConnection(SelectionKey key, Throwable e) {
      if (key != null) {
        Connection c = (Connection)key.attachment();
        if (c != null) {
          if (LOG.isDebugEnabled()) {
            LOG.debug(getName() + ": disconnecting client " + c.getHostAddress() +
                (e != null ? " on error " + e.getMessage() : ""));
          }
          closeConnection(c);
          key.attach(null);
        }
      }
    }

    InetSocketAddress getAddress() {
      return (InetSocketAddress)acceptChannel.socket().getLocalSocketAddress();
    }

    void doAccept(SelectionKey key) throws IOException, OutOfMemoryError {
      Connection c;
      ServerSocketChannel server = (ServerSocketChannel) key.channel();

      SocketChannel channel;
      while ((channel = server.accept()) != null) {
        channel.configureBlocking(false);
        channel.socket().setTcpNoDelay(tcpNoDelay);
        channel.socket().setKeepAlive(tcpKeepAlive);

        Reader reader = getReader();
        try {
          reader.startAdd();
          SelectionKey readKey = reader.registerChannel(channel);
          c = getConnection(channel, System.currentTimeMillis());
          readKey.attach(c);
          synchronized (connectionList) {
            connectionList.add(numConnections, c);
            numConnections++;
          }
          if (LOG.isDebugEnabled())
            LOG.debug("Server connection from " + c.toString() +
                "; # active connections: " + numConnections +
                "; # queued calls: " + callQueue.size());
        } finally {
          reader.finishAdd();
        }
      }
      rpcMetrics.numOpenConnections.set(numConnections);
    }

    void doRead(SelectionKey key) throws InterruptedException {
      int count = 0;
      Connection c = (Connection)key.attachment();
      if (c == null) {
        return;
      }
      c.setLastContact(System.currentTimeMillis());

      try {
        count = c.readAndProcess();
      } catch (InterruptedException ieo) {
        throw ieo;
      } catch (Exception e) {
        LOG.warn(getName() + ": readAndProcess threw exception " + e + ". Count of bytes read: " + count, e);
        count = -1; //so that the (count < 0) block is executed
      }
      if (count < 0) {
        if (LOG.isDebugEnabled())
          LOG.debug(getName() + ": disconnecting client " +
                    c.getHostAddress() + ". Number of active connections: "+
                    numConnections);
        closeConnection(c);
        // c = null;
      }
      else {
        c.setLastContact(System.currentTimeMillis());
      }
    }

    synchronized void doStop() {
      if (selector != null) {
        selector.wakeup();
        Thread.yield();
      }
      if (acceptChannel != null) {
        try {
          acceptChannel.socket().close();
        } catch (IOException e) {
          LOG.info(getName() + ":Exception in closing listener socket. " + e);
        }
      }
      readPool.shutdownNow();
    }

    // The method that will return the next reader to work with
    // Simplistic implementation of round robin for now
    Reader getReader() {
      currentReader = (currentReader + 1) % readers.length;
      return readers[currentReader];
    }
  }

  // Sends responses of RPC back to clients.
  protected class Responder extends Thread {
    private final Selector writeSelector;
    private int pending;         // connections waiting to register

    Responder() throws IOException {
      this.setName("IPC Server Responder");
      this.setDaemon(true);
      writeSelector = Selector.open(); // create a selector
      pending = 0;
    }

    @Override
    public void run() {
      LOG.info(getName() + ": starting");
      SERVER.set(HBaseServer.this);
      try {
        doRunLoop();
      } finally {
        LOG.info("Stopping " + this.getName());
        try {
          writeSelector.close();
        } catch (IOException ioe) {
          LOG.error("Couldn't close write selector in " + this.getName(), ioe);
        }
      }
    }

    private void doRunLoop() {
      long lastPurgeTime = 0;   // last check for old calls.

      while (running) {
        try {
          waitPending();     // If a channel is being registered, wait.
          writeSelector.select(purgeTimeout);
          Iterator<SelectionKey> iter = writeSelector.selectedKeys().iterator();
          while (iter.hasNext()) {
            SelectionKey key = iter.next();
            iter.remove();
            try {
              if (key.isValid() && key.isWritable()) {
                  doAsyncWrite(key);
              }
            } catch (IOException e) {
              LOG.info(getName() + ": doAsyncWrite threw exception " + e);
            }
          }
          long now = System.currentTimeMillis();
          if (now < lastPurgeTime + purgeTimeout) {
            continue;
          }
          lastPurgeTime = now;
          //
          // If there were some calls that have not been sent out for a
          // long time, discard them.
          //
          LOG.debug("Checking for old call responses.");
          ArrayList<Call> calls;

          // get the list of channels from list of keys.
          synchronized (writeSelector.keys()) {
            calls = new ArrayList<Call>(writeSelector.keys().size());
            iter = writeSelector.keys().iterator();
            while (iter.hasNext()) {
              SelectionKey key = iter.next();
              Call call = (Call)key.attachment();
              if (call != null && key.channel() == call.connection.channel) {
                calls.add(call);
              }
            }
          }

          for(Call call : calls) {
            try {
              doPurge(call, now);
            } catch (IOException e) {
              LOG.warn("Error in purging old calls " + e);
            }
          }
        } catch (OutOfMemoryError e) {
          if (errorHandler != null) {
            if (errorHandler.checkOOME(e)) {
              LOG.info(getName() + ": exiting on OOME");
              return;
            }
          } else {
            //
            // we can run out of memory if we have too many threads
            // log the event and sleep for a minute and give
            // some thread(s) a chance to finish
            //
            LOG.warn("Out of Memory in server select", e);
            try { Thread.sleep(60000); } catch (Exception ignored) {}
          }
        } catch (Exception e) {
          LOG.warn("Exception in Responder " +
                   StringUtils.stringifyException(e));
        }
      }
      LOG.info("Stopping " + this.getName());
    }

    private void doAsyncWrite(SelectionKey key) throws IOException {
      Call call = (Call)key.attachment();
      if (call == null) {
        return;
      }
      if (key.channel() != call.connection.channel) {
        throw new IOException("doAsyncWrite: bad channel");
      }

      synchronized(call.connection.responseQueue) {
        if (processResponse(call.connection.responseQueue, false)) {
          try {
            key.interestOps(0);
          } catch (CancelledKeyException e) {
            /* The Listener/reader might have closed the socket.
             * We don't explicitly cancel the key, so not sure if this will
             * ever fire.
             * This warning could be removed.
             */
            LOG.warn("Exception while changing ops : " + e);
          }
        }
      }
    }

    //
    // Remove calls that have been pending in the responseQueue
    // for a long time.
    //
    private void doPurge(Call call, long now) throws IOException {
      synchronized (call.connection.responseQueue) {
        Iterator<Call> iter = call.connection.responseQueue.listIterator(0);
        while (iter.hasNext()) {
          Call nextCall = iter.next();
          if (now > nextCall.timestamp + purgeTimeout) {
            closeConnection(nextCall.connection);
            break;
          }
        }
      }
    }

    // Processes one response. Returns true if there are no more pending
    // data for this channel.
    //
    private boolean processResponse(final LinkedList<Call> responseQueue,
                                    boolean inHandler) throws IOException {
      boolean error = true;
      boolean done = false;       // there is more data for this channel.
      int numElements;
      Call call = null;
      try {
        //noinspection SynchronizationOnLocalVariableOrMethodParameter
        synchronized (responseQueue) {
          //
          // If there are no items for this channel, then we are done
          //
          numElements = responseQueue.size();
          if (numElements == 0) {
            error = false;
            return true;              // no more data for this channel.
          }
          //
          // Extract the first call
          //
          call = responseQueue.peek();
          SocketChannel channel = call.connection.channel;
          if (LOG.isDebugEnabled()) {
            LOG.debug(getName() + ": responding to #" + call.id + " from " +
                      call.connection);
          }
          //
          // Send as much data as we can in the non-blocking fashion
          //
          int numBytes = channelWrite(channel, call.response);
          if (numBytes < 0) {
            // Error flag is set, so returning here closes connection and
            // clears responseQueue.                   
            return true;
          }
          if (!call.response.hasRemaining()) {
            responseQueue.poll();
            responseQueuesSizeThrottler.decrease(call.response.limit());    
            call.connection.decRpcCount();
            //noinspection RedundantIfStatement
            if (numElements == 1) {    // last call fully processes.
              done = true;             // no more data for this channel.
            } else {
              done = false;            // more calls pending to be sent.
            }
            if (LOG.isDebugEnabled()) {
              LOG.debug(getName() + ": responding to #" + call.id + " from " +
                        call.connection + " Wrote " + numBytes + " bytes.");
            }
          } else {
            if (inHandler) {
              // set the serve time when the response has to be sent later
              call.timestamp = System.currentTimeMillis();
              if (enqueueInSelector(call))
                done = true;
            }
            if (LOG.isDebugEnabled()) {
              LOG.debug(getName() + ": responding to #" + call.id + " from " +
                        call.connection + " Wrote partial " + numBytes +
                        " bytes.");
            }
          }
          error = false;              // everything went off well
        }
      } finally {
        if (error && call != null) {
          LOG.warn(getName()+", call " + call + ": output error");
          done = true;               // error. no more data for this channel.
          closeConnection(call.connection);
        }
      }
      return done;
    }

    //
    // Enqueue for background thread to send responses out later.
    //
    private boolean enqueueInSelector(Call call) throws IOException {
      boolean done = false;
      incPending();
      try {
        // Wake up the thread blocked on select, only then can the call
        // to channel.register() complete.
        SocketChannel channel = call.connection.channel;
        writeSelector.wakeup();
        channel.register(writeSelector, SelectionKey.OP_WRITE, call);
      } catch (ClosedChannelException e) {
        //It's OK.  Channel might be closed else where.
        done = true;
      } finally {
        decPending();
      }
      return done;
    }

    //
    // Enqueue a response from the application.
    //
    void doRespond(Call call) throws IOException {
      // set the serve time when the response has to be sent later
      call.timestamp = System.currentTimeMillis();

      boolean doRegister = false;
      boolean closed;
      try {
        responseQueuesSizeThrottler.increase(call.response.remaining());
      } catch (InterruptedException ie) {
        throw new InterruptedIOException(ie.getMessage());
      }
      synchronized (call.connection.responseQueue) {
        closed = call.connection.closed;
        if (!closed) {
          call.connection.responseQueue.addLast(call);

          if (call.connection.responseQueue.size() == 1) {
            doRegister = !processResponse(call.connection.responseQueue, false);
          }
        }
      }
      if (doRegister) {
        enqueueInSelector(call);
      }
      if (closed) {
        // Connection was closed when we tried to submit response, but we
        // increased responseQueues size already. It shoud be
        // decreased here.
        responseQueuesSizeThrottler.decrease(call.response.remaining());
      }      
    }

    private synchronized void incPending() {   // call waiting to be enqueued.
      pending++;
    }

    private synchronized void decPending() { // call done enqueueing.
      pending--;
      notify();
    }

    private synchronized void waitPending() throws InterruptedException {
      while (pending > 0) {
        wait();
      }
    }
  }

  /** Reads calls from a connection and queues them for handling. */
  protected class Connection {
    private boolean versionRead = false; //if initial signature and
                                         //version are read
    private boolean headerRead = false;  //if the connection header that
                                         //follows version is read.

    protected volatile boolean closed = false;    // indicates if connection was closed
    protected SocketChannel channel;
    private ByteBuffer data;
    private ByteBuffer dataLengthBuffer;
    protected final LinkedList<Call> responseQueue;
    private volatile int rpcCount = 0; // number of outstanding rpcs
    private long lastContact;
    private int dataLength;
    protected Socket socket;
    // Cache the remote host & port info so that even if the socket is
    // disconnected, we can say where it used to connect to.
    protected String hostAddress;
    protected int remotePort;
    ConnectionHeader header = new ConnectionHeader();
    Class<? extends VersionedProtocol> protocol;
    protected User ticket = null;

    public Connection(SocketChannel channel, long lastContact) {
      this.channel = channel;
      this.lastContact = lastContact;
      this.data = null;
      this.dataLengthBuffer = ByteBuffer.allocate(4);
      this.socket = channel.socket();
      InetAddress addr = socket.getInetAddress();
      if (addr == null) {
        this.hostAddress = "*Unknown*";
      } else {
        this.hostAddress = addr.getHostAddress();
      }
      this.remotePort = socket.getPort();
      this.responseQueue = new LinkedList<Call>();
      if (socketSendBufferSize != 0) {
        try {
          socket.setSendBufferSize(socketSendBufferSize);
        } catch (IOException e) {
          LOG.warn("Connection: unable to set socket send buffer size to " +
                   socketSendBufferSize);
        }
      }
    }

    @Override
    public String toString() {
      return getHostAddress() + ":" + remotePort;
    }

    public String getHostAddress() {
      return hostAddress;
    }

    public int getRemotePort() {
      return remotePort;
    }

    public void setLastContact(long lastContact) {
      this.lastContact = lastContact;
    }

    public long getLastContact() {
      return lastContact;
    }

    /* Return true if the connection has no outstanding rpc */
    private boolean isIdle() {
      return rpcCount == 0;
    }

    /* Decrement the outstanding RPC count */
    protected void decRpcCount() {
      rpcCount--;
    }

    /* Increment the outstanding RPC count */
    protected void incRpcCount() {
      rpcCount++;
    }

    protected boolean timedOut(long currentTime) {
      return isIdle() && currentTime - lastContact > maxIdleTime;
    }

    public int readAndProcess() throws IOException, InterruptedException {
      while (true) {
        /* Read at most one RPC. If the header is not read completely yet
         * then iterate until we read first RPC or until there is no data left.
         */
        int count;
        if (dataLengthBuffer.remaining() > 0) {
          count = channelRead(channel, dataLengthBuffer);
          if (count < 0 || dataLengthBuffer.remaining() > 0)
            return count;
        }

        if (!versionRead) {
          //Every connection is expected to send the header.
          ByteBuffer versionBuffer = ByteBuffer.allocate(1);
          count = channelRead(channel, versionBuffer);
          if (count <= 0) {
            return count;
          }
          int version = versionBuffer.get(0);

          dataLengthBuffer.flip();
          if (!HEADER.equals(dataLengthBuffer) || version != CURRENT_VERSION) {
            //Warning is ok since this is not supposed to happen.
            LOG.warn("Incorrect header or version mismatch from " +
                     hostAddress + ":" + remotePort +
                     " got version " + version +
                     " expected version " + CURRENT_VERSION);
            setupBadVersionResponse(version);
            return -1;
          }
          dataLengthBuffer.clear();
          versionRead = true;
          continue;
        }

        if (data == null) {
          dataLengthBuffer.flip();
          dataLength = dataLengthBuffer.getInt();

          if (dataLength == HBaseClient.PING_CALL_ID) {
            dataLengthBuffer.clear();
            return 0;  //ping message
          }
          data = ByteBuffer.allocate(dataLength);
          incRpcCount();  // Increment the rpc count
        }

        count = channelRead(channel, data);

        if (data.remaining() == 0) {
          dataLengthBuffer.clear();
          data.flip();
          if (headerRead) {
            processData(data.array());
            data = null;
            return count;
          }
          processHeader();
          headerRead = true;
          data = null;
          continue;
        }
        return count;
      }
    }

    /**
     * Try to set up the response to indicate that the client version
     * is incompatible with the server. This can contain special-case
     * code to speak enough of past IPC protocols to pass back
     * an exception to the caller.
     * @param clientVersion the version the caller is using
     * @throws IOException
     */
    private void setupBadVersionResponse(int clientVersion) throws IOException {
      String errMsg = "Server IPC version " + CURRENT_VERSION +
      " cannot communicate with client version " + clientVersion;
      ByteArrayOutputStream buffer = new ByteArrayOutputStream();

      if (clientVersion >= 3) {
        // We used to return an id of -1 which caused server to close the
        // connection without telling the client what the problem was.  Now
        // we return 0 which will keep the socket up -- bad clients, unless
        // they switch to suit the running server -- will fail later doing
        // getProtocolVersion.
        Call fakeCall =  new Call(0, null, this, responder, 0);
        // Versions 3 and greater can interpret this exception
        // response in the same manner
        setupResponse(buffer, fakeCall, Status.FATAL,
            null, VersionMismatch.class.getName(), errMsg);

        responder.doRespond(fakeCall);
      }
    }

    /// Reads the connection header following version
    private void processHeader() throws IOException {
      DataInputStream in =
        new DataInputStream(new ByteArrayInputStream(data.array()));
      header.readFields(in);
      try {
        String protocolClassName = header.getProtocol();
        if (protocolClassName == null) {
          protocolClassName = "org.apache.hadoop.hbase.ipc.HRegionInterface";
        }
        protocol = getProtocolClass(protocolClassName, conf);
      } catch (ClassNotFoundException cnfe) {
        throw new IOException("Unknown protocol: " + header.getProtocol());
      }

      ticket = header.getUser();
    }

    protected void processData(byte[] buf) throws  IOException, InterruptedException {
      DataInputStream dis =
        new DataInputStream(new ByteArrayInputStream(buf));
      int id = dis.readInt();                    // try to read an id
      long callSize = buf.length;

      if (LOG.isDebugEnabled()) {
        LOG.debug(" got call #" + id + ", " + callSize + " bytes");
      }

      // Enforcing the call queue size, this triggers a retry in the client
      if ((callSize + callQueueSize.get()) > maxQueueSize) {
        final Call callTooBig =
          new Call(id, null, this, responder, callSize);
        ByteArrayOutputStream responseBuffer = new ByteArrayOutputStream();
        setupResponse(responseBuffer, callTooBig, Status.FATAL, null,
            IOException.class.getName(),
            "Call queue is full, is ipc.server.max.callqueue.size too small?");
        responder.doRespond(callTooBig);
        return;
      }

      Writable param;
      try {
        param = ReflectionUtils.newInstance(paramClass, conf);//read param
        param.readFields(dis);
      } catch (Throwable t) {
        LOG.warn("Unable to read call parameters for client " +
                 getHostAddress(), t);
        final Call readParamsFailedCall =
          new Call(id, null, this, responder, callSize);
        ByteArrayOutputStream responseBuffer = new ByteArrayOutputStream();

        setupResponse(responseBuffer, readParamsFailedCall, Status.FATAL, null,
            t.getClass().getName(),
            "IPC server unable to read call parameters: " + t.getMessage());
        responder.doRespond(readParamsFailedCall);
        return;
      }
      Call call = new Call(id, param, this, responder, callSize);
      callQueueSize.add(callSize);

      if (priorityCallQueue != null && getQosLevel(param) > highPriorityLevel) {
        priorityCallQueue.put(call);
        updateCallQueueLenMetrics(priorityCallQueue);
      } else if (replicationQueue != null && getQosLevel(param) == HConstants.REPLICATION_QOS) {
        replicationQueue.put(call);
        updateCallQueueLenMetrics(replicationQueue);
      } else {
        callQueue.put(call); // queue the call; maybe blocked here
        updateCallQueueLenMetrics(callQueue);
      }
    }

    protected synchronized void close() {
      closed = true;
      data = null;
      dataLengthBuffer = null;
      if (!channel.isOpen())
        return;
      try {socket.shutdownOutput();} catch(Exception ignored) {} // FindBugs DE_MIGHT_IGNORE
      if (channel.isOpen()) {
        try {channel.close();} catch(Exception ignored) {}
      }
      try {socket.close();} catch(Exception ignored) {}
    }
  }

  /**
   * Reports length of the call queue to HBaseRpcMetrics.
   * @param queue Which queue to report
   */
  protected void updateCallQueueLenMetrics(BlockingQueue<Call> queue) {
    if (queue == callQueue) {
      rpcMetrics.callQueueLen.set(callQueue.size());
    } else if (queue == priorityCallQueue) {
      rpcMetrics.priorityCallQueueLen.set(priorityCallQueue.size());
    } else if (queue == replicationQueue) {
      rpcMetrics.replicationCallQueueLen.set(replicationQueue.size());
    } else {
      LOG.warn("Unknown call queue");
    }
  }

  /** Handles queued calls . */
  private class Handler extends Thread {
    private final BlockingQueue<Call> myCallQueue;
    private MonitoredRPCHandler status;

    public Handler(final BlockingQueue<Call> cq, int instanceNumber) {
      this.myCallQueue = cq;
      this.setDaemon(true);

      String threadName = "IPC Server handler " + instanceNumber + " on " + port;
      if (cq == priorityCallQueue) {
        // this is just an amazing hack, but it works.
        threadName = "PRI " + threadName;
      } else if (cq == replicationQueue) {
        threadName = "REPL " + threadName;
      }
      this.setName(threadName);
      this.status = TaskMonitor.get().createRPCStatus(threadName);
    }

    @Override
    public void run() {
      LOG.info(getName() + ": starting");
      status.setStatus("starting");
      SERVER.set(HBaseServer.this);
      while (running) {
        try {
          status.pause("Waiting for a call");
          Call call = myCallQueue.take(); // pop the queue; maybe blocked here
          updateCallQueueLenMetrics(myCallQueue);
          status.setStatus("Setting up call");
          status.setConnection(call.connection.getHostAddress(), 
              call.connection.getRemotePort());

          if (LOG.isDebugEnabled())
            LOG.debug(getName() + ": has #" + call.id + " from " +
                      call.connection);

          String errorClass = null;
          String error = null;
          Writable value = null;

          CurCall.set(call);
          try {
            if (!started)
              throw new ServerNotRunningYetException("Server is not running yet");

            if (LOG.isDebugEnabled()) {
              User remoteUser = call.connection.ticket;
              LOG.debug(getName() + ": call #" + call.id + " executing as "
                  + (remoteUser == null ? "NULL principal" : remoteUser.getName()));
            }

            RequestContext.set(call.connection.ticket, getRemoteIp(),
                call.connection.protocol);
            // make the call
            value = call(call.connection.protocol, call.param, call.timestamp, 
                status);
          } catch (Throwable e) {
            LOG.debug(getName()+", call "+call+": error: " + e, e);
            errorClass = e.getClass().getName();
            error = StringUtils.stringifyException(e);
          } finally {
            // Must always clear the request context to avoid leaking
            // credentials between requests.
            RequestContext.clear();
          }
          CurCall.set(null);
          callQueueSize.add(call.getSize() * -1);
          // Set the response for undelayed calls and delayed calls with
          // undelayed responses.
          if (!call.isDelayed() || !call.isReturnValueDelayed()) {
            call.setResponse(value,
              errorClass == null? Status.SUCCESS: Status.ERROR,
                errorClass, error);
          }
          call.sendResponseIfReady();
          status.markComplete("Sent response");
        } catch (InterruptedException e) {
          if (running) {                          // unexpected -- log it
            LOG.info(getName() + " caught: " +
                     StringUtils.stringifyException(e));
          }
        } catch (OutOfMemoryError e) {
          if (errorHandler != null) {
            if (errorHandler.checkOOME(e)) {
              LOG.info(getName() + ": exiting on OOME");
              return;
            }
          } else {
            // rethrow if no handler
            throw e;
          }
       } catch (ClosedChannelException cce) {
          LOG.warn(getName() + " caught a ClosedChannelException, " +
            "this means that the server was processing a " +
            "request but the client went away. The error message was: " +
            cce.getMessage());
        } catch (Exception e) {
          LOG.warn(getName() + " caught: " +
                   StringUtils.stringifyException(e));
        }
      }
      LOG.info(getName() + ": exiting");
    }

  }


  private Function<Writable,Integer> qosFunction = null;

  /**
   * Gets the QOS level for this call.  If it is higher than the highPriorityLevel and there
   * are priorityHandlers available it will be processed in it's own thread set.
   *
   * @param newFunc
   */
  @Override
  public void setQosFunction(Function<Writable, Integer> newFunc) {
    qosFunction = newFunc;
  }

  protected int getQosLevel(Writable param) {
    if (qosFunction == null) {
      return 0;
    }

    Integer res = qosFunction.apply(param);
    if (res == null) {
      return 0;
    }
    return res;
  }

  /* Constructs a server listening on the named port and address.  Parameters passed must
   * be of the named class.  The <code>handlerCount</handlerCount> determines
   * the number of handler threads that will be used to process calls.
   *
   */
  protected HBaseServer(String bindAddress, int port,
                        Class<? extends Writable> paramClass, int handlerCount,
                        int priorityHandlerCount, Configuration conf, String serverName,
                        int highPriorityLevel)
    throws IOException {
    this.bindAddress = bindAddress;
    this.conf = conf;
    this.port = port;
    this.paramClass = paramClass;
    this.handlerCount = handlerCount;
    this.priorityHandlerCount = priorityHandlerCount;
    this.socketSendBufferSize = 0;

    // temporary backward compatibility
    String oldMaxQueueSize = this.conf.get("ipc.server.max.queue.size");
    if (oldMaxQueueSize == null) {
      this.maxQueueLength =
        this.conf.getInt("ipc.server.max.callqueue.length",
          handlerCount * DEFAULT_MAX_CALLQUEUE_LENGTH_PER_HANDLER);
    } else {
      LOG.warn("ipc.server.max.queue.size was renamed " +
               "ipc.server.max.callqueue.length, " +
               "please update your configuration");
      this.maxQueueLength = Integer.getInteger(oldMaxQueueSize);
    }

    this.maxQueueSize =
      this.conf.getInt("ipc.server.max.callqueue.size",
        DEFAULT_MAX_CALLQUEUE_SIZE);
     this.readThreads = conf.getInt(
        "ipc.server.read.threadpool.size",
        10);
    this.callQueue  = new LinkedBlockingQueue<Call>(maxQueueLength);
    if (priorityHandlerCount > 0) {
      this.priorityCallQueue = new LinkedBlockingQueue<Call>(maxQueueLength); // TODO hack on size
    } else {
      this.priorityCallQueue = null;
    }
    this.highPriorityLevel = highPriorityLevel;
    this.maxIdleTime = 2*conf.getInt("ipc.client.connection.maxidletime", 1000);
    this.maxConnectionsToNuke = conf.getInt("ipc.client.kill.max", 10);
    this.thresholdIdleConnections = conf.getInt("ipc.client.idlethreshold", 4000);
    this.purgeTimeout = conf.getLong("ipc.client.call.purge.timeout",
                                     2 * HConstants.DEFAULT_HBASE_RPC_TIMEOUT);
    this.numOfReplicationHandlers = 
      conf.getInt("hbase.regionserver.replication.handler.count", 3);
    if (numOfReplicationHandlers > 0) {
      this.replicationQueue = new LinkedBlockingQueue<Call>(maxQueueSize);
    }
    // Start the listener here and let it bind to the port
    listener = new Listener();
    this.port = listener.getAddress().getPort();
    this.rpcMetrics = new HBaseRpcMetrics(
        serverName, Integer.toString(this.port));
    this.tcpNoDelay = conf.getBoolean("ipc.server.tcpnodelay", false);
    this.tcpKeepAlive = conf.getBoolean("ipc.server.tcpkeepalive", true);

    this.warnDelayedCalls = conf.getInt(WARN_DELAYED_CALLS,
                                        DEFAULT_WARN_DELAYED_CALLS);
    this.delayedCalls = new AtomicInteger(0);


    this.responseQueuesSizeThrottler = new SizeBasedThrottler(
        conf.getLong(RESPONSE_QUEUES_MAX_SIZE, DEFAULT_RESPONSE_QUEUES_MAX_SIZE));

    // Create the responder here
    responder = new Responder();
  }

  /**
   * Subclasses of HBaseServer can override this to provide their own
   * Connection implementations.
   */
  protected Connection getConnection(SocketChannel channel, long time) {
    return new Connection(channel, time);
  }

  /**
   * Setup response for the IPC Call.
   *
   * @param response buffer to serialize the response into
   * @param call {@link Call} to which we are setting up the response
   * @param status {@link Status} of the IPC call
   * @param rv return value for the IPC Call, if the call was successful
   * @param errorClass error class, if the the call failed
   * @param error error message, if the call failed
   * @throws IOException
   */
  private void setupResponse(ByteArrayOutputStream response,
                             Call call, Status status,
                             Writable rv, String errorClass, String error)
  throws IOException {
    response.reset();
    DataOutputStream out = new DataOutputStream(response);

    if (status == Status.SUCCESS) {
      try {
        rv.write(out);
        call.setResponse(rv, status, null, null);
      } catch (Throwable t) {
        LOG.warn("Error serializing call response for call " + call, t);
        // Call back to same function - this is OK since the
        // buffer is reset at the top, and since status is changed
        // to ERROR it won't infinite loop.
        call.setResponse(null, status.ERROR, t.getClass().getName(),
            StringUtils.stringifyException(t));
      }
    } else {
      call.setResponse(rv, status, errorClass, error);
    }
  }

  protected void closeConnection(Connection connection) {
    synchronized (connectionList) {
      if (connectionList.remove(connection)) {
        numConnections--;
      }
    }
    connection.close();
    long bytes = 0;
    synchronized (connection.responseQueue) {
      for (Call c : connection.responseQueue) {
        bytes += c.response.limit();
      }
      connection.responseQueue.clear();
    }
    responseQueuesSizeThrottler.decrease(bytes);    
    rpcMetrics.numOpenConnections.set(numConnections);
  }

  /** Sets the socket buffer size used for responding to RPCs.
   * @param size send size
   */
  @Override
  public void setSocketSendBufSize(int size) { this.socketSendBufferSize = size; }

  /** Starts the service.  Must be called before any calls will be handled. */
  @Override
  public void start() {
    startThreads();
    openServer();
  }

  /**
   * Open a previously started server.
   */
  @Override
  public void openServer() {
    started = true;
  }

  /**
   * Starts the service threads but does not allow requests to be responded yet.
   * Client will get {@link ServerNotRunningYetException} instead.
   */
  @Override
  public synchronized void startThreads() {
    responder.start();
    listener.start();
    handlers = startHandlers(callQueue, handlerCount);
    priorityHandlers = startHandlers(priorityCallQueue, priorityHandlerCount);
    replicationHandlers = startHandlers(replicationQueue, numOfReplicationHandlers);
    }

  private Handler[] startHandlers(BlockingQueue<Call> queue, int numOfHandlers) {
    if (numOfHandlers <= 0) {
      return null;
    }
    Handler[] handlers = new Handler[numOfHandlers];
    for (int i = 0; i < numOfHandlers; i++) {
      handlers[i] = new Handler(queue, i);
      handlers[i].start();
    }
    return handlers;
  }

  /** Stops the service.  No new calls will be handled after this is called. */
  @Override
  public synchronized void stop() {
    LOG.info("Stopping server on " + port);
    running = false;
    stopHandlers(handlers);
    stopHandlers(priorityHandlers);
    stopHandlers(replicationHandlers);
    listener.interrupt();
    listener.doStop();
    responder.interrupt();
    notifyAll();
    if (this.rpcMetrics != null) {
      this.rpcMetrics.shutdown();
    }
  }

  private void stopHandlers(Handler[] handlers) {
    if (handlers != null) {
      for (Handler handler : handlers) {
        if (handler != null) {
          handler.interrupt();
        }
      }
    }
  }

  /** Wait for the server to be stopped.
   * Does not wait for all subthreads to finish.
   *  See {@link #stop()}.
   * @throws InterruptedException e
   */
  @Override
  public synchronized void join() throws InterruptedException {
    while (running) {
      wait();
    }
  }

  /**
   * Return the socket (ip+port) on which the RPC server is listening to.
   * @return the socket (ip+port) on which the RPC server is listening to.
   */
  @Override
  public synchronized InetSocketAddress getListenerAddress() {
    return listener.getAddress();
  }

  /**
   * Set the handler for calling out of RPC for error conditions.
   * @param handler the handler implementation
   */
  @Override
  public void setErrorHandler(HBaseRPCErrorHandler handler) {
    this.errorHandler = handler;
  }

  /**
   * Returns the metrics instance for reporting RPC call statistics
   */
  public HBaseRpcMetrics getRpcMetrics() {
    return rpcMetrics;
  }

  /**
   * When the read or write buffer size is larger than this limit, i/o will be
   * done in chunks of this size. Most RPC requests and responses would be
   * be smaller.
   */
  private static int NIO_BUFFER_LIMIT = 64 * 1024; //should not be more than 64KB.

  /**
   * This is a wrapper around {@link java.nio.channels.WritableByteChannel#write(java.nio.ByteBuffer)}.
   * If the amount of data is large, it writes to channel in smaller chunks.
   * This is to avoid jdk from creating many direct buffers as the size of
   * buffer increases. This also minimizes extra copies in NIO layer
   * as a result of multiple write operations required to write a large
   * buffer.
   *
   * @param channel writable byte channel to write to
   * @param buffer buffer to write
   * @return number of bytes written
   * @throws java.io.IOException e
   * @see java.nio.channels.WritableByteChannel#write(java.nio.ByteBuffer)
   */
  protected int channelWrite(WritableByteChannel channel,
                                    ByteBuffer buffer) throws IOException {

    int count =  (buffer.remaining() <= NIO_BUFFER_LIMIT) ?
           channel.write(buffer) : channelIO(null, channel, buffer);
    if (count > 0) {
      rpcMetrics.sentBytes.inc(count);
    }
    return count;
  }

  /**
   * This is a wrapper around {@link java.nio.channels.ReadableByteChannel#read(java.nio.ByteBuffer)}.
   * If the amount of data is large, it writes to channel in smaller chunks.
   * This is to avoid jdk from creating many direct buffers as the size of
   * ByteBuffer increases. There should not be any performance degredation.
   *
   * @param channel writable byte channel to write on
   * @param buffer buffer to write
   * @return number of bytes written
   * @throws java.io.IOException e
   * @see java.nio.channels.ReadableByteChannel#read(java.nio.ByteBuffer)
   */
  protected int channelRead(ReadableByteChannel channel,
                                   ByteBuffer buffer) throws IOException {

    int count = (buffer.remaining() <= NIO_BUFFER_LIMIT) ?
           channel.read(buffer) : channelIO(channel, null, buffer);
    if (count > 0) {
      rpcMetrics.receivedBytes.inc(count);
  }
    return count;
  }

  /**
   * Helper for {@link #channelRead(java.nio.channels.ReadableByteChannel, java.nio.ByteBuffer)}
   * and {@link #channelWrite(java.nio.channels.WritableByteChannel, java.nio.ByteBuffer)}. Only
   * one of readCh or writeCh should be non-null.
   *
   * @param readCh read channel
   * @param writeCh write channel
   * @param buf buffer to read or write into/out of
   * @return bytes written
   * @throws java.io.IOException e
   * @see #channelRead(java.nio.channels.ReadableByteChannel, java.nio.ByteBuffer)
   * @see #channelWrite(java.nio.channels.WritableByteChannel, java.nio.ByteBuffer)
   */
  private static int channelIO(ReadableByteChannel readCh,
                               WritableByteChannel writeCh,
                               ByteBuffer buf) throws IOException {

    int originalLimit = buf.limit();
    int initialRemaining = buf.remaining();
    int ret = 0;

    while (buf.remaining() > 0) {
      try {
        int ioSize = Math.min(buf.remaining(), NIO_BUFFER_LIMIT);
        buf.limit(buf.position() + ioSize);

        ret = (readCh == null) ? writeCh.write(buf) : readCh.read(buf);

        if (ret < ioSize) {
          break;
        }

      } finally {
        buf.limit(originalLimit);
      }
    }

    int nBytes = initialRemaining - buf.remaining();
    return (nBytes > 0) ? nBytes : ret;
  }

  /**
   * Needed for delayed calls.  We need to be able to store the current call
   * so that we can complete it later.
   * @return Call the server is currently handling.
   */
  public static RpcCallContext getCurrentCall() {
    return CurCall.get();
  }

  public long getResponseQueueSize(){
    return responseQueuesSizeThrottler.getCurrentValue();
  }
}
