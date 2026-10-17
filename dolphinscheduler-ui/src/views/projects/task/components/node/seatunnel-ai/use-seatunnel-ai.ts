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

import { useI18n } from 'vue-i18n'
import { useAiSession } from '@/components/ai-assistant/use-ai-session'
import type { AiSession } from '@/components/ai-assistant/use-ai-session'
import { useConnectionContext } from './use-connection-context'
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
 * conversation is saved for the task and shared by its editors. A read-only node only shows the saved conversation.
 */
export function useSeatunnelAi(
  model: INodeData,
  projectCode: number,
  taskCode?: number,
  readonly = false
) {
  const { t } = useI18n()
  const userStore = useUserStore()
  const query = taskCode
    ? { projectCode, taskCode, assistant: 'seatunnel' }
    : null
  // eslint-disable-next-line prefer-const
  let session: AiSession
  // The optional source and sink connections, a change is noted in the conversation
  const connections = useConnectionContext((role, summary) => {
    const side =
      role === 'SOURCE'
        ? t('project.node.ai_context_source')
        : t('project.node.ai_context_sink')
    session.addNote(
      summary
        ? t('project.node.ai_context_changed', { role: side, summary })
        : t('project.node.ai_context_cleared', { role: side })
    )
  })
  session = useAiSession({
    url: '/ai/seatunnel/chat',
    buildBody: (userInput, history) => ({
      projectCode,
      // E.g. the Kafka consumer group of the job is derived from the task
      taskCode,
      history,
      userInput,
      currentScript: model.useCustom ? model.rawScript || '' : '',
      context: {
        startupScript: model.startupScript || '',
        deployMode: model.deployMode || '',
        runMode: model.runMode || '',
        master: model.master || ''
      },
      connections: connections.toContext()
    }),
    getScript: () => model.rawScript || '',
    setScript: (script: string) => {
      model.useCustom = true
      model.rawScript = script
    },
    userName: () => (userStore.getUserInfo as UserInfoRes)?.userName || '',
    readonly,
    persistence: query
      ? {
          load: async () => {
            const conversation = await getAiConversation(query)
            connections.apply(conversation.context)
            return conversation
          },
          save: (messages) =>
            saveAiConversation(query, messages, connections.toContext()),
          clear: () => deleteAiConversation(query)
        }
      : undefined
  })
  const loadSession = session.load
  return Object.assign(session, {
    connections,
    /**
     * Load the connections the user may use, then the saved conversation and selection of the task
     */
    load: async () => {
      await connections.load()
      await loadSession()
    }
  })
}
