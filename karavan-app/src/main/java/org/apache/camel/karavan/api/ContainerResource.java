/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.karavan.api;

import io.smallrye.mutiny.Multi;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.core.eventbus.EventBus;
import io.vertx.mutiny.core.eventbus.Message;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.apache.camel.karavan.manager.docker.DockerManager;
import org.apache.camel.karavan.manager.kubernetes.KubernetesManager;
import org.apache.camel.karavan.project.DockerComposeConverter;
import org.apache.camel.karavan.project.ProjectService;
import org.apache.camel.karavan.project.model.DockerComposeService;
import org.apache.camel.karavan.manager.ProjectManager;
import org.apache.camel.karavan.config.ConfigService;
import org.apache.camel.karavan.status.StatusCache;
import org.apache.camel.karavan.status.StatusConstants;
import org.apache.camel.karavan.status.model.ContainerStatus;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.*;
import java.util.stream.Collectors;

import static org.apache.camel.karavan.manager.ManagerConstants.*;
import static org.apache.camel.karavan.status.StatusEvents.CONTAINER_UPDATED;

@Path("/ui/container")
public class ContainerResource {

    @Inject
    EventBus eventBus;

    @Inject
    StatusCache statusCache;

    @Inject
    KubernetesManager kubernetesManager;

    @Inject
    DockerManager dockerManager;

    @Inject
    ProjectManager projectManager;

    @Inject
    ProjectService projectService;

    @ConfigProperty(name = "karavan.environment")
    String environment;

    private static final Logger LOGGER = Logger.getLogger(ContainerResource.class.getName());

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public List<ContainerStatus> getAllContainerStatuses() throws Exception {
        return statusCache.getContainerStatuses().stream()
                .sorted(Comparator.comparing(ContainerStatus::getProjectId))
                .collect(Collectors.toList());
    }

    @POST
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Path("/{projectId}/{type}/{name}")
    public Response manageContainer(@PathParam("projectId") String projectId, @PathParam("type") String type, @PathParam("name") String name, JsonObject command) {
        try {
            if (ConfigService.inKubernetes()) {
                if (command.getString("command").equalsIgnoreCase("delete")) {
                    kubernetesManager.deletePod(name);
                    return Response.ok().build();
                }
            } else {
                // set container statuses
                setContainerStatusTransit(projectId, name, type);
                // exec docker commands
                if (command.containsKey("command")) {
                    if (command.getString("command").equalsIgnoreCase("deploy")) {
                        deployContainer(projectId, type, command);
                    } else if (command.getString("command").equalsIgnoreCase("run")) {
                        dockerManager.runContainer(name);
                    } else if (command.getString("command").equalsIgnoreCase("stop")) {
                        dockerManager.stopContainer(name);
                    } else if (command.getString("command").equalsIgnoreCase("pause")) {
                        dockerManager.pauseContainer(name);
                    } else if (command.getString("command").equalsIgnoreCase("delete")) {
                        dockerManager.deleteContainer(name);
                    }
                    return Response.ok().build();
                }
            }
            return Response.ok().build();
        } catch (Exception e) {
            return Response.serverError().entity(e.getMessage()).build();
        }
    }

    private void deployContainer(String projectId, String type, JsonObject command) throws InterruptedException {
        if (Objects.equals(type, ContainerStatus.ContainerType.devservice.name())) {
            String code = projectService.getDevServiceCode();
            DockerComposeService dockerComposeService = DockerComposeConverter.fromCode(code, projectId);
            if (dockerComposeService != null) {
                Map<String, String> labels = new HashMap<>();
                labels.put(LABEL_TYPE, ContainerStatus.ContainerType.devservice.name());
                labels.put(LABEL_CAMEL_RUNTIME, StatusConstants.CamelRuntime.CAMEL_MAIN.getValue());
                labels.put(LABEL_PROJECT_ID, projectId);
                dockerManager.createContainerFromCompose(dockerComposeService, labels, needPull(command));
                dockerManager.runContainer(dockerComposeService.getContainer_name());
            }
        } else if (Objects.equals(type, ContainerStatus.ContainerType.project.name())) {
            DockerComposeService dockerComposeService = projectService.getProjectDockerComposeService(projectId);
            if (dockerComposeService != null) {
                Map<String, String> labels = new HashMap<>();
                labels.put(LABEL_TYPE, ContainerStatus.ContainerType.project.name());
                labels.put(LABEL_CAMEL_RUNTIME, StatusConstants.CamelRuntime.CAMEL_MAIN.getValue());
                labels.put(LABEL_PROJECT_ID, projectId);
                dockerManager.createContainerFromCompose(dockerComposeService, labels, needPull(command));
                dockerManager.runContainer(dockerComposeService.getContainer_name());
            }
        } else if (Objects.equals(type, ContainerStatus.ContainerType.devmode.name())) {
//                        TODO: merge with DevMode service
//                        dockerForKaravan.createDevmodeContainer(name, "");
//                        dockerService.runContainer(name);
        }
    }

    private boolean needPull(JsonObject command) {
        if (command != null && command.containsKey("pullImage")) {
            return command.getBoolean("pullImage");
        }
        return false;
    }

    private void setContainerStatusTransit(String projectId, String name, String type) {
        ContainerStatus status = statusCache.getContainerStatus(projectId, environment, name);
        if (status == null) {
            status = ContainerStatus.createByType(projectId, environment, ContainerStatus.ContainerType.valueOf(type));
        }
        status.setInTransit(true);
        eventBus.publish(CONTAINER_UPDATED, JsonObject.mapFrom(status));
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Path("/{env}")
    public List<ContainerStatus> getContainerStatusesByEnv(@PathParam("env") String env) throws Exception {
        return statusCache.getContainerStatuses(env).stream()
                .sorted(Comparator.comparing(ContainerStatus::getProjectId))
                .collect(Collectors.toList());
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Path("/{projectId}/{env}")
    public List<ContainerStatus> getContainerStatusesByProjectAndEnv(@PathParam("projectId") String projectId, @PathParam("env") String env) throws Exception {
        return statusCache.getContainerStatuses(projectId, env).stream()
                .sorted(Comparator.comparing(ContainerStatus::getContainerName))
                .collect(Collectors.toList());
    }

    @DELETE
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Path("/{projectId}/{type}/{name}")
    public Response deleteContainer(@PathParam("projectId") String projectId, @PathParam("type") String type, @PathParam("name") String name) {
        // set container statuses
        setContainerStatusTransit(projectId, name, type);
        try {
            if (ConfigService.inKubernetes()) {
                kubernetesManager.deletePod(name);
            } else {
                dockerManager.deleteContainer(name);
            }
            return Response.accepted().build();
        } catch (Exception e) {
            LOGGER.error(e.getMessage());
            return Response.notModified().build();
        }
    }

    // TODO: implement log watch
    @GET
    @Path("/log/watch/{env}/{name}")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    public Multi<String> getContainerLogWatch(@PathParam("env") String env, @PathParam("name") String name) {
        LOGGER.info("Start sourcing");
        return eventBus.<String>consumer(name + "-" + kubernetesManager.getNamespace()).toMulti().map(Message::body);
    }
}