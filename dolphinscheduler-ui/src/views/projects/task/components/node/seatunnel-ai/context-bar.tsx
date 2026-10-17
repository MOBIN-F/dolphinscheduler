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

import { defineComponent, PropType, reactive, ref } from 'vue'
import {
  NCheckbox,
  NIcon,
  NInput,
  NPopover,
  NSelect,
  NSpin,
  NTag
} from 'naive-ui'
import { DownOutlined, RightOutlined } from '@vicons/antd'
import { useI18n } from 'vue-i18n'
import { browseConnection } from '@/service/modules/seatunnel-connections'
import type {
  BrowseNode,
  ConnectionRole,
  ObjectDescription
} from '@/service/modules/seatunnel-connections/types'
import type { ConnectionContext } from './use-connection-context'
import styles from './context-bar.module.scss'

const FILE_FORMATS = ['parquet', 'orc', 'json', 'csv', 'text']

/**
 * Optional selection of the source and the sink connections at the top of the SeaTunnel AI chat: the connection,
 * then the topics, tables, API paths or S3 directory. The fields of the selected objects are given to the AI.
 */
export default defineComponent({
  name: 'SeatunnelContextBar',
  props: {
    context: {
      type: Object as PropType<ConnectionContext>,
      required: true as const
    },
    readonly: {
      type: Boolean,
      default: false
    }
  },
  setup(props) {
    const { t } = useI18n()
    const expanded = ref(false)
    // Browse results by connection key and parent path
    const nodes = reactive<Record<string, BrowseNode[]>>({})
    const loading = reactive<Record<string, boolean>>({})
    // A failed listing, e.g. no list permission: the names can still be typed
    const failed = reactive<Record<string, boolean>>({})
    const parents = reactive<Record<ConnectionRole, string>>({
      SOURCE: '',
      SINK: ''
    })

    const browse = async (key: string, parent = '') => {
      const cacheKey = key + '|' + parent
      if (nodes[cacheKey] || loading[cacheKey]) return
      loading[cacheKey] = true
      try {
        nodes[cacheKey] = await browseConnection(key, parent)
      } catch (e) {
        // Not retried on every focus, the name can be typed
        nodes[cacheKey] = []
        failed[cacheKey] = true
      } finally {
        loading[cacheKey] = false
      }
    }

    /**
     * The placeholder tells the user the name can be typed when the listing failed or is empty
     */
    const typePlaceholder = (
      key: string | null,
      parent: string,
      placeholder: string
    ) => {
      const cacheKey = key + '|' + parent
      return key &&
        nodes[cacheKey] &&
        (failed[cacheKey] || !nodes[cacheKey].length)
        ? t('project.node.ai_context_type_name')
        : placeholder
    }

    const listed = (key: string | null, parent = '') =>
      key ? nodes[key + '|' + parent] || [] : []

    const isLoading = (key: string | null, parent = '') =>
      !!key && !!loading[key + '|' + parent]

    const roleLabel = (role: ConnectionRole) =>
      role === 'SOURCE'
        ? t('project.node.ai_context_source')
        : t('project.node.ai_context_sink')

    const connectionOptions = (role: ConnectionRole) => {
      const { state } = props.context
      return state.types
        .filter((type) => type.roles.includes(role))
        .map((type) => ({
          type: 'group',
          label: type.label,
          key: type.type,
          children: state.connections
            .filter((item) => item.type === type.type)
            .map((item) => ({ label: item.name, value: item.connKey }))
        }))
        .filter((group) => group.children.length)
    }

    const onConnection = (role: ConnectionRole, key: string | null) => {
      props.context.selectConnection(role, key)
      parents[role] = ''
      if (key) browse(key)
    }

    const onObjects = (role: ConnectionRole, objects: string[]) => {
      props.context.selection(role).objects = objects
      props.context.scheduleDescribe(role)
    }

    /**
     * Kafka topics or HTTP paths: one level
     */
    const renderFlatObjects = (role: ConnectionRole, multiple: boolean) => {
      const item = props.context.selection(role)
      const key = item.connectionKey
      const options = listed(key).map((node) => ({
        label: node.name,
        value: node.path
      }))
      return (
        <NSelect
          size='small'
          filterable
          tag
          multiple={multiple}
          disabled={props.readonly}
          loading={isLoading(key)}
          placeholder={typePlaceholder(
            key,
            '',
            t('project.node.ai_context_object')
          )}
          value={multiple ? item.objects : item.objects[0] || null}
          options={options}
          onFocus={() => key && browse(key)}
          onUpdateValue={(value: string | string[] | null) =>
            onObjects(role, Array.isArray(value) ? value : value ? [value] : [])
          }
        />
      )
    }

    /**
     * Databases or schemas, then tables
     */
    const renderTables = (role: ConnectionRole) => {
      const item = props.context.selection(role)
      const key = item.connectionKey
      const database =
        parents[role] ||
        (item.createTable ? item.newTable : item.objects[0] || '').split('.')[0]
      const sink = role === 'SINK'
      const tables = listed(key, database).map((node) => ({
        label: node.name,
        value: node.path
      }))
      return (
        <div class={styles.objects}>
          <NSelect
            size='small'
            filterable
            tag
            disabled={props.readonly}
            loading={isLoading(key)}
            placeholder={typePlaceholder(
              key,
              '',
              t('project.node.ai_context_database')
            )}
            value={database || null}
            options={listed(key).map((node) => ({
              label: node.name,
              value: node.path
            }))}
            onFocus={() => key && browse(key)}
            onUpdateValue={(value: string) => {
              parents[role] = value
              if (key) browse(key, value)
              item.newTable = item.createTable ? value + '.' : item.newTable
              onObjects(role, [])
            }}
          />
          {sink && item.createTable ? (
            <NInput
              size='small'
              disabled={props.readonly}
              placeholder={t('project.node.ai_context_new_table')}
              value={item.newTable.split('.').slice(1).join('.')}
              onUpdateValue={(value: string) => {
                item.newTable = database + '.' + value
                props.context.scheduleDescribe(role)
              }}
            />
          ) : (
            <NSelect
              size='small'
              filterable
              tag
              multiple={!sink}
              maxTagCount='responsive'
              disabled={props.readonly || !database}
              loading={isLoading(key, database)}
              placeholder={typePlaceholder(
                key,
                database,
                t('project.node.ai_context_table')
              )}
              value={sink ? item.objects[0] || null : item.objects}
              options={tables}
              onFocus={() => key && database && browse(key, database)}
              onUpdateValue={(value: string | string[] | null) =>
                onObjects(
                  role,
                  // A typed table name gets the database, e.g. orders -> shop.orders
                  (Array.isArray(value) ? value : value ? [value] : []).map(
                    (table) =>
                      table.startsWith(database + '.')
                        ? table
                        : database + '.' + table
                  )
                )
              }
            />
          )}
          {sink && (
            <NCheckbox
              size='small'
              disabled={props.readonly}
              checked={item.createTable}
              onUpdateChecked={(checked: boolean) => {
                item.createTable = checked
                item.newTable = checked && database ? database + '.' : ''
                onObjects(role, [])
              }}
            >
              {t('project.node.ai_context_create_table')}
            </NCheckbox>
          )}
        </div>
      )
    }

    /**
     * The target directory of an S3 sink, browsed one level at a time
     */
    const renderS3 = (role: ConnectionRole) => {
      const item = props.context.selection(role)
      const key = item.connectionKey
      const parent = item.path
      return (
        <div class={styles.objects}>
          <NInput
            size='small'
            disabled={props.readonly}
            placeholder='/warehouse/orders'
            value={item.path}
            onUpdateValue={(value: string) => {
              item.path = value
              props.context.scheduleDescribe(role)
            }}
          />
          <NSelect
            size='small'
            filterable
            disabled={props.readonly}
            loading={isLoading(key, parent)}
            placeholder={t('project.node.ai_context_subdirectory')}
            value={null}
            options={listed(key, parent).map((node) => ({
              label: node.name + '/',
              value: node.path
            }))}
            onFocus={() => key && browse(key, parent)}
            onUpdateValue={(value: string) => {
              item.path = value
              if (key) browse(key, value)
              props.context.scheduleDescribe(role)
            }}
          />
          <NSelect
            size='small'
            disabled={props.readonly}
            value={item.fileFormat}
            options={FILE_FORMATS.map((format) => ({
              label: format,
              value: format
            }))}
            onUpdateValue={(value: string) => {
              item.fileFormat = value
              props.context.scheduleDescribe(role)
            }}
          />
        </div>
      )
    }

    const renderDescriptions = (descriptions: ObjectDescription[]) =>
      descriptions.map((description) => (
        <div class={styles.description} key={description.object}>
          <div class={styles['description-title']}>
            {description.object} · {description.format}
            {description.recordPath && description.recordPath !== '$'
              ? ' · ' + description.recordPath
              : ''}
          </div>
          {description.note && (
            <div class={styles['description-note']}>{description.note}</div>
          )}
          <table class={styles.fields}>
            <tbody>
              {description.fields.map((field) => (
                <tr key={field.name}>
                  <td>
                    {field.name}
                    {field.primaryKey ? ' 🔑' : ''}
                  </td>
                  <td>
                    {field.type}
                    {field.nullable ? '?' : ''}
                  </td>
                  <td class={styles.sample}>
                    {field.sample || field.note || ''}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          {!!description.samples?.length && (
            <details>
              <summary>{t('project.node.ai_context_samples')}</summary>
              <pre class={styles.samples}>
                {description.samples.join('\n\n')}
              </pre>
            </details>
          )}
        </div>
      ))

    const renderStatus = (role: ConnectionRole) => {
      const item = props.context.selection(role)
      if (item.describing) return <NSpin size={14} />
      if (item.error) {
        return (
          <NTag size='small' type='error' bordered={false}>
            {item.error}
          </NTag>
        )
      }
      const count = item.descriptions.reduce(
        (total, description) => total + description.fields.length,
        0
      )
      if (!item.descriptions.length) return null
      return (
        <NPopover
          trigger='click'
          placement='bottom-end'
          scrollable
          style={{ maxHeight: '420px', maxWidth: '560px' }}
        >
          {{
            trigger: () => (
              <NTag
                size='small'
                type='info'
                bordered={false}
                class={styles.clickable}
              >
                {t('project.node.ai_context_fields', { n: count })}
              </NTag>
            ),
            default: () => renderDescriptions(item.descriptions)
          }}
        </NPopover>
      )
    }

    const renderRole = (role: ConnectionRole) => {
      const item = props.context.selection(role)
      const type = props.context.typeOf(item.connectionKey)
      return (
        <div class={styles.role}>
          <span class={styles.label}>{roleLabel(role)}</span>
          <NSelect
            class={styles.connection}
            size='small'
            clearable
            filterable
            disabled={props.readonly}
            placeholder={t('project.node.ai_context_connection')}
            value={item.connectionKey}
            options={connectionOptions(role) as any}
            onUpdateValue={(value: string | null) => onConnection(role, value)}
          />
          {type?.objectKind === 'table' && renderTables(role)}
          {type?.objectKind === 'topic' &&
            renderFlatObjects(role, role === 'SOURCE')}
          {type?.type === 'HTTP' && renderFlatObjects(role, false)}
          {type?.type === 'S3' && renderS3(role)}
          <span class={styles.status}>{renderStatus(role)}</span>
        </div>
      )
    }

    return () => {
      const { context } = props
      if (!context.state.connections.length) return null
      const source = context.summary('SOURCE')
      const sink = context.summary('SINK')
      return (
        <div class={styles['context-bar']}>
          <div
            class={styles.header}
            onClick={() => (expanded.value = !expanded.value)}
          >
            <NIcon size={12}>
              {expanded.value ? <DownOutlined /> : <RightOutlined />}
            </NIcon>
            <span class={styles.title}>
              {t('project.node.ai_context_title')}
            </span>
            <span class={styles.summary}>
              {source || sink
                ? [
                    source && `${roleLabel('SOURCE')}: ${source}`,
                    sink && `${roleLabel('SINK')}: ${sink}`
                  ]
                    .filter(Boolean)
                    .join('  ·  ')
                : t('project.node.ai_context_none')}
            </span>
          </div>
          {expanded.value && (
            <div class={styles.body}>
              {renderRole('SOURCE')}
              {renderRole('SINK')}
            </div>
          )}
        </div>
      )
    }
  }
})
