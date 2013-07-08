/*
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

package org.apache.hadoop.yarn.server.resourcemanager.recovery;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.yarn.api.records.impl.pb.ApplicationAttemptStateDataPBImpl;
import org.apache.hadoop.yarn.api.records.impl.pb.ApplicationStateDataPBImpl;

public class NullRMStateStore extends RMStateStore {

  @Override
  protected void initInternal(Configuration conf) throws Exception {
    // Do nothing
  }

  @Override
  protected void closeInternal() throws Exception {
    // Do nothing
  }

  @Override
  public RMState loadState() throws Exception {
    return null;
  }

  @Override
  protected void storeApplicationState(String appId,
      ApplicationStateDataPBImpl appStateData) throws Exception {
    // Do nothing
  }

  @Override
  protected void storeApplicationAttemptState(String attemptId,
      ApplicationAttemptStateDataPBImpl attemptStateData) throws Exception {
    // Do nothing
  }

  @Override
  protected void removeApplicationState(ApplicationState appState)
      throws Exception {
    // Do nothing
  }

}
