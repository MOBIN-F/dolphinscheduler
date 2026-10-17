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

type ConnectionRole = 'SOURCE' | 'SINK'

interface ConnectionField {
  key: string
  label: string
  labelZh: string
  input: 'text' | 'password' | 'number' | 'select' | 'textarea'
  required: boolean
  secret: boolean
  defaultValue: string
  options: string[]
  showWhen: Record<string, string[]>
}

interface ConnectionTypeDef {
  type: string
  label: string
  roles: ConnectionRole[]
  objectKind: 'topic' | 'table' | 'path'
  fields: ConnectionField[]
}

interface SeatunnelConnection {
  id: number
  code: number
  connKey: string
  name: string
  type: string
  description?: string
  params: Record<string, string>
  secretsSet: string[]
  updateUserName?: string
  updateTime?: string
}

interface SeatunnelConnectionReq {
  connKey: string
  name: string
  type: string
  description?: string
  params: Record<string, string>
  secrets: Record<string, string>
}

interface BrowseNode {
  name: string
  path: string
  leaf: boolean
}

interface DescribedField {
  name: string
  type: string
  nullable: boolean
  primaryKey: boolean
  sample?: string
  note?: string
}

interface ObjectDescription {
  object: string
  format: string
  fields: DescribedField[]
  recordPath?: string | null
  note?: string
  /**
   * Raw messages or response, shown to the user only, not sent to the AI and not saved
   */
  samples?: string[]
}

export {
  ConnectionRole,
  ConnectionField,
  ConnectionTypeDef,
  SeatunnelConnection,
  SeatunnelConnectionReq,
  BrowseNode,
  DescribedField,
  ObjectDescription
}
