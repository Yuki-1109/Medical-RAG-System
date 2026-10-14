import { validKnowledgeBaseId } from '../api/knowledgeBases'

export function safeDestination(value: unknown): string {
  if (typeof value !== 'string') return '/app'
  if (['/app', '/app/health', '/app/knowledge-bases', '/app/settings/models'].includes(value)) return value
  const match = /^\/app\/knowledge-bases\/([^/]+)$/.exec(value)
  if (match && validKnowledgeBaseId(match[1])) return value
  const images = /^\/app\/knowledge-bases\/([^/]+)\/images(?:\/([^/?]+))?$/.exec(value)
  if (images && validKnowledgeBaseId(images[1]) && (!images[2] || validKnowledgeBaseId(images[2]))) return value
  const documents = /^\/app\/knowledge-bases\/([^/]+)\/documents(?:\/([^/?]+)(?:\?page=([1-9][0-9]{0,4}))?)?$/.exec(value)
  return documents && validKnowledgeBaseId(documents[1]) && (!documents[2] || validKnowledgeBaseId(documents[2])) ? value : '/app'
}
