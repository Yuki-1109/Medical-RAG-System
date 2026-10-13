<script setup lang="ts">
import { ref, watch } from 'vue'
import { ElAlert, ElButton, ElDialog, ElInput } from 'element-plus'
import { validateKnowledgeBase, type KnowledgeBase, type KnowledgeBaseInput } from '../api/knowledgeBases'
const props = defineProps<{ open: boolean; editing: KnowledgeBase | null; busy: boolean; error: string }>()
const emit = defineEmits<{ close: []; save: [input: KnowledgeBaseInput] }>()
const name = ref('')
const description = ref('')
const validation = ref('')
watch(() => props.open, (open) => {
  if (open) { name.value = props.editing?.name || ''; description.value = props.editing?.description || ''; validation.value = '' }
})
function submit() {
  if (props.busy) return
  const input = { name: name.value.trim(), description: description.value.trim() }
  validation.value = validateKnowledgeBase(input)
  if (!validation.value) emit('save', input)
}
</script>

<template>
  <el-dialog :model-value="open" :title="editing ? '编辑知识库' : '新建知识库'" width="560px" :close-on-click-modal="false" :close-on-press-escape="!busy" :show-close="!busy" @close="!busy && emit('close')" destroy-on-close>
    <el-alert v-if="validation || error" :title="validation || error" type="error" :closable="false" show-icon />
    <form class="knowledge-base-form" @submit.prevent="submit">
      <label for="knowledge-base-name">知识库名称</label>
      <el-input id="knowledge-base-name" v-model="name" :disabled="busy" placeholder="例如：心血管影像研究" />
      <small>1～100 个字符，名称不能为空。</small>
      <label for="knowledge-base-description">知识库简介</label>
      <el-input id="knowledge-base-description" v-model="description" type="textarea" :rows="4" :disabled="busy" placeholder="可选，简要说明研究主题或资料范围" />
      <small>最多 2000 个字符。</small>
      <div class="form-actions"><el-button :disabled="busy" @click="emit('close')">取消</el-button><el-button native-type="submit" type="primary" :loading="busy">{{ editing ? '保存修改' : '创建知识库' }}</el-button></div>
    </form>
  </el-dialog>
</template>
