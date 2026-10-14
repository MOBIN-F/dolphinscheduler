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

const escapeHtml = (text: string) =>
  text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')

const inline = (text: string) =>
  text
    .replace(/`([^`]+)`/g, '<code>$1</code>')
    .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')

/**
 * Render the small subset of markdown used by the assistant replies: code blocks, inline code, bold, lists and
 * headings. The text is escaped first, so the result is safe to use as innerHTML.
 */
export function renderMarkdown(markdown: string): string {
  const blocks = escapeHtml(markdown || '').split(/```/)
  return blocks
    .map((block, index) => {
      if (index % 2 === 1) {
        // Code block, drop the language of the first line
        const code = block.replace(/^[\w-]*\r?\n/, '')
        return '<pre><code>' + code.replace(/\n$/, '') + '</code></pre>'
      }
      const lines = block.split(/\r?\n/)
      const html: string[] = []
      let inList = false
      for (const line of lines) {
        const item = line.match(/^\s*(?:[-*]|\d+\.)\s+(.*)$/)
        if (item) {
          if (!inList) html.push('<ul>')
          inList = true
          html.push('<li>' + inline(item[1]) + '</li>')
          continue
        }
        if (inList) html.push('</ul>')
        inList = false
        const heading = line.match(/^#{1,6}\s+(.*)$/)
        if (heading) {
          html.push('<p><strong>' + inline(heading[1]) + '</strong></p>')
        } else if (line.trim()) {
          html.push('<p>' + inline(line) + '</p>')
        }
      }
      if (inList) html.push('</ul>')
      return html.join('')
    })
    .join('')
}
