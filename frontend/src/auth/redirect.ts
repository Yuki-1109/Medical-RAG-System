export function safeDestination(value: unknown): string {
  return typeof value === 'string' && ['/app', '/app/health'].includes(value) ? value : '/app'
}
