/*
 * (c) Copyright 2020 Palantir Technologies Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.palantir.timestamp;

import static com.palantir.timestamp.TimestampManagementService.SENTINEL_TIMESTAMP_STRING;

import javax.annotation.CheckReturnValue;
import javax.annotation.meta.When;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;

import com.palantir.logsafe.Safe;

@Path("/")
public class ManagedTimestampResource {
    private final ManagedTimestampService managedTimestampService;

    public ManagedTimestampResource(ManagedTimestampService managedTimestampService) {
        this.managedTimestampService = managedTimestampService;
    }

    @POST // This has to be POST because we can't allow caching.
    @Path("/timestamp/fresh-timestamp")
    @Produces(MediaType.APPLICATION_JSON)
    public long getFreshTimestamp() {
        return managedTimestampService.getFreshTimestamp();
    }

    @POST // This has to be POST because we can't allow caching.
    @Path("/timestamp/fresh-timestamps")
    @Produces(MediaType.APPLICATION_JSON)
    public TimestampRange getFreshTimestamps(@Safe @QueryParam("number") int numTimestampsRequested) {
        return managedTimestampService.getFreshTimestamps(numTimestampsRequested);
    }

    @POST
    @Path("/timestamp-management/fast-forward")
    @Produces(MediaType.APPLICATION_JSON)
    public void fastForwardTimestamp(
            @Safe @QueryParam("currentTimestamp") @DefaultValue(SENTINEL_TIMESTAMP_STRING) long currentTimestamp) {
        managedTimestampService.fastForwardTimestamp(currentTimestamp);
    }

    @GET
    @Path("/timestamp-management/ping")
    @Produces(MediaType.TEXT_PLAIN)
    @CheckReturnValue(when = When.NEVER)
    public String ping() {
        return managedTimestampService.ping();
    }
}
