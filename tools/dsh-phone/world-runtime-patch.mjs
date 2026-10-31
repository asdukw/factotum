// Pure source transformation; assembly owns writes to the staged runtime.
export function patchWorldRuntime(source) {
  const replacements = [
    [
      '\t\t\t\tconst iterator = toStreamChunks(snapshot.models.streamSimple(model, context, {',
      '\t\t\t\tconst zoneuProjection = await this.config.projectRequest?.({ options, context, model, profile });\n' +
      '\t\t\t\tconst iterator = toStreamChunks(snapshot.models.streamSimple(model, zoneuProjection?.context ?? context, {',
    ],
    [
      '\t\t\t\t\theaders: requestHeaders(profile.headers)\n\t\t\t\t}), model.contextWindow',
      '\t\t\t\t\theaders: requestHeaders(profile.headers),\n' +
      '\t\t\t\t\t...zoneuProjection?.onPayload === void 0 ? {} : { onPayload: zoneuProjection.onPayload }\n' +
      '\t\t\t\t}), model.contextWindow',
    ],
    [
      '\tconst adapter = new PiAiAdapter({\n\t\tprofiles,\n\t\tresolveApiKey,',
      '\tconst adapter = new PiAiAdapter({\n\t\tprofiles,\n\t\tresolveApiKey,\n' +
      '\t\tprojectRequest: (request) => ctx.get("zoneuWorldRuntime")?.project(request),',
    ],
  ]
  // Validate every anchor before producing output. Mixed/partial patches fail closed.
  const counts = replacements.map(([before, after]) => [source.split(after).join('').split(before).length - 1, source.split(after).length - 1])
  if (counts.every(([before, after]) => before === 0 && after === 1)) return source
  if (!counts.every(([before, after]) => before === 1 && after === 0)) throw new Error('WORLD_RUNTIME_PATCH_ANCHOR_MISMATCH')
  return replacements.reduce((text, [before, after]) => text.replace(before, after), source)
}
