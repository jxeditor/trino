/*
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
package io.trino.server;

import com.google.inject.Inject;
import io.airlift.node.NodeInfo;
import io.trino.client.NodeVersion;
import io.trino.client.ServerInfo;
import io.trino.metadata.NodeState;
import io.trino.server.security.ResourceSecurity;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Response;

import java.util.Optional;

import static io.airlift.units.Duration.nanosSince;
import static io.trino.metadata.NodeState.ACTIVE;
import static io.trino.metadata.NodeState.SHUTTING_DOWN;
import static io.trino.server.security.ResourceSecurity.AccessType.MANAGEMENT_WRITE;
import static io.trino.server.security.ResourceSecurity.AccessType.PUBLIC;
import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;
import static jakarta.ws.rs.core.MediaType.TEXT_PLAIN;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

@Path("/v1/info")
public class ServerInfoResource
{
    private final NodeVersion version;
    private final String environment;
    private final boolean coordinator;
    private final GracefulShutdownHandler shutdownHandler;
    private final StartupStatus startupStatus;
    private final long startTime = System.nanoTime();

    @Inject
    public ServerInfoResource(NodeVersion nodeVersion, NodeInfo nodeInfo, ServerConfig serverConfig, GracefulShutdownHandler shutdownHandler, StartupStatus startupStatus)
    {
        this.version = requireNonNull(nodeVersion, "nodeVersion is null");
        this.environment = nodeInfo.getEnvironment();
        this.coordinator = serverConfig.isCoordinator();
        this.shutdownHandler = requireNonNull(shutdownHandler, "shutdownHandler is null");
        this.startupStatus = requireNonNull(startupStatus, "startupStatus is null");
    }

    @ResourceSecurity(PUBLIC)
    @GET
    @Produces(APPLICATION_JSON)
    public ServerInfo getInfo()
    {
        boolean starting = !startupStatus.isStartupComplete();
        return new ServerInfo(version, environment, coordinator, starting, Optional.of(nanosSince(startTime)));
    }

    @ResourceSecurity(MANAGEMENT_WRITE)
    @PUT
    @Path("state")
    @Consumes(APPLICATION_JSON)
    @Produces(TEXT_PLAIN)
    public Response updateState(NodeState state)
    {
        requireNonNull(state, "state is null");
        return switch (state) {
            case SHUTTING_DOWN -> {
                shutdownHandler.requestShutdown();
                yield Response.ok().build();
            }
            case ACTIVE, INACTIVE -> throw new BadRequestException(format("Invalid state transition to %s", state));
        };
    }

    @ResourceSecurity(PUBLIC)
    @GET
    @Path("state")
    @Produces(APPLICATION_JSON)
    public NodeState getServerState()
    {
        if (shutdownHandler.isShutdownRequested()) {
            return SHUTTING_DOWN;
        }
        return ACTIVE;
    }

    @ResourceSecurity(PUBLIC)
    @GET
    @Path("coordinator")
    @Produces(TEXT_PLAIN)
    public Response getServerCoordinator()
    {
        if (coordinator) {
            return Response.ok().build();
        }
        // return 404 to allow load balancers to only send traffic to the coordinator
        throw new NotFoundException();
    }
}
