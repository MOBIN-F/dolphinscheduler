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

import { useUserStore } from '@/store/user/user'
import cookies from 'js-cookie'

export interface SseEvent {
  event: string
  data: any
}

const baseUrl = () =>
  import.meta.env.MODE === 'development'
    ? '/dolphinscheduler'
    : import.meta.env.VITE_APP_PROD_WEB_URL + '/dolphinscheduler'

/**
 * POST a json body and read the server-sent events of the response. EventSource only supports GET, so fetch is
 * used. When the server rejects the request it replies a json Result, which is thrown as an error.
 */
export async function postSse(
  url: string,
  body: unknown,
  onEvent: (event: SseEvent) => void,
  signal: AbortSignal
): Promise<void> {
  const userStore = useUserStore()
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    Accept: 'text/event-stream, application/json',
    sessionId: userStore.getSessionId
  }
  const language = cookies.get('language')
  if (language) headers.language = language

  const response = await fetch(baseUrl() + url, {
    method: 'POST',
    headers,
    body: JSON.stringify(body),
    credentials: 'same-origin',
    signal
  })
  const contentType = response.headers.get('content-type') || ''
  if (!response.ok || !contentType.includes('text/event-stream')) {
    let message = response.statusText || String(response.status)
    try {
      const result = await response.json()
      message = result.msg || message
    } catch (e) {
      // not a json result
    }
    throw new Error(message)
  }

  const reader = response.body!.getReader()
  const decoder = new TextDecoder()
  const parser = createSseParser(onEvent)
  for (;;) {
    const { done, value } = await reader.read()
    if (done) break
    parser(decoder.decode(value, { stream: true }))
  }
}

/**
 * @return a function to feed the received text, a chunk may contain a part of an event or several events
 */
export function createSseParser(onEvent: (event: SseEvent) => void) {
  let buffer = ''
  return (chunk: string) => {
    buffer += chunk
    // Events are separated by a blank line
    let match
    while ((match = buffer.match(/\r?\n\r?\n/)) && match.index !== undefined) {
      const raw = buffer.slice(0, match.index)
      buffer = buffer.slice(match.index + match[0].length)
      const event = parseEvent(raw)
      if (event) onEvent(event)
    }
  }
}

function parseEvent(raw: string): SseEvent | null {
  let event = 'message'
  const data: string[] = []
  for (const line of raw.split(/\r?\n/)) {
    if (line.startsWith('event:')) {
      event = line.slice(6).trim()
    } else if (line.startsWith('data:')) {
      data.push(line.slice(5).replace(/^ /, ''))
    }
  }
  if (!data.length) return null
  const text = data.join('\n')
  try {
    return { event, data: JSON.parse(text) }
  } catch (e) {
    return { event, data: text }
  }
}
