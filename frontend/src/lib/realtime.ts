import { API_PREFIX } from './api';

export type RealtimeState = 'connecting' | 'open' | 'closed';

export interface RealtimeStreamOptions {
  /** 收到一条服务端事件：name 是业务事件名（如 message），data 是 payload。 */
  onEvent?: (name: string, data: unknown) => void;
  /** 连接状态变化，给 store 用来决定要不要退回轮询。 */
  onState?: (state: RealtimeState) => void;
}

interface ServerFrame {
  type: string;
  event?: string;
  seq?: number;
  ts?: number;
  payload?: unknown;
  code?: string;
  message?: string;
}

/**
 * 实时通道（ADR-0001 D4：WebSocket，不再用 SSE）。
 *
 * 鉴权两条路：
 * - 浏览器：握手自动带 httpOnly 会话 Cookie，服务端直接认出来；
 * - 当前 Web 端还在用 Bearer（localStorage），所以连上后发一条 auth 帧。
 *   等前端整体切到 Cookie 后，这条 auth 帧可以留着（无害），也可以删掉。
 *
 * 断线策略：指数退避（1s → 30s 封顶）。重连后带 lastEventId 续传，
 * 服务端从 Redis Stream / 内存环形缓存补最近 100 条。
 *
 * 心跳：25 秒发一次 JSON ping（浏览器 WebSocket API 不暴露协议层 pong），
 * 60 秒收不到 pong 就主动断开重连 —— 中间代理掐掉长连接时客户端不会毫无察觉。
 */
export function openRealtimeStream(options: RealtimeStreamOptions = {}): () => void {
  let socket: WebSocket | undefined;
  let closed = false;
  let retryDelay = 1000;
  let retryTimer: number | undefined;
  let heartbeatTimer: number | undefined;
  let lastSeq = 0;
  let lastPongAt = Date.now();

  const emitState = (state: RealtimeState) => {
    try {
      options.onState?.(state);
    } catch {
      // 回调异常不能带崩连接循环
    }
  };

  const url = () => {
    const scheme = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    return `${scheme}//${window.location.host}${API_PREFIX}/realtime/ws`;
  };

  const send = (frame: Record<string, unknown>) => {
    if (socket?.readyState === WebSocket.OPEN) {
      socket.send(JSON.stringify(frame));
    }
  };

  const scheduleReconnect = () => {
    if (closed) return;
    emitState('closed');
    window.clearInterval(heartbeatTimer);
    retryTimer = window.setTimeout(() => { void connect(); }, retryDelay);
    retryDelay = Math.min(retryDelay * 2, 30000);
  };

  const handleFrame = (raw: string) => {
    let frame: ServerFrame;
    try {
      frame = JSON.parse(raw) as ServerFrame;
    } catch {
      return;
    }
    switch (frame.type) {
      case 'ready':
        retryDelay = 1000;
        lastPongAt = Date.now();
        emitState('open');
        break;
      case 'event': {
        if (typeof frame.seq === 'number' && frame.seq > lastSeq) {
          lastSeq = frame.seq;
        }
        try {
          options.onEvent?.(frame.event ?? 'message', frame.payload);
        } catch {
          // 消费方异常不能断流
        }
        break;
      }
      case 'pong':
        lastPongAt = Date.now();
        break;
      case 'auth-required':
        // 浏览器走 httpOnly Cookie，握手阶段就认出来了；走到这里说明没登录，
        // 不重试、等 auth store 触发重连即可。
        break;
      case 'error':
        if (frame.code === 'AUTH_UNAUTHENTICATED') {
          socket?.close();
        }
        break;
      default:
        break;
    }
  };

  function connect() {
    if (closed) return;
    emitState('connecting');
    try {
      socket = new WebSocket(url());
    } catch {
      scheduleReconnect();
      return;
    }

    socket.onopen = () => {
      // 不在这里发 auth：浏览器握手会自动带 httpOnly 会话 Cookie。
      // App 端将来接 WS 时，才需要首帧 auth 帧带 Bearer。
      if (lastSeq > 0) send({ type: 'resume', lastEventId: lastSeq });
      lastPongAt = Date.now();
      heartbeatTimer = window.setInterval(() => {
        send({ type: 'ping' });
        if (Date.now() - lastPongAt > 60000) {
          // 半开连接：浏览器以为还连着，实际早断了。主动关掉走重连。
          socket?.close();
        }
      }, 25000);
    };

    socket.onmessage = (event) => {
      if (typeof event.data === 'string') handleFrame(event.data);
    };

    socket.onclose = () => {
      window.clearInterval(heartbeatTimer);
      if (!closed) scheduleReconnect();
    };

    socket.onerror = () => {
      socket?.close();
    };
  }

  void connect();

  return () => {
    if (closed) return;
    closed = true;
    window.clearTimeout(retryTimer);
    window.clearInterval(heartbeatTimer);
    socket?.close();
    emitState('closed');
  };
}
