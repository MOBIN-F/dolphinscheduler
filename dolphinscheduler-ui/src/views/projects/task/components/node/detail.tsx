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

import {
  defineComponent,
  ref,
  watch,
  inject,
  Ref,
  unref,
  computed,
  onBeforeUnmount
} from 'vue'
import { useI18n } from 'vue-i18n'
import Form from '@/components/form'
import { useTask } from './use-task'
import { useTaskNodeStore } from '@/store/project/task-node'
import AiChatPanel from '@/components/ai-assistant/chat-panel'
import AiDiffPanel from '@/components/ai-assistant/diff-panel'
import { useAiStatus } from '@/components/ai-assistant/use-ai-status'
import { useSeatunnelAi } from './seatunnel-ai/use-seatunnel-ai'
import type { ITaskData, EditWorkflowDefinition } from './types'

interface IDetailPanel {
  projectCode: number
  data: ITaskData
  readonly: false
  from: number
  detailRef?: Ref
  definition?: EditWorkflowDefinition
}

const NodeDetail = defineComponent({
  name: 'NodeDetail',
  emits: ['taskTypeChange'],
  setup(props, { expose, emit }) {
    const taskStore = useTaskNodeStore()

    const formRef = ref()
    const detailData: IDetailPanel = inject('data') || {
      projectCode: 0,
      data: {
        taskType: 'SHELL'
      },
      readonly: false,
      from: 0
    }
    const { data, projectCode, from, readonly, definition } = unref(detailData)

    const { elementsRef, rulesRef, model } = useTask({
      data,
      projectCode,
      from,
      readonly,
      definition
    })
    watch(
      () => model.taskType,
      async (taskType) => {
        taskStore.updateName(model.name || '')
        emit('taskTypeChange', taskType)
      }
    )

    // Show the script editor on the left and the other settings on the right
    const splitPaths = computed(() =>
      model.taskType === 'SEATUNNEL' ? ['rawScript', 'resourceList'] : []
    )

    // With the AI assistant: settings | AI chat | script editor (or the diff of the AI proposal)
    const { t } = useI18n()
    const aiStatus = useAiStatus()
    const seatunnelAiEnabled = computed(
      () =>
        model.taskType === 'SEATUNNEL' &&
        !!aiStatus.value?.enabled &&
        !unref(readonly)
    )
    const seatunnelAi = useSeatunnelAi(model, projectCode)
    onBeforeUnmount(() => seatunnelAi.stop())

    const renderAiChat = () => (
      <AiChatPanel
        session={seatunnelAi}
        title={t('project.node.ai_seatunnel_title')}
        placeholder={t('project.node.ai_seatunnel_placeholder')}
        examples={[
          t('project.node.ai_seatunnel_example_1'),
          t('project.node.ai_seatunnel_example_2'),
          t('project.node.ai_seatunnel_example_3')
        ]}
      />
    )
    const renderAiDiff = () => {
      const proposal = seatunnelAi.pending.value?.proposal
      return proposal ? (
        <AiDiffPanel
          original={model.useCustom ? model.rawScript || '' : ''}
          proposal={proposal}
          onAccept={(script: string) => seatunnelAi.accept(script)}
          onReject={() => seatunnelAi.reject()}
        />
      ) : null
    }

    expose(formRef)

    return () => (
      <Form
        ref={formRef}
        meta={{
          model,
          rules: rulesRef.value,
          elements: elementsRef.value,
          disabled: unref(readonly)
        }}
        layout={{
          xGap: 10
        }}
        splitPaths={splitPaths.value}
        middle={seatunnelAiEnabled.value ? renderAiChat : undefined}
        splitOverlay={seatunnelAiEnabled.value ? renderAiDiff : undefined}
      />
    )
  }
})

export default NodeDetail
