import { API_PREFIX, authToken } from './api';

export type RealtimeState = 'connecting' | 'open' | 'closed';

export interface RealtimeStreamOptions {
  /** 收到一条服务端事件：name 是 SSE 的 event 名，data 是 JSON.parse 之后的值。 */
  onEvent?: (name: string, data: unknown) => void;
  /** 连接状态变化，给 store 用来决定要不要退回轮询。 */
  onState?: (state: RealtimeState) => void;
}

/**
 * 消息推送长连接（D-6）。
 *
 * 为什么不用浏览器原生 EventSource：它不能带 Authorization 头，只能把 token 塞进 query，
 * 那样 token 会进访问日志、进浏览器历史、还可能被 Referer 带出去。这里用 fetch + ReadableStream
 * 读同一条 SSE 流，token 照常走请求头，服务端的鉴权链路不用为实时化开特例。
 *
 * 断线策略：指数退避重连（1s → 2s → … → 30s 封顶）。返回的 stop() 是幂等的，
 * 退出登录 / 组件卸载都要调，否则会有个后台连接一直拿着旧 token。
 */
export function openRealtimeStream(options: RealtimeStreamOptions = {}): () => void {
  const controller = new AbortController();
  let closed = false;
  let retryDelay = 1000;
  let retryTimer: number | undefined;

  const emitState = (state: RealtimeState) => {
    try {
      options.onState?.(state);
    } catch {
      // 回调自己抛异常不能带崩连接循环
    }
  };

  const scheduleReconnect = () => {
    if (closed) return;
    emitState('closed');
    retryTimer = window.setTimeout(() => { void connect(); }, retryDelay);
    retryDelay = Math.min(retryDelay * 2, 30000);
  };

  const dispatch = (block: string) => {
    let event = 'message';
    const dataLines: string[] = [];
    for (const line of block.split('\n')) {
      if (!line || line.startsWith(':')) continue;
      const colon = line.indexOf(':');
      const field = colon === -1 ? line : line.slice(0, colon);
      const value = colon === -1 ? '' : line.slice(colon + 1).replace(/^ /, '');
      if (field === 'event') event = value;
      else if (field === 'data') dataLines.push(value);
    }
    if (!dataLines.length) return;
    let payload: unknown = dataLines.join('\n');
    try {
      payload = JSON.parse(dataLines.join('\n'));
    } catch {
      // 非 JSON 的 data（心跳之类）原样透传
    }
    try {
      options.onEvent?.(event, payload);
    } catch {
      // 同上：消费方异常不能断流
    }
  };

  const consume = async (body: ReadableStream<Uint8Array>) => {
    const reader = body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';
    for (;;) {
      // 流式读取天生是串行的：必须等上一块处理完才能读下一块，这里不是「循环里忘并发」
      // eslint-disable-next-line no-await-in-loop
      const { done, value } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      // SSE 用空行分隔事件；\r\n\r\n 也认，nginx 可能改写换行
      buffer = buffer.replace(/\r\n/g, '\n');
      let boundary = buffer.indexOf('\n\n');
      while (boundary !== -1) {
        const block = buffer.slice(0, boundary);
        buffer = buffer.slice(boundary + 2);
        if (block.trim()) dispatch(block);
        boundary = buffer.indexOf('\n\n');
      }
    }
  };

  async function connect() {
    if (closed) return;
    const token = authToken();
    if (!token) {
      // 还没登录：不重连，等 store 再叫一次
      emitState('closed');
      return;
    }
    emitState('connecting');
    try {
      const response = await fetch(`${API_PREFIX}/realtime/stream`, {
        headers: { Authorization: `Bearer ${token}`, Accept: 'text/event-stream' },
        signal: controller.signal,
      });
      if (!response.ok || !response.body) {
        throw new Error(`realtime stream ${response.status}`);
      }
      retryDelay = 1000;
      emitState('open');
      await consume(response.body);
      scheduleReconnect();
    } catch {
      if (closed || controller.signal.aborted) return;
      scheduleReconnect();
    }
  }

  void connect();

  return () => {
    if (closed) return;
    closed = true;
    window.clearTimeout(retryTimer);
    controller.abort();
    emitState('closed');
  };
}
