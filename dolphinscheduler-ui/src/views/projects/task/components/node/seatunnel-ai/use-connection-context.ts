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

import { reactive } from 'vue'
import {
  describeConnectionObject,
  queryConnections,
  queryConnectionTypes
} from '@/service/modules/seatunnel-connections'
import type {
  ConnectionRole,
  ConnectionTypeDef,
  ObjectDescription,
  SeatunnelConnection
} from '@/service/modules/seatunnel-connections/types'
import type {
  ConnectionContextReq,
  ConnectionSelectionReq
} from '@/service/modules/ai/types'

export interface RoleSelection {
  connectionKey: string | null
  /**
   * Topics, tables (database.table or schema.table) or API paths
   */
  objects: string[]
  /**
   * Create the sink table instead of using an existing one
   */
  createTable: boolean
  /**
   * The table to create, database.table
   */
  newTable: string
  /**
   * The target directory of an S3 sink
   */
  path: string
  fileFormat: string
  /**
   * The fields of the objects, the raw samples are only kept in the browser
   */
  descriptions: ObjectDescription[]
  describing: boolean
  error: string
}

const DESCRIBE_DELAY_MILLIS = 600

const emptySelection = (): RoleSelection => ({
  connectionKey: null,
  objects: [],
  createTable: false,
  newTable: '',
  path: '',
  fileFormat: 'parquet',
  descriptions: [],
  describing: false,
  error: ''
})

/**
 * The connections selected for the source and the sink of the SeaTunnel job. Optional: without a selection the user
 * describes the connections in the conversation.
 *
 * @param onChanged called when the user changed a selection and its fields are described
 */
export function useConnectionContext(
  onChanged: (role: ConnectionRole, summary: string) => void
) {
  const state = reactive({
    connections: [] as SeatunnelConnection[],
    types: [] as ConnectionTypeDef[],
    source: emptySelection(),
    sink: emptySelection()
  })
  const timers: Partial<Record<ConnectionRole, ReturnType<typeof setTimeout>>> =
    {}

  const selection = (role: ConnectionRole): RoleSelection =>
    role === 'SOURCE' ? state.source : state.sink

  const connection = (key: string | null) =>
    state.connections.find((item) => item.connKey === key)

  const typeOf = (key: string | null) => {
    const type = connection(key)?.type
    return state.types.find((item) => item.type === type)
  }

  /**
   * Load the connections the user may use, the selection is hidden when there are none
   */
  const load = async () => {
    try {
      const [types, connections] = await Promise.all([
        queryConnectionTypes(),
        queryConnections()
      ])
      state.types = types
      state.connections = connections
    } catch (e) {
      // The connections are optional
    }
  }

  const summary = (role: ConnectionRole) => {
    const item = selection(role)
    const conn = connection(item.connectionKey)
    if (!conn) return ''
    const target =
      item.createTable && item.newTable
        ? item.newTable
        : item.path || item.objects.join(', ')
    return conn.name + (target ? ' / ' + target : '')
  }

  /**
   * Describe the selected objects after the user stopped changing them
   */
  const scheduleDescribe = (role: ConnectionRole) => {
    const timer = timers[role]
    if (timer) clearTimeout(timer)
    timers[role] = setTimeout(() => describe(role), DESCRIBE_DELAY_MILLIS)
  }

  const describe = async (role: ConnectionRole) => {
    const item = selection(role)
    const key = item.connectionKey
    item.error = ''
    item.descriptions = []
    const type = typeOf(key)
    if (key && type && type.type !== 'S3' && item.objects.length) {
      item.describing = true
      try {
        for (const object of item.objects) {
          const description = await describeConnectionObject(key, object)
          // The selection may have changed meanwhile
          if (item.connectionKey !== key) return
          item.descriptions.push(description)
        }
      } catch (e: any) {
        item.error = e?.message || String(e)
      } finally {
        item.describing = false
      }
    }
    onChanged(role, summary(role))
  }

  const selectConnection = (role: ConnectionRole, key: string | null) => {
    const fileFormat = selection(role).fileFormat
    Object.assign(selection(role), emptySelection(), {
      connectionKey: key,
      fileFormat
    })
    scheduleDescribe(role)
  }

  const toRequest = (role: ConnectionRole): ConnectionSelectionReq | null => {
    const item = selection(role)
    if (!item.connectionKey || !connection(item.connectionKey)) return null
    return {
      connectionKey: item.connectionKey,
      objects: item.createTable ? [] : item.objects,
      newTable: item.createTable ? item.newTable : undefined,
      path: item.path || undefined,
      fileFormat:
        typeOf(item.connectionKey)?.type === 'S3' ? item.fileFormat : undefined,
      // The raw samples are not sent to the AI and not saved
      descriptions: item.descriptions.map((description) => ({
        ...description,
        samples: undefined
      }))
    }
  }

  const toContext = (): ConnectionContextReq | null => {
    const source = toRequest('SOURCE')
    const sink = toRequest('SINK')
    return source || sink ? { source, sink } : null
  }

  /**
   * Restore the saved selection of the task
   */
  const apply = (context?: ConnectionContextReq | null) => {
    const restore = (
      role: ConnectionRole,
      saved?: ConnectionSelectionReq | null
    ) => {
      const item = selection(role)
      Object.assign(item, emptySelection())
      if (!saved) return
      item.connectionKey = saved.connectionKey
      item.objects = saved.objects || []
      item.createTable = !!saved.newTable
      item.newTable = saved.newTable || ''
      item.path = saved.path || ''
      item.fileFormat = saved.fileFormat || 'parquet'
      item.descriptions = (saved.descriptions || []) as ObjectDescription[]
    }
    restore('SOURCE', context?.source)
    restore('SINK', context?.sink)
  }

  return {
    state,
    selection,
    connection,
    typeOf,
    load,
    summary,
    selectConnection,
    scheduleDescribe,
    toContext,
    apply
  }
}

export type ConnectionContext = ReturnType<typeof useConnectionContext>
