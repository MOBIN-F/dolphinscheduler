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

import { defineComponent, nextTick, PropType, ref, watch } from 'vue'
import { NButton, NInput, NSpin, NTag } from 'naive-ui'
import { useI18n } from 'vue-i18n'
import { renderMarkdown } from './markdown'
import styles from './index.module.scss'
import type { AiSession } from './use-ai-session'
import type { ChatMessage } from './types'

const props = {
  session: {
    type: Object as PropType<AiSession>,
    required: true as const
  },
  title: {
    type: String as PropType<string>,
    default: ''
  },
  placeholder: {
    type: String as PropType<string>,
    default: ''
  },
  /**
   * Example requests shown in an empty conversation
   */
  examples: {
    type: Array as PropType<string[]>,
    default: () => []
  },
  height: {
    type: String as PropType<string>,
    default: 'calc(100vh - 220px)'
  }
}

/**
 * Chat panel of the AI assistant.
 */
export default defineComponent({
  name: 'AiChatPanel',
  props,
  setup(props) {
    const { t } = useI18n()
    const input = ref('')
    const list = ref<HTMLElement>()

    const scrollToBottom = () => {
      nextTick(() => {
        if (list.value) list.value.scrollTop = list.value.scrollHeight
      })
    }
    watch(() => {
      const messages = props.session.state.messages
      const last = messages[messages.length - 1]
      return [messages.length, last?.content, last?.statuses.length]
    }, scrollToBottom)

    const send = () => {
      const text = input.value
      if (!text.trim()) return
      input.value = ''
      props.session.send(text)
    }

    const onKeydown = (event: KeyboardEvent) => {
      if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
        event.preventDefault()
        send()
      }
    }

    const formatStatus = (status: string, detail: string | null) => {
      if (status === 'tool' && detail) {
        const [name, ...rest] = detail.split(' ')
        let args: any = {}
        try {
          args = JSON.parse(rest.join(' '))
        } catch (e) {
          // ignore
        }
        if (name === 'get_connector_info') {
          return t('project.node.ai_status_connector', {
            name: args.connector_name,
            type: args.connector_type
          })
        }
        if (name === 'validate_config') {
          return t('project.node.ai_status_self_check')
        }
        return t('project.node.ai_status_list_connectors')
      }
      if (status === 'fixing') {
        return t('project.node.ai_status_fixing', { n: detail })
      }
      return t('project.node.ai_status_' + status)
    }

    const renderProposalState = (message: ChatMessage) => {
      const session = props.session
      switch (message.proposalState) {
        case 'pending':
          return (
            <NTag size='small' type='info' bordered={false}>
              {t('project.node.ai_proposal_pending')}
            </NTag>
          )
        case 'accepted':
          return (
            <div class={styles['proposal-state']}>
              <NTag size='small' type='success' bordered={false}>
                {t('project.node.ai_accepted')}
              </NTag>
              <NButton
                text
                size='tiny'
                type='primary'
                disabled={session.state.sending}
                onClick={() => session.revert(message.id)}
              >
                {t('project.node.ai_revert')}
              </NButton>
            </div>
          )
        case 'rejected':
          return (
            <NTag size='small' bordered={false}>
              {t('project.node.ai_rejected')}
            </NTag>
          )
        case 'reverted':
          return (
            <div class={styles['proposal-state']}>
              <NTag size='small' type='warning' bordered={false}>
                {t('project.node.ai_reverted')}
              </NTag>
              <NButton
                text
                size='tiny'
                type='primary'
                disabled={
                  session.state.sending ||
                  session.state.pendingMessageId !== null
                }
                onClick={() => session.redo(message.id)}
              >
                {t('project.node.ai_redo')}
              </NButton>
            </div>
          )
        default:
          return null
      }
    }

    const renderMessage = (message: ChatMessage) => {
      const classes = [
        styles.message,
        message.role === 'user' ? styles.user : styles.assistant,
        message.reverted ? styles.reverted : ''
      ]
      if (message.role === 'user') {
        return (
          <div class={classes} key={message.id}>
            <div class={styles.bubble}>{message.content}</div>
          </div>
        )
      }
      return (
        <div class={classes} key={message.id}>
          <div class={styles.bubble}>
            {message.content ? (
              <div
                class={styles.markdown}
                innerHTML={renderMarkdown(message.content)}
              />
            ) : message.streaming ? (
              <NSpin size='small' />
            ) : null}
            {message.statuses.length > 0 && (
              <div class={styles.statuses}>
                {message.statuses.map((item, index) => (
                  <div key={index}>
                    {(message.streaming && index === message.statuses.length - 1
                      ? '⋯ '
                      : '✓ ') + formatStatus(item.status, item.detail)}
                  </div>
                ))}
              </div>
            )}
            {message.error && (
              <div class={styles.error}>
                {message.error === 'stopped'
                  ? t('project.node.ai_stopped')
                  : t('project.node.ai_error', { message: message.error })}
              </div>
            )}
            {renderProposalState(message)}
          </div>
        </div>
      )
    }

    return () => {
      const { state } = props.session
      const pending = state.pendingMessageId !== null
      return (
        <div class={styles['chat-panel']} style={{ height: props.height }}>
          <div class={styles['chat-header']}>
            <span class={styles['chat-title']}>
              {props.title || t('project.node.ai_assistant')}
            </span>
            <NButton
              text
              size='small'
              disabled={state.sending || !state.messages.length}
              onClick={() => props.session.clear()}
            >
              {t('project.node.ai_new_chat')}
            </NButton>
          </div>
          <div class={styles['chat-messages']} ref={list}>
            {state.messages.length === 0 ? (
              <div class={styles.welcome}>
                <p>{t('project.node.ai_welcome')}</p>
                {props.examples.map((example) => (
                  <div
                    class={styles.example}
                    onClick={() => (input.value = example)}
                  >
                    {example}
                  </div>
                ))}
              </div>
            ) : (
              state.messages.map(renderMessage)
            )}
          </div>
          <div class={styles['chat-input']}>
            {pending && (
              <div class={styles.hint}>{t('project.node.ai_pending_hint')}</div>
            )}
            <NInput
              type='textarea'
              value={input.value}
              onUpdateValue={(value: string) => (input.value = value)}
              onKeydown={onKeydown}
              autosize={{ minRows: 2, maxRows: 6 }}
              placeholder={
                props.placeholder || t('project.node.ai_placeholder')
              }
              disabled={pending}
            />
            <div class={styles['chat-actions']}>
              {state.sending ? (
                <NButton size='small' onClick={() => props.session.stop()}>
                  {t('project.node.ai_stop')}
                </NButton>
              ) : (
                <NButton
                  size='small'
                  type='primary'
                  disabled={pending || !input.value.trim()}
                  onClick={send}
                >
                  {t('project.node.ai_send')}
                </NButton>
              )}
            </div>
          </div>
        </div>
      )
    }
  }
})
