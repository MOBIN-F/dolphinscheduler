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

import type { ObjectDescription } from '@/service/modules/seatunnel-connections/types'

interface AiStatus {
  enabled: boolean
  seatunnelEngineValidation: boolean
  seatunnelVersion: string
}

interface AiConversationQuery {
  projectCode: number
  taskCode: number
  assistant: string
}

/**
 * A saved message, the generated scripts and the checkpoints are not saved
 */
interface SavedMessage {
  role: 'user' | 'assistant'
  content: string
  userName?: string
  time?: number
  /**
   * A note of a user action, e.g. the selected source was changed
   */
  note?: boolean
  /**
   * The progress steps, without the details
   */
  steps?: {
    kind: string
    state: string
    args?: Record<string, any>
    result?: Record<string, any>
    millis?: number
  }[]
  proposalState?: string
  valid?: boolean
  fixAttempts?: number
  error?: string
}

/**
 * The connection selected for the source or the sink, without the raw samples
 */
interface ConnectionSelectionReq {
  connectionKey: string
  objects: string[]
  newTable?: string
  path?: string
  fileFormat?: string
  descriptions?: Omit<ObjectDescription, 'samples'>[]
}

interface ConnectionContextReq {
  source?: ConnectionSelectionReq | null
  sink?: ConnectionSelectionReq | null
}

interface AiConversation {
  messages: SavedMessage[]
  updateTime: string | null
  updateUserName: string | null
  context?: ConnectionContextReq | null
}

export {
  AiStatus,
  AiConversationQuery,
  SavedMessage,
  AiConversation,
  ConnectionSelectionReq,
  ConnectionContextReq
}
