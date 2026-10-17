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

import { defineComponent, h, onMounted, ref } from 'vue'
import {
  NButton,
  NDataTable,
  NPopconfirm,
  NSpace,
  NTag,
  NTooltip
} from 'naive-ui'
import { useI18n } from 'vue-i18n'
import Card from '@/components/card'
import ConnectionModal from './connection-modal'
import {
  deleteConnection,
  queryConnections,
  queryConnectionTypes
} from '@/service/modules/seatunnel-connections'
import type {
  ConnectionTypeDef,
  SeatunnelConnection
} from '@/service/modules/seatunnel-connections/types'

/**
 * SeaTunnel connections of the security center: Kafka clusters, databases, HTTP APIs and S3 buckets used by the
 * SeaTunnel AI assistant. The scripts reference the secrets as ${stconn.<key>.<field>}.
 */
export default defineComponent({
  name: 'seatunnel-connection',
  setup() {
    const { t } = useI18n()
    const loading = ref(false)
    const data = ref<SeatunnelConnection[]>([])
    const types = ref<ConnectionTypeDef[]>([])
    const showModal = ref(false)
    const editing = ref<SeatunnelConnection | null>(null)

    const load = async () => {
      loading.value = true
      try {
        data.value = await queryConnections()
      } finally {
        loading.value = false
      }
    }

    onMounted(async () => {
      types.value = await queryConnectionTypes()
      load()
    })

    const open = (row: SeatunnelConnection | null) => {
      editing.value = row
      showModal.value = true
    }

    const remove = async (row: SeatunnelConnection) => {
      await deleteConnection(row.code)
      window.$message.success(t('security.seatunnel_connection.deleted'))
      load()
    }

    const typeLabel = (type: string) =>
      types.value.find((item) => item.type === type)?.label || type

    const columns = () => [
      {
        title: t('security.seatunnel_connection.name'),
        key: 'name',
        minWidth: 160
      },
      {
        title: t('security.seatunnel_connection.key'),
        key: 'connKey',
        minWidth: 140,
        render: (row: SeatunnelConnection) =>
          h(
            NTooltip,
            {},
            {
              trigger: () => h('code', {}, row.connKey),
              default: () => `\${stconn.${row.connKey}.<field>}`
            }
          )
      },
      {
        title: t('security.seatunnel_connection.type'),
        key: 'type',
        width: 120,
        render: (row: SeatunnelConnection) =>
          h(NTag, { size: 'small', bordered: false }, () => typeLabel(row.type))
      },
      {
        title: t('security.seatunnel_connection.description'),
        key: 'description',
        minWidth: 200,
        ellipsis: { tooltip: true }
      },
      {
        title: t('security.seatunnel_connection.update_user'),
        key: 'updateUserName',
        width: 160
      },
      {
        title: t('security.seatunnel_connection.update_time'),
        key: 'updateTime',
        width: 180
      },
      {
        title: t('security.seatunnel_connection.operation'),
        key: 'operation',
        width: 150,
        render: (row: SeatunnelConnection) =>
          h(NSpace, {}, () => [
            h(
              NButton,
              { size: 'tiny', type: 'info', onClick: () => open(row) },
              () => t('security.seatunnel_connection.edit')
            ),
            h(
              NPopconfirm,
              { onPositiveClick: () => remove(row) },
              {
                trigger: () =>
                  h(NButton, { size: 'tiny', type: 'error' }, () =>
                    t('security.seatunnel_connection.delete')
                  ),
                default: () => t('security.seatunnel_connection.delete_confirm')
              }
            )
          ])
      }
    ]

    return () => (
      <NSpace vertical>
        <Card>
          <NSpace justify='space-between'>
            <NButton size='small' type='primary' onClick={() => open(null)}>
              {t('security.seatunnel_connection.create')}
            </NButton>
          </NSpace>
        </Card>
        <Card title={t('menu.seatunnel_connection')}>
          <NDataTable
            loading={loading.value}
            columns={columns()}
            data={data.value}
            rowKey={(row: SeatunnelConnection) => row.code}
            scrollX={1100}
          />
        </Card>
        <ConnectionModal
          show={showModal.value}
          types={types.value}
          row={editing.value}
          onClose={() => (showModal.value = false)}
          onSaved={() => {
            showModal.value = false
            load()
          }}
        />
      </NSpace>
    )
  }
})
