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

import { defineComponent, PropType, toRefs, h, unref, VNode } from 'vue'
import { NSpin, NGrid, NForm, NFormItemGi, FormRules } from 'naive-ui'
import { useForm } from './use-form'
import styles from './index.module.scss'
import type { GridProps, IFormItem, IMeta } from './types'

const props = {
  meta: {
    type: Object as PropType<IMeta>,
    default: {},
    required: true
  },
  layout: {
    type: Object as PropType<GridProps>
  },
  loading: {
    type: Boolean as PropType<boolean>,
    default: false
  },
  // Paths of the elements rendered in the left column, the others are rendered in the right column
  splitPaths: {
    type: Array as PropType<string[]>,
    default: () => []
  },
  // Three columns: the other elements | middle | the split elements
  middle: {
    type: Function as PropType<() => VNode | null>
  },
  // Rendered instead of the split elements when it returns a node, the split elements stay mounted
  splitOverlay: {
    type: Function as PropType<() => VNode | null>
  }
}

const Form = defineComponent({
  name: 'DSForm',
  props,
  setup(props, { expose }) {
    const { state, ...rest } = useForm()
    expose({
      ...rest
    })
    return { ...toRefs(state) }
  },
  render(props: {
    meta: IMeta
    layout?: GridProps
    loading?: boolean
    splitPaths?: string[]
    middle?: () => VNode | null
    splitOverlay?: () => VNode | null
  }) {
    const {
      loading,
      layout,
      meta,
      splitPaths = [],
      middle,
      splitOverlay
    } = props
    const { elements = [], ...restFormProps } = meta

    const renderGrid = (items: IFormItem[]) => (
      <NGrid {...layout}>
        {items.map((element) => {
          const { span = 24, path, widget, ...formItemProps } = element

          return (
            <NFormItemGi
              {...formItemProps}
              span={unref(span) === void 0 ? 24 : unref(span)}
              path={path}
              key={path || String(Date.now() + Math.random())}
            >
              {h(widget)}
            </NFormItemGi>
          )
        })}
      </NGrid>
    )

    const leftElements = elements.filter((element) =>
      splitPaths.includes(element.path)
    )
    const otherElements = elements.filter(
      (element) => !splitPaths.includes(element.path)
    )
    const overlay = splitOverlay ? splitOverlay() : null

    return (
      <NSpin show={loading}>
        <NForm {...restFormProps} rules={meta.rules as FormRules} ref='formRef'>
          {leftElements.length && middle ? (
            <div class={[styles.split, styles.three]}>
              <div class={styles.settings}>{renderGrid(otherElements)}</div>
              <div class={styles.middle}>{middle()}</div>
              <div class={styles.editor}>
                {overlay}
                <div v-show={!overlay}>{renderGrid(leftElements)}</div>
              </div>
            </div>
          ) : leftElements.length ? (
            <div class={styles.split}>
              <div class={styles.left}>{renderGrid(leftElements)}</div>
              <div class={styles.right}>{renderGrid(otherElements)}</div>
            </div>
          ) : (
            renderGrid(elements)
          )}
        </NForm>
      </NSpin>
    )
  }
})

export default Form
