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
  AiConversation,
  AiConversationQuery,
  AiStatus,
  ConnectionContextReq,
  SavedMessage
} from './types'

export function getAiStatus(): Promise<AiStatus> {
  return axios({
    url: '/ai/status',
    method: 'get'
  }) as unknown as Promise<AiStatus>
}

export function getAiConversation(
  params: AiConversationQuery
): Promise<AiConversation> {
  return axios({
    url: '/ai/conversations',
    method: 'get',
    params
  }) as unknown as Promise<AiConversation>
}

export function saveAiConversation(
  params: AiConversationQuery,
  messages: SavedMessage[],
  context?: ConnectionContextReq | null
): Promise<void> {
  return axios({
    url: '/ai/conversations',
    method: 'put',
    headers: { 'Content-Type': 'application/json' },
    data: JSON.stringify({ ...params, messages, context })
  }) as unknown as Promise<void>
}

export function deleteAiConversation(
  params: AiConversationQuery
): Promise<void> {
  return axios({
    url: '/ai/conversations',
    method: 'delete',
    params
  }) as unknown as Promise<void>
}
