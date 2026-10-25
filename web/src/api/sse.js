/**
 * SSE（Server-Sent Events）分帧解析器。
 *
 * 单独抽出来的原因：这段解析踩过坑，而且坑都很隐蔽 ——
 *   1) 一个事件可能由**多行 data** 组成（正文里的换行会被服务端拆开），
 *      必须按规范用 '\n' 重新拼接，少拼一次就丢一个换行；
 *   2) 规范要求剥掉字段值开头的**单个**空格，但只能剥一个，
 *      剥多了会把正文里真实的行首空格也吃掉。
 * 与网络、与 Vue 都无关，所以可以直接喂字节流做测试。
 *
 * 约定：后端保证每个事件的 data 都是 JSON 对象，所以解析失败不抛异常，
 * 而是降级成 { text: 原始内容 }，避免一个坏帧把整条流打断。
 */
export function createSseParser(onEvent) {
  let buf = ''

  const dispatch = (block) => {
    let name = 'message'
    const dataLines = []
    for (const line of block.split('\n')) {
      if (!line || line.startsWith(':')) continue // 空行与注释行（心跳）跳过
      const i = line.indexOf(':')
      const field = i >= 0 ? line.slice(0, i) : line
      let value = i >= 0 ? line.slice(i + 1) : ''
      if (value.startsWith(' ')) value = value.slice(1) // 规范：至多剥一个空格
      if (field === 'event') name = value
      else if (field === 'data') dataLines.push(value)
    }
    if (!dataLines.length) return
    const raw = dataLines.join('\n')
    let data
    try {
      data = JSON.parse(raw)
    } catch (e) {
      data = { text: raw }
    }
    onEvent?.({ event: name, data })
  }

  return {
    /** 送入一段已解码的文本（可以只是半截事件）。 */
    push(text) {
      if (!text) return
      buf += text
      buf = buf.replace(/\r\n/g, '\n') // 兼容服务端写 \r\n 的情况
      let idx
      while ((idx = buf.indexOf('\n\n')) >= 0) {
        dispatch(buf.slice(0, idx))
        buf = buf.slice(idx + 2)
      }
    },
    /** 流结束：处理可能残留的、没被空行终结的最后一个事件。 */
    end() {
      if (buf.trim()) dispatch(buf)
      buf = ''
    }
  }
}
