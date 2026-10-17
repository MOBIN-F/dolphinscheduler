/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.dolphinscheduler.api.controller;

import org.apache.dolphinscheduler.ai.connection.BrowseNode;
import org.apache.dolphinscheduler.ai.connection.ObjectDescription;
import org.apache.dolphinscheduler.api.dto.seatunnel.SeatunnelConnectionRequest;
import org.apache.dolphinscheduler.api.dto.seatunnel.SeatunnelConnectionVO;
import org.apache.dolphinscheduler.api.service.seatunnel.SeatunnelConnectionService;
import org.apache.dolphinscheduler.api.utils.Result;
import org.apache.dolphinscheduler.common.constants.Constants;
import org.apache.dolphinscheduler.dao.entity.User;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * SeaTunnel connections of the security center, used by the SeaTunnel AI assistant. Managed by the
 * administrators, used by the authorized users.
 */
@Tag(name = "SEATUNNEL_CONNECTION_TAG")
@RestController
@RequestMapping("/seatunnel-connections")
public class SeatunnelConnectionController extends BaseController {

    private final SeatunnelConnectionService connectionService;

    public SeatunnelConnectionController(SeatunnelConnectionService connectionService) {
        this.connectionService = connectionService;
    }

    @Operation(summary = "seatunnelConnectionTypes", description = "SEATUNNEL_CONNECTION_TYPES_NOTES")
    @GetMapping(value = "/types")
    @ResponseStatus(HttpStatus.OK)
    public Result<List<Map<String, Object>>> types(@Parameter(hidden = true) @RequestAttribute(value = Constants.SESSION_USER) User loginUser) {
        return Result.success(connectionService.types());
    }

    @Operation(summary = "querySeatunnelConnections", description = "QUERY_SEATUNNEL_CONNECTIONS_NOTES")
    @GetMapping
    @ResponseStatus(HttpStatus.OK)
    public Result<List<SeatunnelConnectionVO>> list(@Parameter(hidden = true) @RequestAttribute(value = Constants.SESSION_USER) User loginUser) {
        return Result.success(connectionService.list(loginUser));
    }

    @Operation(summary = "createSeatunnelConnection", description = "CREATE_SEATUNNEL_CONNECTION_NOTES")
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Result<SeatunnelConnectionVO> create(@Parameter(hidden = true) @RequestAttribute(value = Constants.SESSION_USER) User loginUser,
                                                @RequestBody SeatunnelConnectionRequest request) {
        return Result.success(connectionService.create(loginUser, request));
    }

    @Operation(summary = "updateSeatunnelConnection", description = "UPDATE_SEATUNNEL_CONNECTION_NOTES")
    @PutMapping(value = "/{code}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.OK)
    public Result<SeatunnelConnectionVO> update(@Parameter(hidden = true) @RequestAttribute(value = Constants.SESSION_USER) User loginUser,
                                                @PathVariable("code") long code,
                                                @RequestBody SeatunnelConnectionRequest request) {
        return Result.success(connectionService.update(loginUser, code, request));
    }

    @Operation(summary = "deleteSeatunnelConnection", description = "DELETE_SEATUNNEL_CONNECTION_NOTES")
    @DeleteMapping(value = "/{code}")
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> delete(@Parameter(hidden = true) @RequestAttribute(value = Constants.SESSION_USER) User loginUser,
                               @PathVariable("code") long code) {
        connectionService.delete(loginUser, code);
        return Result.success();
    }

    @Operation(summary = "testSeatunnelConnection", description = "TEST_SEATUNNEL_CONNECTION_NOTES")
    @PostMapping(value = "/test", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> test(@Parameter(hidden = true) @RequestAttribute(value = Constants.SESSION_USER) User loginUser,
                             @RequestParam(value = "code", required = false) Long code,
                             @RequestBody SeatunnelConnectionRequest request) {
        connectionService.test(loginUser, request, code);
        return Result.success();
    }

    @Operation(summary = "authorizedSeatunnelConnections", description = "AUTHORIZED_SEATUNNEL_CONNECTIONS_NOTES")
    @GetMapping(value = "/authorized")
    @ResponseStatus(HttpStatus.OK)
    public Result<List<SeatunnelConnectionVO>> authorized(@Parameter(hidden = true) @RequestAttribute(value = Constants.SESSION_USER) User loginUser,
                                                          @RequestParam("userId") int userId) {
        return Result.success(connectionService.authorized(loginUser, userId));
    }

    @Operation(summary = "unauthorizedSeatunnelConnections", description = "UNAUTHORIZED_SEATUNNEL_CONNECTIONS_NOTES")
    @GetMapping(value = "/unauthorized")
    @ResponseStatus(HttpStatus.OK)
    public Result<List<SeatunnelConnectionVO>> unauthorized(@Parameter(hidden = true) @RequestAttribute(value = Constants.SESSION_USER) User loginUser,
                                                            @RequestParam("userId") int userId) {
        return Result.success(connectionService.unauthorized(loginUser, userId));
    }

    @Operation(summary = "grantSeatunnelConnections", description = "GRANT_SEATUNNEL_CONNECTIONS_NOTES")
    @PostMapping(value = "/grant")
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> grant(@Parameter(hidden = true) @RequestAttribute(value = Constants.SESSION_USER) User loginUser,
                              @RequestParam("userId") int userId,
                              @RequestParam(value = "connectionIds", required = false) String connectionIds) {
        connectionService.grant(loginUser, userId, connectionIds);
        return Result.success();
    }

    @Operation(summary = "browseSeatunnelConnection", description = "BROWSE_SEATUNNEL_CONNECTION_NOTES")
    @GetMapping(value = "/{connKey}/objects")
    @ResponseStatus(HttpStatus.OK)
    public Result<List<BrowseNode>> objects(@Parameter(hidden = true) @RequestAttribute(value = Constants.SESSION_USER) User loginUser,
                                            @PathVariable("connKey") String connKey,
                                            @RequestParam(value = "parent", required = false, defaultValue = "") String parent) {
        return Result.success(connectionService.objects(loginUser, connKey, parent));
    }

    @Operation(summary = "describeSeatunnelConnectionObject", description = "DESCRIBE_SEATUNNEL_CONNECTION_OBJECT_NOTES")
    @PostMapping(value = "/{connKey}/describe")
    @ResponseStatus(HttpStatus.OK)
    public Result<ObjectDescription> describe(@Parameter(hidden = true) @RequestAttribute(value = Constants.SESSION_USER) User loginUser,
                                              @PathVariable("connKey") String connKey,
                                              @RequestParam("object") String object) {
        return Result.success(connectionService.describe(loginUser, connKey, object));
    }
}
