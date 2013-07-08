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
/**
Provides HBase <a href="http://wiki.apache.org/hadoop/HadoopMapReduce">MapReduce</a>
Input/OutputFormats, a table indexing MapReduce job, and utility

<h2>Table of Contents</h2>
<ul>
<li><a href="#classpath">HBase, MapReduce and the CLASSPATH</a></li>
<li><a href="#sink">HBase as MapReduce job data source and sink</a></li>
<li><a href="#examples">Example Code</a></li>
</ul>

<h2><a name="classpath">HBase, MapReduce and the CLASSPATH</a></h2>

<p>MapReduce jobs deployed to a MapReduce cluster do not by default have access
to the HBase configuration under <code>$HBASE_CONF_DIR</code> nor to HBase classes.
You could add <code>hbase-site.xml</code> to $HADOOP_HOME/conf and add
<code>hbase-X.X.X.jar</code> to the <code>$HADOOP_HOME/lib</code> and copy these
changes across your cluster but the cleanest means of adding hbase configuration
and classes to the cluster <code>CLASSPATH</code> is by uncommenting
<code>HADOOP_CLASSPATH</code> in <code>$HADOOP_HOME/conf/hadoop-env.sh</code>
adding hbase dependencies here.  For example, here is how you would amend
<code>hadoop-env.sh</code> adding the
built hbase jar, zookeeper (needed by hbase client), hbase conf, and the
<code>PerformanceEvaluation</code> class from the built hbase test jar to the
hadoop <code>CLASSPATH</code>:

<blockquote><pre># Extra Java CLASSPATH elements. Optional.
# export HADOOP_CLASSPATH=
export HADOOP_CLASSPATH=$HBASE_HOME/build/hbase-X.X.X.jar:$HBASE_HOME/build/hbase-X.X.X-test.jar:$HBASE_HOME/conf:${HBASE_HOME}/lib/zookeeper-X.X.X.jar</pre></blockquote>

<p>Expand <code>$HBASE_HOME</code> in the above appropriately to suit your
local environment.</p>

<p>After copying the above change around your cluster (and restarting), this is
how you would run the PerformanceEvaluation MR job to put up 4 clients (Presumes
a ready mapreduce cluster):

<blockquote><pre>$HADOOP_HOME/bin/hadoop org.apache.hadoop.hbase.PerformanceEvaluation sequentialWrite 4</pre></blockquote>

The PerformanceEvaluation class wil be found on the CLASSPATH because you
added <code>$HBASE_HOME/build/test</code> to HADOOP_CLASSPATH
</p>

<p>Another possibility, if for example you do not have access to hadoop-env.sh or
are unable to restart the hadoop cluster, is bundling the hbase jar into a mapreduce
job jar adding it and its dependencies under the job jar <code>lib/</code>
directory and the hbase conf into a job jar <code>conf/</code> directory.
</a>

<h2><a name="sink">HBase as MapReduce job data source and sink</a></h2>

<p>HBase can be used as a data source, {@link org.apache.hadoop.hbase.mapred.TableInputFormat TableInputFormat},
and data sink, {@link org.apache.hadoop.hbase.mapred.TableOutputFormat TableOutputFormat}, for MapReduce jobs.
Writing MapReduce jobs that read or write HBase, you'll probably want to subclass
{@link org.apache.hadoop.hbase.mapred.TableMap TableMap} and/or
{@link org.apache.hadoop.hbase.mapred.TableReduce TableReduce}.  See the do-nothing
pass-through classes {@link org.apache.hadoop.hbase.mapred.IdentityTableMap IdentityTableMap} and
{@link org.apache.hadoop.hbase.mapred.IdentityTableReduce IdentityTableReduce} for basic usage.  For a more
involved example, see <code>BuildTableIndex</code>
or review the <code>org.apache.hadoop.hbase.mapred.TestTableMapReduce</code> unit test.
</p>

<p>Running mapreduce jobs that have hbase as source or sink, you'll need to
specify source/sink table and column names in your configuration.</p>

<p>Reading from hbase, the TableInputFormat asks hbase for the list of
regions and makes a map-per-region or <code>mapred.map.tasks maps</code>,
whichever is smaller (If your job only has two maps, up mapred.map.tasks
to a number > number of regions). Maps will run on the adjacent TaskTracker
if you are running a TaskTracer and RegionServer per node.
Writing, it may make sense to avoid the reduce step and write yourself back into
hbase from inside your map. You'd do this when your job does not need the sort
and collation that mapreduce does on the map emitted data; on insert,
hbase 'sorts' so there is no point double-sorting (and shuffling data around
your mapreduce cluster) unless you need to. If you do not need the reduce,
you might just have your map emit counts of records processed just so the
framework's report at the end of your job has meaning or set the number of
reduces to zero and use TableOutputFormat. See example code
below. If running the reduce step makes sense in your case, its usually better
to have lots of reducers so load is spread across the hbase cluster.</p>

<p>There is also a new hbase partitioner that will run as many reducers as
currently existing regions.  The
{@link org.apache.hadoop.hbase.mapred.HRegionPartitioner} is suitable
when your table is large and your upload is not such that it will greatly
alter the number of existing regions when done; other use the default
partitioner.
</p>

<h2><a name="examples">Example Code</a></h2>
<h3>Sample Row Counter</h3>
<p>See {@link org.apache.hadoop.hbase.mapred.RowCounter}.  You should be able to run
it by doing: <code>% ./bin/hadoop jar hbase-X.X.X.jar</code>.  This will invoke
the hbase MapReduce Driver class.  Select 'rowcounter' from the choice of jobs
offered. You may need to add the hbase conf directory to <code>$HADOOP_HOME/conf/hadoop-env.sh#HADOOP_CLASSPATH</code>
so the rowcounter gets pointed at the right hbase cluster (or, build a new jar
with an appropriate hbase-site.xml built into your job jar).
</p>
<h3>PerformanceEvaluation</h3>
<p>See org.apache.hadoop.hbase.PerformanceEvaluation from hbase src/test.  It runs
a mapreduce job to run concurrent clients reading and writing hbase.
</p>

*/
package org.apache.hadoop.hbase.mapred;
