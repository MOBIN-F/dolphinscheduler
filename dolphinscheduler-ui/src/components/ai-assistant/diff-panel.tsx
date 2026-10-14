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
  onBeforeUnmount,
  onMounted,
  PropType,
  ref,
  watch
} from 'vue'
import { NButton, NSpace, NTag, NTooltip } from 'naive-ui'
import { useI18n } from 'vue-i18n'
import * as monaco from 'monaco-editor'
// Configures the monaco workers
import '@/components/monaco-editor'
import { useThemeStore } from '@/store/theme/theme'
import styles from './index.module.scss'
import type { Proposal, ValidationResult } from './types'

const props = {
  original: {
    type: String as PropType<string>,
    default: ''
  },
  proposal: {
    type: Object as PropType<Proposal>,
    required: true as const
  },
  height: {
    type: String as PropType<string>,
    default: 'calc(100vh - 300px)'
  },
  language: {
    type: String as PropType<string>,
    default: 'shell'
  }
}

/**
 * Shows the change proposed by the assistant as a diff, the proposed side is editable before accepting.
 */
export default defineComponent({
  name: 'AiDiffPanel',
  props,
  emits: ['accept', 'reject'],
  setup(props, { emit }) {
    const { t } = useI18n()
    const themeStore = useThemeStore()
    const container = ref<HTMLElement>()
    const sideBySide = ref(false)
    let editor: monaco.editor.IStandaloneDiffEditor | null = null
    let originalModel: monaco.editor.ITextModel | null = null
    let modifiedModel: monaco.editor.ITextModel | null = null

    const createEditor = () => {
      if (!container.value) return
      editor = monaco.editor.createDiffEditor(container.value, {
        automaticLayout: true,
        originalEditable: false,
        renderSideBySide: sideBySide.value,
        theme: themeStore.darkTheme ? 'vs-dark' : 'vs',
        scrollBeyondLastLine: false
      })
      originalModel = monaco.editor.createModel(props.original, props.language)
      modifiedModel = monaco.editor.createModel(
        props.proposal.script,
        props.language
      )
      editor.setModel({ original: originalModel, modified: modifiedModel })
    }

    const disposeEditor = () => {
      editor?.dispose()
      originalModel?.dispose()
      modifiedModel?.dispose()
      editor = null
    }

    onMounted(createEditor)
    onBeforeUnmount(disposeEditor)

    watch(
      () => props.proposal,
      () => {
        modifiedModel?.setValue(props.proposal.script)
        originalModel?.setValue(props.original)
      }
    )
    watch(sideBySide, (value) =>
      editor?.updateOptions({ renderSideBySide: value })
    )

    const onAccept = () =>
      emit('accept', modifiedModel?.getValue() ?? props.proposal.script)
    const onReject = () => emit('reject')

    const renderValidation = (result: ValidationResult) => {
      const label = t(
        result.stage === 'engine'
          ? 'project.node.ai_validation_engine'
          : 'project.node.ai_validation_local'
      )
      const errors = result.issues.filter((issue) => issue.level === 'ERROR')
      const warnings = result.issues.filter(
        (issue) => issue.level === 'WARNING'
      )
      const type = result.skipped
        ? 'default'
        : errors.length
        ? 'error'
        : warnings.length
        ? 'warning'
        : 'success'
      const text = result.skipped
        ? t('project.node.ai_validation_skipped')
        : errors.length
        ? t('project.node.ai_validation_failed')
        : warnings.length
        ? t('project.node.ai_validation_warnings', { count: warnings.length })
        : t('project.node.ai_validation_passed')
      const detail = result.skipped
        ? result.skipReason
        : result.issues
            .map((issue) => `[${issue.location}] ${issue.message}`)
            .join('\n')
      const tag = (
        <NTag size='small' type={type} bordered={false}>
          {label}: {text}
        </NTag>
      )
      return detail ? (
        <NTooltip style={{ maxWidth: '520px', whiteSpace: 'pre-wrap' }}>
          {{ trigger: () => tag, default: () => detail }}
        </NTooltip>
      ) : (
        tag
      )
    }

    return () => (
      <div class={styles['diff-panel']}>
        <div class={styles['diff-header']}>
          <span class={styles['diff-title']}>
            {t('project.node.ai_proposal')}
          </span>
          <NSpace size='small'>
            {props.proposal.validations.map(renderValidation)}
          </NSpace>
        </div>
        <div
          ref={container}
          class={styles['diff-editor']}
          style={{ height: props.height }}
        />
        <div class={styles['diff-actions']}>
          <NButton
            size='small'
            quaternary
            onClick={() => (sideBySide.value = !sideBySide.value)}
          >
            {t(
              sideBySide.value
                ? 'project.node.ai_inline_view'
                : 'project.node.ai_side_by_side_view'
            )}
          </NButton>
          <NSpace size='small'>
            <NButton size='small' onClick={onReject}>
              {t('project.node.ai_reject')}
            </NButton>
            <NButton size='small' type='primary' onClick={onAccept}>
              {t('project.node.ai_accept')}
            </NButton>
          </NSpace>
        </div>
      </div>
    )
  }
})
