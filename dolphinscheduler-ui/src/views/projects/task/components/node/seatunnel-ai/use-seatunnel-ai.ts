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

import { useAiSession } from '@/components/ai-assistant/use-ai-session'
import {
  deleteAiConversation,
  getAiConversation,
  saveAiConversation
} from '@/service/modules/ai'
import { useUserStore } from '@/store/user/user'
import type { UserInfoRes } from '@/service/modules/users/types'
import type { INodeData } from '../types'

/**
 * The AI assistant session of a SeaTunnel task node: the config is generated or modified in the rawScript field, the
 * conversation is saved for the task and shared by its editors.
 */
export function useSeatunnelAi(
  model: INodeData,
  projectCode: number,
  taskCode?: number
) {
  const userStore = useUserStore()
  const query = taskCode
    ? { projectCode, taskCode, assistant: 'seatunnel' }
    : null
  return useAiSession({
    url: '/ai/seatunnel/chat',
    buildBody: (userInput, history) => ({
      projectCode,
      history,
      userInput,
      currentScript: model.useCustom ? model.rawScript || '' : '',
      context: {
        startupScript: model.startupScript || '',
        deployMode: model.deployMode || '',
        runMode: model.runMode || '',
        master: model.master || ''
      }
    }),
    getScript: () => model.rawScript || '',
    setScript: (script: string) => {
      model.useCustom = true
      model.rawScript = script
    },
    userName: () => (userStore.getUserInfo as UserInfoRes)?.userName || '',
    persistence: query
      ? {
          load: () => getAiConversation(query),
          save: (messages) => saveAiConversation(query, messages),
          clear: () => deleteAiConversation(query)
        }
      : undefined
  })
}
