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

import { computed, reactive } from 'vue'
import { postSse } from './sse'
import type { ChatMessage, HistoryTurn, Proposal } from './types'

export interface AiSessionOptions {
  /**
   * The chat endpoint, e.g. /ai/seatunnel/chat
   */
  url: string
  buildBody: (userInput: string, history: HistoryTurn[]) => unknown
  getScript: () => string
  setScript: (script: string) => void
}

const CREDENTIAL_PLACEHOLDER = /\$\{_CRED_\d+_}/g

/**
 * A conversation with the AI assistant: the history is kept in the browser and sent in every request, proposals are
 * accepted or rejected by the user, and every accepted proposal is a checkpoint which can be reverted.
 */
export function useAiSession(options: AiSessionOptions) {
  const state = reactive({
    messages: [] as ChatMessage[],
    sending: false,
    pendingMessageId: null as number | null
  })
  let controller: AbortController | null = null
  let nextId = 1

  const pending = computed(() =>
    state.messages.find((message) => message.id === state.pendingMessageId)
  )

  const history = (): HistoryTurn[] =>
    state.messages
      .filter(
        (message) =>
          !message.streaming &&
          !message.error &&
          !message.reverted &&
          message.content
      )
      .map((message) => ({
        role: message.role,
        content: message.proposal
          ? message.content + proposalNote(message)
          : message.content
      }))

  const send = async (input: string) => {
    const userInput = input.trim()
    if (!userInput || state.sending || state.pendingMessageId !== null) return
    const body = options.buildBody(userInput, history())
    state.messages.push({
      id: nextId++,
      role: 'user',
      content: userInput,
      statuses: [],
      streaming: false
    })
    state.messages.push({
      id: nextId++,
      role: 'assistant',
      content: '',
      statuses: [],
      streaming: true
    })
    // Work on the reactive proxy so that the updates are rendered
    const reply = state.messages[state.messages.length - 1]
    state.sending = true
    controller = new AbortController()
    try {
      await postSse(
        options.url,
        body,
        ({ event, data }) => {
          if (event === 'delta') {
            reply.content = mask(reply.content + data.text)
          } else if (event === 'status') {
            reply.statuses.push({ status: data.status, detail: data.detail })
          } else if (event === 'proposal') {
            reply.proposal = data as Proposal
            reply.proposalState = 'pending'
            state.pendingMessageId = reply.id
          } else if (event === 'done') {
            // The final reply without the config, the config is shown in the diff editor
            reply.content = mask(data.message) || reply.content
          } else if (event === 'error') {
            reply.error = data.message
          }
        },
        controller.signal
      )
    } catch (e: any) {
      reply.error =
        e?.name === 'AbortError' ? 'stopped' : e?.message || String(e)
    } finally {
      reply.streaming = false
      state.sending = false
      controller = null
    }
  }

  const stop = () => controller?.abort()

  const accept = (script: string) => {
    const message = pending.value
    if (!message) return
    message.checkpoint = { before: options.getScript(), after: script }
    options.setScript(script)
    message.proposalState = 'accepted'
    state.pendingMessageId = null
  }

  const reject = () => {
    const message = pending.value
    if (!message) return
    message.proposalState = 'rejected'
    state.pendingMessageId = null
  }

  /**
   * Restore the script before the given turn, the turn and the following turns are excluded from the history.
   */
  const revert = (messageId: number) => {
    const index = state.messages.findIndex(
      (message) => message.id === messageId
    )
    const message = state.messages[index]
    if (!message?.checkpoint || state.sending) return
    reject()
    options.setScript(message.checkpoint.before)
    // Start from the user message of the turn
    for (let i = Math.max(0, index - 1); i < state.messages.length; i++) {
      const item = state.messages[i]
      item.reverted = true
      if (item.proposalState === 'accepted') item.proposalState = 'reverted'
    }
  }

  /**
   * Apply the given reverted turn again.
   */
  const redo = (messageId: number) => {
    const index = state.messages.findIndex(
      (message) => message.id === messageId
    )
    const message = state.messages[index]
    if (
      !message?.checkpoint ||
      state.sending ||
      state.pendingMessageId !== null
    )
      return
    options.setScript(message.checkpoint.after)
    for (let i = Math.max(0, index - 1); i <= index; i++) {
      state.messages[i].reverted = false
    }
    message.proposalState = 'accepted'
  }

  const clear = () => {
    stop()
    state.messages = []
    state.pendingMessageId = null
  }

  return { state, pending, send, stop, accept, reject, revert, redo, clear }
}

export type AiSession = ReturnType<typeof useAiSession>

function mask(text: string) {
  return (text || '').replace(CREDENTIAL_PLACEHOLDER, '******')
}

function proposalNote(message: ChatMessage) {
  if (message.proposalState === 'accepted') {
    return '\n\n(The user accepted the proposed config.)'
  }
  if (message.proposalState === 'rejected') {
    return '\n\n(The user rejected the proposed config.)'
  }
  return ''
}
