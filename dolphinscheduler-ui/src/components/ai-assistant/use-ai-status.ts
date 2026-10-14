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

import { ref } from 'vue'
import { getAiStatus } from '@/service/modules/ai'
import type { AiStatus } from '@/service/modules/ai/types'

const status = ref<AiStatus | null>(null)
let loading: Promise<void> | null = null

/**
 * Status of the AI assistant, loaded once and shared by all components.
 */
export function useAiStatus() {
  if (!loading) {
    loading = getAiStatus()
      .then((result) => {
        status.value = result
      })
      .catch(() => {
        status.value = {
          enabled: false,
          seatunnelEngineValidation: false,
          seatunnelVersion: ''
        }
      })
  }
  return status
}
