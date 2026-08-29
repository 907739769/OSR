<template>
  <div class="page-container">
    <PageHeader
      icon="plug"
      title="MCP 令牌"
      desc="签发给本地 AI 助理的长期访问凭据 — 助理凭它通过 MCP 协议查询订阅、追剧进度并触发任务"
    >
      <template #actions>
        <v-btn color="primary" variant="outlined" prepend-icon="book-open" @click="showGuide = true">
          连接说明
        </v-btn>
      </template>
    </PageHeader>

    <SearchPanel ref="queryRef" :visible="showSearch" @search="handleQuery" @reset="resetQuery">
      <v-text-field
        v-model="queryParams.name"
        label="令牌名称"
        placeholder="支持模糊匹配"
        clearable
        density="compact"
        variant="outlined"
        hide-details
        class="field-md"
        @keyup.enter="handleQuery"
      />
    </SearchPanel>

    <v-card class="table-card">
      <div class="action-bar">
        <div class="action-left">
          <v-btn color="primary" prepend-icon="plus" @click="openIssueDialog">签发令牌</v-btn>
        </div>
        <v-btn variant="text" prepend-icon="funnel" @click="showSearch = !showSearch">
          {{ showSearch ? '隐藏搜索' : '显示搜索' }}
        </v-btn>
      </div>

      <div class="card-grid">
        <v-progress-linear v-if="loading" indeterminate color="primary" />
        <div v-for="item in tokenList" :key="item.id" class="item-card">
          <div class="card-header">
            <span class="card-title" :title="item.name">{{ item.name }}</span>
            <StatusChip
              :type="statusOf(item).type"
              :text="statusOf(item).text"
            />
          </div>
          <div class="card-body">
            <div class="card-row">
              <span class="label">权限档</span>
              <span class="value">
                <v-chip size="x-small" :color="SCOPE_COLOR[item.scope]" variant="tonal">
                  {{ SCOPE_LABEL[item.scope] }}
                </v-chip>
              </span>
            </div>
            <div class="card-row">
              <span class="label">令牌</span>
              <span class="value">{{ item.tokenPrefix }}……</span>
            </div>
            <div class="card-row">
              <span class="label">最后使用</span>
              <span class="value">{{ item.lastUsedTime || '从未使用' }}</span>
            </div>
            <div class="card-row">
              <span class="label">有效期</span>
              <span class="value">{{ item.expireTime || '长期有效' }}</span>
            </div>
            <div class="card-row">
              <span class="label">备注</span>
              <span class="value">{{ item.remark || '-' }}</span>
            </div>
          </div>
          <div class="card-footer">
            <v-btn
              variant="text"
              size="small"
              :color="item.enabled === '1' ? 'warning' : 'success'"
              :prepend-icon="item.enabled === '1' ? 'ban' : 'circle-check'"
              @click="handleToggle(item)"
            >
              {{ item.enabled === '1' ? '停用' : '启用' }}
            </v-btn>
            <v-btn variant="text" color="error" size="small" prepend-icon="trash-2" @click="handleDelete(item)">
              删除
            </v-btn>
          </div>
        </div>
        <v-empty-state
          v-if="!loading && tokenList.length === 0"
          icon="plug"
          title="还没有签发过令牌"
          text="给每个助理/每台机器单独签一枚，日后要停掉其中一个时不会影响其它的"
        />
      </div>

      <div class="pagination-wrapper">
        <span class="total-text">共 {{ total }} 条</span>
        <v-pagination
          v-model="queryParams.pageNum"
          :length="Math.ceil(total / queryParams.pageSize!) || 1"
          density="comfortable"
          @update:model-value="getList"
        />
      </div>
    </v-card>

    <!-- 签发 -->
    <v-dialog v-model="issueDialog" max-width="560" persistent>
      <v-card>
        <v-card-title class="dialog-title">签发 MCP 令牌</v-card-title>
        <v-card-text class="dialog-body">
          <v-text-field
            v-model="issueForm.name"
            label="令牌名称"
            placeholder="例如：书房电脑上的 Claude Code"
            density="compact"
            variant="outlined"
            :rules="[(v: string) => !!v || '请填写名称']"
          />
          <v-select
            v-model="issueForm.scope"
            label="权限档"
            :items="SCOPE_OPTIONS"
            density="compact"
            variant="outlined"
          />
          <v-alert type="info" variant="tonal" density="compact" class="scope-hint">
            {{ SCOPE_HINT[issueForm.scope] }}
          </v-alert>
          <v-text-field
            v-model.number="issueForm.expireDays"
            label="有效天数"
            type="number"
            placeholder="留空表示长期有效"
            density="compact"
            variant="outlined"
          />
          <v-text-field
            v-model="issueForm.remark"
            label="备注"
            density="compact"
            variant="outlined"
          />
        </v-card-text>
        <v-card-actions>
          <v-spacer />
          <v-btn variant="text" @click="issueDialog = false">取消</v-btn>
          <v-btn color="primary" :loading="issuing" @click="handleIssue">签发</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>

    <!-- 签发结果：明文只在这里出现这一次 -->
    <v-dialog v-model="issuedDialog" max-width="720" persistent>
      <v-card>
        <v-card-title class="dialog-title">
          <v-icon icon="key-round" size="small" class="mr-2" />令牌已签发
        </v-card-title>
        <v-card-text class="dialog-body">
          <v-alert type="warning" variant="tonal" density="compact" class="mb-4">
            <b>这是这枚令牌唯一一次完整显示。</b>关掉这个窗口之后，服务端也拿不回明文了——
            忘了保存只能重新签发一枚。
          </v-alert>

          <div class="secret-box">
            <code>{{ issuedToken }}</code>
            <v-btn size="small" variant="tonal" prepend-icon="copy" @click="copy(issuedToken)">复制</v-btn>
          </div>

          <div class="snippet-title">Claude Code（终端里执行一次即可）</div>
          <div class="secret-box">
            <code>{{ claudeCodeCommand }}</code>
            <v-btn size="small" variant="tonal" prepend-icon="copy" @click="copy(claudeCodeCommand)">复制</v-btn>
          </div>

          <div class="snippet-title">只支持 stdio 的客户端（经 mcp-remote 桥接）</div>
          <div class="secret-box">
            <code><pre>{{ mcpRemoteConfig }}</pre></code>
            <v-btn size="small" variant="tonal" prepend-icon="copy" @click="copy(mcpRemoteConfig)">复制</v-btn>
          </div>
        </v-card-text>
        <v-card-actions>
          <v-spacer />
          <v-btn color="primary" @click="issuedDialog = false">我已保存</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>

    <!-- 连接说明 -->
    <v-dialog v-model="showGuide" max-width="720" scrollable>
      <v-card>
        <v-card-title class="dialog-title">如何连接</v-card-title>
        <v-card-text class="dialog-body guide">
          <p>MCP 端点：<code>{{ mcpEndpoint }}</code>，认证方式为 <code>Authorization: Bearer &lt;令牌&gt;</code>。</p>
          <p>
            <b>部署提示：</b>如果通过 Nginx 反向代理访问，<code>/mcp</code> 需要单独一条 location，
            并关掉 <code>proxy_buffering</code>、把 <code>proxy_read_timeout</code> 放大。
            现有的 <code>/api/</code> 那条规则是 120 秒超时且开着缓冲，MCP 的长连接撑不住——
            症状很有迷惑性：握手正常、工具列表也拿得到，只有跑得久的调用会莫名断线。
          </p>
          <p>
            <b>安全提示：</b>令牌长期有效且以签发人的身份行动。把 <code>/mcp</code> 暴露到公网，
            等于把一把长期有效的钥匙挂在外面，建议只在内网或 VPN 内访问。
          </p>
          <p><b>助理能做什么：</b>查询订阅与追剧进度、缺集体检、下载记录与统计，按权限档还可以建订阅、
            触发补搜与各类任务。<b>删网盘文件、删种、改索引器/下载器/参数配置这些一律没有开放</b>，
            需要时请在网页端操作。</p>
        </v-card-text>
        <v-card-actions>
          <v-spacer />
          <v-btn color="primary" variant="text" @click="showGuide = false">知道了</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>
  </div>
</template>

<script setup lang="ts">
import PageHeader from '@/components/PageHeader.vue'
import SearchPanel from '@/components/SearchPanel.vue'
import StatusChip from '@/components/StatusChip.vue'
import { useSearchPanel } from '@/composables/useSearchPanel'
import { message } from '@/composables/useMessage'
import { confirm } from '@/composables/useConfirm'
import {
  deleteMcpTokenApi,
  getMcpTokenListApi,
  issueMcpTokenApi,
  setMcpTokenEnabledApi,
  type McpScope,
  type McpToken,
  type McpTokenQuery
} from '@/api/system/mcpToken'

const SCOPE_LABEL: Record<McpScope, string> = { read: '只读', write: '可写', admin: '完全' }
const SCOPE_COLOR: Record<McpScope, string> = { read: 'info', write: 'warning', admin: 'error' }
const SCOPE_OPTIONS = [
  { title: '只读 — 只能查询，做错了没有代价', value: 'read' },
  { title: '可写 — 可建订阅、触发补搜与任务，界面上点几下就能撤销', value: 'write' },
  { title: '完全 — 含删订阅、重置集、拉黑种子等难以撤销的操作', value: 'admin' }
]
const SCOPE_HINT: Record<McpScope, string> = {
  read: '推荐从这一档开始。助理能回答「哪部剧还缺集」「为什么没抓到」，但改不了任何东西。',
  write: '助理可以建订阅、触发补搜与 STRM/同步/重命名任务。这些都能在界面上撤销。',
  admin: '助理可以删订阅、把某一集重置为缺失、拉黑种子或整个发布组。这些撤销起来要花力气，'
    + '除非确有需要，否则别发这一档。'
}

const { showSearch } = useSearchPanel()

const tokenList = ref<McpToken[]>([])
const total = ref(0)
const loading = ref(false)
const queryRef = ref()
const queryParams = reactive<McpTokenQuery>({ pageNum: 1, pageSize: 12, name: undefined })

const issueDialog = ref(false)
const issuing = ref(false)
const issueForm = reactive<{ name: string; scope: McpScope; expireDays: number | null; remark: string }>({
  name: '',
  scope: 'read',
  expireDays: null,
  remark: ''
})

const issuedDialog = ref(false)
const issuedToken = ref('')
const showGuide = ref(false)

/** MCP 端点按当前站点推导。刻意不做成配置项：用户是从哪个地址打开这个页面的，助理多半也从那个地址连 */
const mcpEndpoint = computed(() => `${window.location.origin}/mcp`)

const claudeCodeCommand = computed(
  () => `claude mcp add --transport http osr ${mcpEndpoint.value} --header "Authorization: Bearer ${issuedToken.value}"`
)

const mcpRemoteConfig = computed(() => JSON.stringify({
  mcpServers: {
    osr: {
      command: 'npx',
      args: ['-y', 'mcp-remote', mcpEndpoint.value, '--header', `Authorization: Bearer ${issuedToken.value}`]
    }
  }
}, null, 2))

/** 停用与过期是两回事，都要能一眼看出来：停用是人按下的，过期是到点自动失效的 */
function statusOf(item: McpToken): { type: 'success' | 'error' | 'warning'; text: string } {
  if (item.enabled !== '1') {
    return { type: 'error', text: '已停用' }
  }
  if (item.expireTime && new Date(item.expireTime).getTime() < Date.now()) {
    return { type: 'warning', text: '已过期' }
  }
  return { type: 'success', text: '生效中' }
}

async function getList() {
  loading.value = true
  try {
    const res = await getMcpTokenListApi(queryParams)
    tokenList.value = res.records || []
    total.value = res.total || 0
  } catch (error) {
    // 拦截器已经弹过后端的 message，这里再弹一条只会把准确原因盖掉
    console.error(error)
  } finally {
    loading.value = false
  }
}

function handleQuery() {
  queryParams.pageNum = 1
  getList()
}

function resetQuery() {
  // 不清分页大小，理由同其它列表页：那是用户对这一页的偏好，不是查询条件
  queryParams.name = undefined
  handleQuery()
}

function openIssueDialog() {
  issueForm.name = ''
  issueForm.scope = 'read'
  issueForm.expireDays = null
  issueForm.remark = ''
  issueDialog.value = true
}

async function handleIssue() {
  if (!issueForm.name.trim()) {
    message.warning('请填写令牌名称')
    return
  }
  issuing.value = true
  try {
    const res = await issueMcpTokenApi({
      name: issueForm.name.trim(),
      scope: issueForm.scope,
      expireDays: issueForm.expireDays,
      remark: issueForm.remark
    })
    issueDialog.value = false
    issuedToken.value = res.token
    issuedDialog.value = true
    getList()
  } catch (error) {
    console.error(error)
  } finally {
    issuing.value = false
  }
}

async function handleToggle(item: McpToken) {
  const enable = item.enabled !== '1'
  if (!enable) {
    await confirm({
      title: '停用令牌',
      message: `停用「${item.name}」后，使用它的助理会立刻失去访问权限（无缓存，即刻生效）。继续？`
    }).catch(() => Promise.reject())
  }
  try {
    await setMcpTokenEnabledApi(item.id, enable)
    message.success(enable ? '已启用' : '已停用')
    getList()
  } catch (error) {
    console.error(error)
  }
}

async function handleDelete(item: McpToken) {
  try {
    await confirm({
      title: '删除令牌',
      message: `删除「${item.name}」后无法恢复，使用它的助理会立刻失去访问权限。`
        + `只是想临时切断的话，用「停用」即可。继续？`
    })
  } catch {
    return
  }
  try {
    await deleteMcpTokenApi(item.id)
    message.success('已删除')
    getList()
  } catch (error) {
    console.error(error)
  }
}

async function copy(text: string) {
  try {
    await navigator.clipboard.writeText(text)
    message.success('已复制到剪贴板')
  } catch {
    // 非 HTTPS 或浏览器不允许时 clipboard API 不可用。这里只提示，不做 execCommand 兜底——
    // 那个已废弃且在部分浏览器上会静默失败，给出一个"看起来成功了"的假象更糟
    message.warning('浏览器不允许自动复制，请手动选中上面的内容复制')
  }
}

onMounted(getList)
</script>

<style scoped lang="scss">
.scope-hint {
  margin-bottom: 12px;
}

.snippet-title {
  margin: 16px 0 6px;
  font-size: 13px;
  font-weight: 600;
  opacity: 0.8;
}

.secret-box {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 12px;
  border-radius: 8px;
  background: rgb(var(--v-theme-surface-variant), 0.35);
  border: 1px solid rgb(var(--v-border-color), var(--v-border-opacity));

  code {
    flex: 1;
    min-width: 0;
    font-size: 12px;
    line-height: 1.6;
    word-break: break-all;
    background: none;

    pre {
      margin: 0;
      white-space: pre-wrap;
      word-break: break-all;
      font: inherit;
    }
  }
}

.guide {
  p {
    margin-bottom: 12px;
    line-height: 1.7;
  }

  code {
    padding: 1px 5px;
    border-radius: 4px;
    background: rgb(var(--v-theme-surface-variant), 0.4);
    font-size: 12px;
  }
}
</style>
