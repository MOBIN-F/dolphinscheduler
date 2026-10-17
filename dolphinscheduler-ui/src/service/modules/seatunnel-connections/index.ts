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

import { axios } from '@/service/service'
import type {
  BrowseNode,
  ConnectionTypeDef,
  ObjectDescription,
  SeatunnelConnection,
  SeatunnelConnectionReq
} from './types'

const JSON_HEADERS = { 'Content-Type': 'application/json' }

/**
 * Connecting to Kafka or a database and sampling may take longer than the default timeout
 */
const BROWSE_TIMEOUT = 60000

export function queryConnectionTypes(): Promise<ConnectionTypeDef[]> {
  return axios({ url: '/seatunnel-connections/types', method: 'get' }) as any
}

/**
 * All connections for an administrator, the authorized ones for other users
 */
export function queryConnections(): Promise<SeatunnelConnection[]> {
  return axios({ url: '/seatunnel-connections', method: 'get' }) as any
}

export function createConnection(
  data: SeatunnelConnectionReq
): Promise<SeatunnelConnection> {
  return axios({
    url: '/seatunnel-connections',
    method: 'post',
    headers: JSON_HEADERS,
    data: JSON.stringify(data)
  }) as any
}

export function updateConnection(
  code: number,
  data: SeatunnelConnectionReq
): Promise<SeatunnelConnection> {
  return axios({
    url: `/seatunnel-connections/${code}`,
    method: 'put',
    headers: JSON_HEADERS,
    data: JSON.stringify(data)
  }) as any
}

export function deleteConnection(code: number): Promise<void> {
  return axios({
    url: `/seatunnel-connections/${code}`,
    method: 'delete'
  }) as any
}

export function testConnection(
  data: SeatunnelConnectionReq,
  code?: number
): Promise<void> {
  return axios({
    url: '/seatunnel-connections/test',
    method: 'post',
    params: code ? { code } : {},
    headers: JSON_HEADERS,
    data: JSON.stringify(data),
    timeout: BROWSE_TIMEOUT
  }) as any
}

export function authorizedConnections(params: {
  userId: number
}): Promise<SeatunnelConnection[]> {
  return axios({
    url: '/seatunnel-connections/authorized',
    method: 'get',
    params
  }) as any
}

export function unauthorizedConnections(params: {
  userId: number
}): Promise<SeatunnelConnection[]> {
  return axios({
    url: '/seatunnel-connections/unauthorized',
    method: 'get',
    params
  }) as any
}

export function grantConnections(data: {
  userId: number
  connectionIds: string
}): Promise<void> {
  return axios({
    url: '/seatunnel-connections/grant',
    method: 'post',
    data
  }) as any
}

export function browseConnection(
  connKey: string,
  parent = ''
): Promise<BrowseNode[]> {
  return axios({
    url: `/seatunnel-connections/${connKey}/objects`,
    method: 'get',
    params: { parent },
    timeout: BROWSE_TIMEOUT
  }) as any
}

export function describeConnectionObject(
  connKey: string,
  object: string
): Promise<ObjectDescription> {
  return axios({
    url: `/seatunnel-connections/${connKey}/describe`,
    method: 'post',
    params: { object },
    timeout: BROWSE_TIMEOUT
  }) as any
}
