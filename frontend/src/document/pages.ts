export function previewPage(value: unknown, count: number): number | null {
  if (typeof value !== 'string' && typeof value !== 'number') return null
  const text = String(value)
  if (!/^[1-9][0-9]*$/.test(text)) return null
  const page = Number(text)
  return Number.isSafeInteger(page) && page <= count ? page : null
}
