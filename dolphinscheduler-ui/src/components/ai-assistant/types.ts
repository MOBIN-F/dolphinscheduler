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

type ChatRole = 'user' | 'assistant'

interface ValidationIssue {
  level: 'ERROR' | 'WARNING'
  location: string
  message: string
}

interface ValidationResult {
  stage: 'local' | 'engine'
  skipped: boolean
  skipReason: string | null
  valid: boolean
  issues: ValidationIssue[]
}

interface Proposal {
  script: string
  explanation: string
  valid: boolean
  fixAttempts: number
  validations: ValidationResult[]
}

/**
 * A step of the progress of a reply: a tool call, a validation, an LLM call, with its result
 */
interface ProgressStep {
  id: number
  kind: string
  state: 'running' | 'ok' | 'warn' | 'error'
  args: Record<string, any>
  result: Record<string, any>
  /**
   * Shown when the step is expanded, not saved
   */
  details?: string[]
  millis?: number
}

/**
 * Raw response of an HTTP API call of the assistant, shown to the user only and not saved
 */
interface HttpResponseSample {
  target: string
  status: number
  contentType: string
  size: number
  truncated: boolean
  body: string
}

/**
 * State of the proposal of an assistant message
 */
type ProposalState =
  | 'pending'
  | 'accepted'
  | 'rejected'
  | 'reverted'
  | 'expired'

interface ChatMessage {
  id: number
  role: ChatRole
  content: string
  /**
   * Progress shown under a streaming assistant message, e.g. a tool call
   */
  statuses: { status: string; detail: string | null }[]
  steps?: ProgressStep[]
  httpResponses?: HttpResponseSample[]
  /**
   * Data sampled by the assistant, e.g. the latest messages of a topic: masked, shown only, not saved
   */
  samples?: { title: string; samples: string[] }[]
  streaming: boolean
  /**
   * Excluded from the history after the user reverted the turn
   */
  reverted?: boolean
  /**
   * Loaded from the saved conversation, it has no proposal script and no checkpoint
   */
  restored?: boolean
  /**
   * The sender of a user message
   */
  userName?: string
  time?: number
  /**
   * A note of a user action, e.g. the selected source was changed; sent to the AI as a user message
   */
  note?: boolean
  /**
   * Validation summary of the proposal, kept for the saved conversation
   */
  valid?: boolean
  fixAttempts?: number
  error?: string
  proposal?: Proposal
  proposalState?: ProposalState
  /**
   * Script before and after accepting the proposal, used to revert and redo
   */
  checkpoint?: { before: string; after: string }
}

interface HistoryTurn {
  role: ChatRole
  content: string
}

export {
  ChatRole,
  ValidationIssue,
  ValidationResult,
  Proposal,
  ProgressStep,
  HttpResponseSample,
  ProposalState,
  ChatMessage,
  HistoryTurn
}
