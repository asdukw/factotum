export async function resolve(specifier, context, nextResolve) {
  if (specifier === '@deepseek-ai/dsh-tools') {
    return {
      url: new URL('./stub-dsh-tools.mjs', import.meta.url).href,
      shortCircuit: true,
    }
  }
  return nextResolve(specifier, context)
}
