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
import {
  NButton,
  NCollapse,
  NCollapseItem,
  NDivider,
  NIcon,
  NInput,
  NPopconfirm,
  NSpin,
  NTag
} from 'naive-ui'
import { DeleteOutlined } from '@vicons/antd'
import { format } from 'date-fns'
import { useI18n } from 'vue-i18n'
import { renderMarkdown } from './markdown'
import styles from './index.module.scss'
import type { AiSession } from './use-ai-session'
import type { ChatMessage, HttpResponseSample } from './types'

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

    const clearing = ref(false)
    const clear = async () => {
      clearing.value = true
      try {
        await props.session.clear()
        window.$message.success(t('project.node.ai_cleared'))
      } catch (e: any) {
        window.$message.error(e?.message || String(e))
      } finally {
        clearing.value = false
      }
    }

    const formatTime = (time?: number) =>
      time ? format(new Date(time), 'yyyy-MM-dd HH:mm') : ''

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
      if (status === 'http_sample') {
        return t('project.node.ai_status_http_sample', { target: detail || '' })
      }
      return t('project.node.ai_status_' + status)
    }

    const formatSize = (size: number) =>
      size >= 1024 ? (size / 1024).toFixed(1) + ' KB' : size + ' B'

    const formatBody = (sample: HttpResponseSample) => {
      if (!sample.truncated) {
        try {
          return JSON.stringify(JSON.parse(sample.body), null, 2)
        } catch (e) {
          // Not JSON, shown as it is
        }
      }
      return sample.body
    }

    const renderHttpResponses = (message: ChatMessage) =>
      message.httpResponses?.length ? (
        <NCollapse class={styles['http-responses']}>
          {message.httpResponses.map((sample, index) => (
            <NCollapseItem
              key={index}
              name={index}
              title={t('project.node.ai_http_response', {
                target: sample.target,
                status: sample.status,
                size: formatSize(sample.size)
              })}
            >
              <pre class={styles['http-response-body']}>
                {formatBody(sample)}
              </pre>
              <div class={styles['http-response-note']}>
                {(sample.truncated
                  ? t('project.node.ai_http_response_truncated') + ' '
                  : '') + t('project.node.ai_http_response_note')}
              </div>
            </NCollapseItem>
          ))}
        </NCollapse>
      ) : null

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
              {message.checkpoint && (
                <NButton
                  text
                  size='tiny'
                  type='primary'
                  disabled={session.state.sending}
                  onClick={() => session.revert(message.id)}
                >
                  {t('project.node.ai_revert')}
                </NButton>
              )}
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
              {message.checkpoint && (
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
              )}
            </div>
          )
        case 'expired':
          return (
            <NTag size='small' bordered={false}>
              {t('project.node.ai_expired')}
            </NTag>
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
            {message.userName && (
              <div class={styles.sender}>
                {message.userName} · {formatTime(message.time)}
              </div>
            )}
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
            {renderHttpResponses(message)}
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

    const renderMessages = () => {
      const { state } = props.session
      const restoredCount = state.messages.filter((m) => m.restored).length
      const nodes = []
      if (restoredCount > 0) {
        nodes.push(
          <NDivider class={styles['history-divider']} key='history'>
            {t('project.node.ai_history', {
              time: (state.restoredUpdateTime || '').slice(0, 16),
              user: state.restoredUpdateUserName || ''
            })}
          </NDivider>
        )
      }
      state.messages.forEach((message, index) => {
        if (restoredCount > 0 && index === restoredCount) {
          nodes.push(
            <NDivider class={styles['history-divider']} key='current'>
              {t('project.node.ai_current_chat')}
            </NDivider>
          )
        }
        nodes.push(renderMessage(message))
      })
      return nodes
    }

    return () => {
      const { state } = props.session
      const pending = state.pendingMessageId !== null
      const readonly = state.readonly
      return (
        <div class={styles['chat-panel']} style={{ height: props.height }}>
          <div class={styles['chat-header']}>
            <span class={styles['chat-title']}>
              {props.title || t('project.node.ai_assistant')}
            </span>
            {!readonly && (
              <NPopconfirm onPositiveClick={clear}>
                {{
                  default: () => t('project.node.ai_clear_confirm'),
                  trigger: () => (
                    <NButton
                      text
                      size='small'
                      loading={clearing.value}
                      disabled={
                        state.sending || state.loading || !state.messages.length
                      }
                    >
                      {{
                        icon: () => (
                          <NIcon>
                            <DeleteOutlined />
                          </NIcon>
                        ),
                        default: () => t('project.node.ai_clear_history')
                      }}
                    </NButton>
                  )
                }}
              </NPopconfirm>
            )}
          </div>
          <div class={styles['chat-messages']} ref={list}>
            {state.loading ? (
              <div class={styles.welcome}>
                <NSpin size='small' />
              </div>
            ) : state.messages.length === 0 && readonly ? (
              <div class={styles.welcome}>
                <p>{t('project.node.ai_readonly_empty')}</p>
              </div>
            ) : state.messages.length === 0 ? (
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
              renderMessages()
            )}
          </div>
          <div class={styles['chat-input']}>
            {pending && !readonly && (
              <div class={styles.hint}>{t('project.node.ai_pending_hint')}</div>
            )}
            <NInput
              type='textarea'
              value={input.value}
              onUpdateValue={(value: string) => (input.value = value)}
              onKeydown={onKeydown}
              autosize={{ minRows: 2, maxRows: 6 }}
              placeholder={
                readonly
                  ? t('project.node.ai_readonly_placeholder')
                  : props.placeholder || t('project.node.ai_placeholder')
              }
              disabled={readonly || pending || state.loading}
            />
            {!readonly && (
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
            )}
          </div>
        </div>
      )
    }
  }
})
