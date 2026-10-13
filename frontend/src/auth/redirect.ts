import { validKnowledgeBaseId } from '../api/knowledgeBases'

export function safeDestination(value: unknown): string {
  if (typeof value !== 'string') return '/app'
  if (['/app', '/app/health', '/app/knowledge-bases'].includes(value)) return value
  const match = /^\/app\/knowledge-bases\/([^/]+)$/.exec(value)
  return match && validKnowledgeBaseId(match[1]) ? value : '/app'
}
