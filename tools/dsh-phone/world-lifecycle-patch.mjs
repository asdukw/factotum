// Pure transformation of @deepseek-ai/dsh-sdk-jsonrpc-server 0.2.0-rc.2.
// Assembly, not this module, owns writing the staged vendor bundle.
export function patchWorldLifecycle(source) {
  const replacements = [
    [
      '\t\trec.handle.agent.followup(message);',
      '\t\tconst zoneuLifecycle = this.ctx.get("zoneuWorldLifecycle");\n' +
      '\t\tif (params.world_request_id !== void 0 && !zoneuLifecycle) throw new Error("WORLD_LIFECYCLE_UNAVAILABLE");\n' +
      '\t\tawait zoneuLifecycle?.bindPrompt({\n' +
      '\t\t\tsessionId: params.sessionId,\n' +
      '\t\t\tmessageId: message.id,\n' +
      '\t\t\trequestId: params.world_request_id\n' +
      '\t\t});\n' +
      '\t\tthis.assertLiveAgent(rec, params.sessionId);\n' +
      '\t\tif (params.world_request_id !== void 0 && this.ctx.get("zoneuWorldLifecycle") !== zoneuLifecycle) throw new Error("WORLD_LIFECYCLE_CHANGED");\n' +
      '\t\trec.handle.agent.followup(message);',
    ],
    [
      '\t\t\tcase "session/prompt": return this.prompt(params);',
      '\t\t\tcase "session/prompt": return this.prompt(params);\n' +
      '\t\t\tcase "zoneu/world-request": {\n' +
      '\t\t\t\tconst lifecycle = this.ctx.get("zoneuWorldLifecycle");\n' +
      '\t\t\t\tif (!lifecycle) throw new Error("WORLD_LIFECYCLE_UNAVAILABLE");\n' +
      '\t\t\t\treturn lifecycle.control(params);\n' +
      '\t\t\t}',
    ],
  ]
  // Count unpatched anchors only outside the complete replacement effect. A
  // marker/comment is not proof: every effect must occur once, or none may occur.
  const counts = replacements.map(([before, after]) => [
    source.split(after).join('').split(before).length - 1,
    source.split(after).length - 1,
  ])
  const remainder = replacements.reduce((text, [, after]) => text.split(after).join(''), source)
  const fragments = [
    '		const zoneuLifecycle = this.ctx.get("zoneuWorldLifecycle");',
    '		await zoneuLifecycle?.bindPrompt({',
    '		await this.ctx.get("zoneuWorldLifecycle")?.bindPrompt({',
    '			case "zoneu/world-request": {',
    '				return lifecycle.control(params);',
    '		if (params.world_request_id !== void 0 && this.ctx.get("zoneuWorldLifecycle") !== zoneuLifecycle)',
  ]
  if (fragments.some(fragment => remainder.includes(fragment))) throw new Error('WORLD_LIFECYCLE_PATCH_ANCHOR_MISMATCH')
  if (counts.every(([before, after]) => before === 0 && after === 1)) return source
  if (!counts.every(([before, after]) => before === 1 && after === 0)) throw new Error('WORLD_LIFECYCLE_PATCH_ANCHOR_MISMATCH')
  return replacements.reduce((text, [before, after]) => text.replace(before, after), source)
}
