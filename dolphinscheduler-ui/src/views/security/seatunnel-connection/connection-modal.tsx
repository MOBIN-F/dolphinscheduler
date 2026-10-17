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

import { computed, defineComponent, PropType, reactive, ref, watch } from 'vue'
import {
  NButton,
  NForm,
  NFormItem,
  NInput,
  NModal,
  NSelect,
  NSpace
} from 'naive-ui'
import { useI18n } from 'vue-i18n'
import {
  createConnection,
  testConnection,
  updateConnection
} from '@/service/modules/seatunnel-connections'
import type {
  ConnectionField,
  ConnectionTypeDef,
  SeatunnelConnection,
  SeatunnelConnectionReq
} from '@/service/modules/seatunnel-connections/types'

const KEY_PATTERN = /^[a-z][a-z0-9_]{1,63}$/

/**
 * Creates or edits a SeaTunnel connection, the fields come from the connection type definitions of the server.
 * The secrets are write-only: the saved values are never loaded, an empty value keeps the saved one.
 */
export default defineComponent({
  name: 'SeatunnelConnectionModal',
  props: {
    show: { type: Boolean, default: false },
    types: {
      type: Array as PropType<ConnectionTypeDef[]>,
      default: () => []
    },
    /**
     * The connection to edit, null to create one
     */
    row: {
      type: Object as PropType<SeatunnelConnection | null>,
      default: null
    }
  },
  emits: ['close', 'saved'],
  setup(props, { emit }) {
    const { t, locale } = useI18n()
    const model = reactive({
      type: '',
      name: '',
      connKey: '',
      description: '',
      values: {} as Record<string, string>
    })
    const saving = ref(false)
    const testing = ref(false)
    const editing = computed(() => !!props.row)

    const typeDef = computed(() =>
      props.types.find((item) => item.type === model.type)
    )

    const reset = () => {
      const row = props.row
      model.type = row?.type || props.types[0]?.type || ''
      model.name = row?.name || ''
      model.connKey = row?.connKey || ''
      model.description = row?.description || ''
      model.values = { ...(row?.params || {}) }
      applyDefaults()
    }

    const applyDefaults = () => {
      typeDef.value?.fields.forEach((field) => {
        if (!field.secret && model.values[field.key] === undefined) {
          model.values[field.key] = field.defaultValue || ''
        }
      })
    }

    watch(
      () => props.show,
      (show) => show && reset()
    )
    watch(
      () => model.type,
      () => {
        if (!editing.value) applyDefaults()
      }
    )

    const isActive = (field: ConnectionField) =>
      Object.entries(field.showWhen || {}).every(([key, values]) =>
        values.includes(model.values[key] || '')
      )

    const activeFields = computed(() =>
      (typeDef.value?.fields || []).filter(isActive)
    )

    const isSecretSet = (field: ConnectionField) =>
      editing.value && !!props.row?.secretsSet?.includes(field.key)

    const label = (field: ConnectionField) =>
      String(locale.value).startsWith('zh') ? field.labelZh : field.label

    const missing = computed(() => {
      if (!model.type || !model.name.trim()) return true
      if (!editing.value && !KEY_PATTERN.test(model.connKey)) return true
      return activeFields.value.some(
        (field) =>
          field.required &&
          !(model.values[field.key] || '').trim() &&
          !(field.secret && isSecretSet(field))
      )
    })

    const request = (): SeatunnelConnectionReq => {
      const params: Record<string, string> = {}
      const secrets: Record<string, string> = {}
      typeDef.value?.fields.forEach((field) => {
        const value = model.values[field.key]
        if (value === undefined) return
        if (field.secret) {
          if (value) secrets[field.key] = value
        } else {
          params[field.key] = value
        }
      })
      return {
        type: model.type,
        name: model.name.trim(),
        connKey: model.connKey.trim(),
        description: model.description,
        params,
        secrets
      }
    }

    const onTest = async () => {
      testing.value = true
      try {
        await testConnection(request(), props.row?.code)
        window.$message.success(t('security.seatunnel_connection.test_success'))
      } finally {
        testing.value = false
      }
    }

    const onSave = async () => {
      saving.value = true
      try {
        if (props.row) {
          await updateConnection(props.row.code, request())
        } else {
          await createConnection(request())
        }
        window.$message.success(t('security.seatunnel_connection.saved'))
        emit('saved')
      } finally {
        saving.value = false
      }
    }

    const renderField = (field: ConnectionField) => {
      const placeholder =
        field.secret && isSecretSet(field)
          ? t('security.seatunnel_connection.secret_kept')
          : field.defaultValue || ''
      const value = model.values[field.key] || ''
      const update = (v: string) => (model.values[field.key] = v)
      if (field.input === 'select') {
        return (
          <NSelect
            value={value}
            options={field.options.map((option) => ({
              label: option,
              value: option
            }))}
            onUpdateValue={update}
          />
        )
      }
      return (
        <NInput
          value={value}
          type={
            field.input === 'password'
              ? 'password'
              : field.input === 'textarea'
              ? 'textarea'
              : 'text'
          }
          showPasswordOn={field.input === 'password' ? 'click' : undefined}
          autosize={
            field.input === 'textarea' ? { minRows: 2, maxRows: 8 } : undefined
          }
          placeholder={placeholder}
          onUpdateValue={update}
        />
      )
    }

    return () => (
      <NModal
        show={props.show}
        preset='card'
        style={{ width: '640px' }}
        title={
          editing.value
            ? t('security.seatunnel_connection.edit')
            : t('security.seatunnel_connection.create')
        }
        onUpdateShow={(show: boolean) => !show && emit('close')}
      >
        {{
          default: () => (
            <NForm labelPlacement='left' labelWidth={150} size='small'>
              <NFormItem
                label={t('security.seatunnel_connection.type')}
                required
              >
                <NSelect
                  value={model.type}
                  disabled={editing.value}
                  options={props.types.map((item) => ({
                    label: item.label,
                    value: item.type
                  }))}
                  onUpdateValue={(v: string) => (model.type = v)}
                />
              </NFormItem>
              <NFormItem
                label={t('security.seatunnel_connection.name')}
                required
              >
                <NInput
                  value={model.name}
                  maxlength={128}
                  onUpdateValue={(v: string) => (model.name = v)}
                />
              </NFormItem>
              <NFormItem
                label={t('security.seatunnel_connection.key')}
                required
                feedback={t('security.seatunnel_connection.key_tips')}
                validationStatus={
                  !editing.value &&
                  model.connKey &&
                  !KEY_PATTERN.test(model.connKey)
                    ? 'error'
                    : undefined
                }
              >
                <NInput
                  value={model.connKey}
                  disabled={editing.value}
                  placeholder='kafka_prod'
                  onUpdateValue={(v: string) => (model.connKey = v)}
                />
              </NFormItem>
              {activeFields.value.map((field) => (
                <NFormItem
                  key={field.key}
                  label={label(field)}
                  required={field.required}
                >
                  {renderField(field)}
                </NFormItem>
              ))}
              <NFormItem label={t('security.seatunnel_connection.description')}>
                <NInput
                  value={model.description}
                  type='textarea'
                  autosize={{ minRows: 2, maxRows: 4 }}
                  onUpdateValue={(v: string) => (model.description = v)}
                />
              </NFormItem>
            </NForm>
          ),
          footer: () => (
            <NSpace justify='end'>
              <NButton
                size='small'
                loading={testing.value}
                disabled={missing.value}
                onClick={onTest}
              >
                {t('security.seatunnel_connection.test')}
              </NButton>
              <NButton size='small' onClick={() => emit('close')}>
                {t('security.seatunnel_connection.cancel')}
              </NButton>
              <NButton
                size='small'
                type='primary'
                loading={saving.value}
                disabled={missing.value}
                onClick={onSave}
              >
                {t('security.seatunnel_connection.confirm')}
              </NButton>
            </NSpace>
          )
        }}
      </NModal>
    )
  }
})
