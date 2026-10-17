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
import type { SavedMessage } from '@/service/modules/ai/types'
import type {
  ChatMessage,
  HistoryTurn,
  HttpResponseSample,
  Proposal
} from './types'

export interface AiSessionOptions {
  /**
   * The chat endpoint, e.g. /ai/seatunnel/chat
   */
  url: string
  buildBody: (userInput: string, history: HistoryTurn[]) => unknown
  getScript: () => string
  setScript: (script: string) => void
  /**
   * The current user, shown as the sender of the user messages
   */
  userName?: () => string
  /**
   * Saves the conversation, e.g. on the server for the task, when set
   */
  persistence?: AiSessionPersistence
  /**
   * Only shows the saved conversation, e.g. when viewing a task: nothing is sent, saved or cleared
   */
  readonly?: boolean
}

export interface AiSessionPersistence {
  load: () => Promise<{
    messages: SavedMessage[]
    updateTime: string | null
    updateUserName: string | null
  }>
  save: (messages: SavedMessage[]) => Promise<void>
  clear: () => Promise<void>
}

const SAVE_DELAY_MILLIS = 500

const CREDENTIAL_PLACEHOLDER = /\$\{_CRED_\d+_}/g

/**
 * A conversation with the AI assistant: the history is kept in the browser and sent in every request, proposals are
 * accepted or rejected by the user, and every accepted proposal is a checkpoint which can be reverted.
 */
export function useAiSession(options: AiSessionOptions) {
  const state = reactive({
    messages: [] as ChatMessage[],
    sending: false,
    pendingMessageId: null as number | null,
    // Loading the saved conversation, sending is disabled meanwhile
    loading: !!options.persistence,
    readonly: !!options.readonly,
    // Info of the saved conversation shown above the restored messages
    restoredUpdateTime: null as string | null,
    restoredUpdateUserName: null as string | null
  })
  let controller: AbortController | null = null
  let nextId = 1
  let saveTimer: ReturnType<typeof setTimeout> | null = null

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
        content: message.proposalState
          ? message.content + proposalNote(message)
          : message.content
      }))

  const toSaved = (): SavedMessage[] =>
    state.messages
      .filter((message) => !message.streaming)
      .map((message) => ({
        role: message.role,
        content: message.content,
        userName: message.userName,
        time: message.time,
        note: message.note,
        steps: message.steps?.map(({ kind, state, args, result, millis }) => ({
          kind,
          // A step still running when the request stopped
          state: state === 'running' ? 'error' : state,
          args,
          result,
          millis
        })),
        // A proposal waiting for the decision can not be accepted after reopening
        proposalState:
          message.proposalState === 'pending'
            ? 'expired'
            : message.proposalState,
        valid: message.proposal ? message.proposal.valid : message.valid,
        fixAttempts: message.proposal
          ? message.proposal.fixAttempts
          : message.fixAttempts,
        error: message.error
      }))

  const save = async () => {
    if (saveTimer) clearTimeout(saveTimer)
    saveTimer = null
    if (!options.persistence || state.loading || options.readonly) return
    try {
      await options.persistence.save(toSaved())
    } catch (e) {
      // The conversation still works without saving
    }
  }

  const scheduleSave = () => {
    if (!options.persistence) return
    if (saveTimer) clearTimeout(saveTimer)
    saveTimer = setTimeout(save, SAVE_DELAY_MILLIS)
  }

  /**
   * Load the saved conversation, the restored messages are context of the following turns.
   */
  let loaded = false
  const load = async () => {
    if (!options.persistence || loaded) return
    loaded = true
    try {
      const saved = await options.persistence.load()
      const restored: ChatMessage[] = (saved.messages || []).map((message) => ({
        id: nextId++,
        role: message.role,
        content: message.content || '',
        statuses: [],
        streaming: false,
        restored: true,
        userName: message.userName,
        time: message.time,
        note: message.note,
        steps: message.steps?.map((step, index) => ({
          id: index + 1,
          kind: step.kind,
          state: step.state as any,
          args: step.args || {},
          result: step.result || {},
          millis: step.millis
        })),
        proposalState: message.proposalState as ChatMessage['proposalState'],
        valid: message.valid,
        fixAttempts: message.fixAttempts,
        error: message.error
      }))
      state.messages = [...restored, ...state.messages]
      state.restoredUpdateTime = saved.updateTime
      state.restoredUpdateUserName = saved.updateUserName
    } catch (e) {
      // Start with an empty conversation
    } finally {
      state.loading = false
    }
  }

  const send = async (input: string) => {
    const userInput = input.trim()
    if (
      !userInput ||
      options.readonly ||
      state.sending ||
      state.loading ||
      state.pendingMessageId !== null
    )
      return
    const body = options.buildBody(userInput, history())
    state.messages.push({
      id: nextId++,
      role: 'user',
      content: userInput,
      statuses: [],
      streaming: false,
      userName: options.userName?.(),
      time: Date.now()
    })
    state.messages.push({
      id: nextId++,
      role: 'assistant',
      content: '',
      statuses: [],
      streaming: true,
      time: Date.now()
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
          } else if (event === 'http_response') {
            reply.httpResponses = [
              ...(reply.httpResponses || []),
              data as HttpResponseSample
            ]
          } else if (event === 'step') {
            reply.steps = [...(reply.steps || []), { ...data, details: [] }]
          } else if (event === 'step_end') {
            const step = reply.steps?.find((item) => item.id === data.id)
            if (step) Object.assign(step, data)
            else reply.steps = [...(reply.steps || []), data]
          } else if (event === 'sample') {
            reply.samples = [
              ...(reply.samples || []),
              { title: data.title, samples: data.samples || [] }
            ]
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
      scheduleSave()
    }
  }

  const stop = () => controller?.abort()

  /**
   * Add a note of a user action to the conversation, e.g. the selected source was changed. The AI sees it in the
   * history of the next request.
   */
  const addNote = (text: string) => {
    if (options.readonly) return
    state.messages.push({
      id: nextId++,
      role: 'user',
      content: text,
      statuses: [],
      streaming: false,
      note: true,
      userName: options.userName?.(),
      time: Date.now()
    })
    scheduleSave()
  }

  const accept = (script: string) => {
    const message = pending.value
    if (!message) return
    message.checkpoint = { before: options.getScript(), after: script }
    options.setScript(script)
    message.proposalState = 'accepted'
    state.pendingMessageId = null
    scheduleSave()
  }

  const reject = () => {
    const message = pending.value
    if (!message) return
    message.proposalState = 'rejected'
    state.pendingMessageId = null
    scheduleSave()
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
    scheduleSave()
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
    scheduleSave()
  }

  /**
   * Clear the conversation, the saved conversation is deleted as well.
   */
  const clear = async () => {
    if (options.readonly) return
    stop()
    if (saveTimer) clearTimeout(saveTimer)
    saveTimer = null
    if (options.persistence) {
      await options.persistence.clear()
    }
    state.messages = []
    state.pendingMessageId = null
    state.restoredUpdateTime = null
    state.restoredUpdateUserName = null
  }

  /**
   * Stop the request and save the pending changes immediately, e.g. when the dialog is closed.
   */
  const dispose = () => {
    stop()
    if (saveTimer) save()
  }

  return {
    state,
    pending,
    load,
    send,
    stop,
    addNote,
    scheduleSave,
    accept,
    reject,
    revert,
    redo,
    clear,
    dispose
  }
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
