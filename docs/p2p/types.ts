/**
 * 跨網 P2P 群聊：核心型別定義（任務 1）
 * =====================================================================
 *
 * 架構總覽
 * ---------------------------------------------------------------------
 *  ┌──────────────┐  信令（Offer/Answer/Candidate/SyncVector）  ┌──────────────┐
 *  │   Peer A     │ ───────── 由「已連上的 Peer」轉送 ─────────▶ │   Peer B     │
 *  │ P2PManager   │ ◀═══════ RTCDataChannel（加密、可靠）═══════▶ │ P2PManager   │
 *  │ CRDTEngine   │                                              │ CRDTEngine   │
 *  └──────────────┘                                              └──────────────┘
 *
 *  三層：
 *   1. 傳輸層 P2PManager：WebRTC DataChannel，Full Mesh；ICE 用公用 STUN 打洞
 *   2. 信令層 Signaling：沒有信令伺服器，改用
 *        (a) 第一次：邀請 QR Code／連結（內含 Offer SDP，壓縮後 base64）手動交換
 *        (b) 之後：已在群內的 Peer 互相轉送（Gossip Relay），新成員只要連上任一人即可
 *   3. 資料層 CRDTEngine：訊息為只增集合（Grow-only），以向量時鐘（SyncVector）
 *      比對差異，上線時互補離線期間的訊息；E2E 加密在 CRDT 之前完成
 *
 *  「完全無伺服器」的現實限制（必讀）：
 *   - STUN 只用來查詢自己的公網位址，可用公用 STUN（例如 stun.l.google.com），
 *     不經手訊息內容，但仍是第三方節點；不用 STUN 則只能區網互連
 *   - 雙方都在對稱型 NAT（部分行動網路 CGNAT）時打洞會失敗，必須 TURN 中繼。
 *     本設計允許「群內任一 Peer 自願當中繼」（Peer Relay）作為替代，而非架設伺服器
 *   - 第一次連線一定要某種帶外（out-of-band）交換：QR Code、通訊軟體傳邀請連結
 *
 *  型別安全約束：
 *   - 以品牌型別（Branded Type）區分各種字串 ID，避免 PeerId 與 MessageId 混用
 *   - 所有網路封包都是可辨識聯合（Discriminated Union），以 `kind` 收斂型別
 *   - 外部輸入一律先經過型別守衛（type guard）驗證，未知封包直接丟棄
 *   - 欄位一律 readonly；CRDT 只增不改，修改＝新增一筆操作
 */

// =====================================================================
// 0. 共用基礎型別
// =====================================================================

/** 品牌型別：讓不同語意的字串在編譯期不能互相指派 */
declare const __brand: unique symbol;
export type Brand<T, B extends string> = T & { readonly [__brand]: B };

/** 節點 ID：公鑰指紋（SHA-256 前 16 bytes 的 base32），可驗證、不可偽造 */
export type PeerId = Brand<string, 'PeerId'>;
/** 群組 ID：群組金鑰的指紋 */
export type GroupId = Brand<string, 'GroupId'>;
/** 訊息 ID：`${PeerId}:${Lamport}`，全網唯一且可排序 */
export type MessageId = Brand<string, 'MessageId'>;
/** 檔案 ID：檔案內容 SHA-256（內容定址，重複檔案自動去重） */
export type FileId = Brand<string, 'FileId'>;

/** Unix 毫秒時間（僅供顯示；排序以 Lamport 為準，避免各機時鐘不同步） */
export type EpochMs = Brand<number, 'EpochMs'>;
/** Lamport 邏輯時鐘：每次本地事件 +1，收到訊息取 max(本地, 對方) + 1 */
export type Lamport = Brand<number, 'Lamport'>;

/** base64url 字串（金鑰、簽章、密文） */
export type B64 = Brand<string, 'B64'>;

/** 協定版本：不相容變更時 +1，握手時不一致直接拒絕 */
export const PROTOCOL_VERSION = 1 as const;
export type ProtocolVersion = typeof PROTOCOL_VERSION;

// =====================================================================
// 1. Peer 節點資訊
// =====================================================================

/** ICE 候選位址類型（RFC 8445） */
export type IceCandidateType = 'host' | 'srflx' | 'prflx' | 'relay';

/** 單一 ICE Candidate（與 RTCIceCandidateInit 相容，額外保留解析後欄位以利診斷） */
export interface IceCandidate {
  readonly candidate: string;            // 原始 SDP a=candidate 行
  readonly sdpMid: string | null;
  readonly sdpMLineIndex: number | null;
  readonly usernameFragment?: string | null;
  /** 以下由 candidate 字串解析，僅供 UI 與除錯 */
  readonly type?: IceCandidateType;
  readonly protocol?: 'udp' | 'tcp';
  readonly ip?: string;                  // IPv4／IPv6；mDNS 候選為 *.local
  readonly port?: number;
}

/** SDP 描述（Offer／Answer） */
export interface SessionDescription {
  readonly type: 'offer' | 'answer';
  readonly sdp: string;
}

/**
 * 連線狀態機
 *   idle → signaling → connecting → connected
 *                     ↘ failed        ↘ reconnecting（ICE Restart）→ connected
 *   任何狀態 → closed（主動離開或被移出群組）
 */
export type PeerConnectionState =
  | { readonly kind: 'idle' }
  | { readonly kind: 'signaling'; readonly role: 'offerer' | 'answerer'; readonly since: EpochMs }
  | { readonly kind: 'connecting'; readonly since: EpochMs }
  | { readonly kind: 'connected'; readonly since: EpochMs; readonly path: IceCandidateType; readonly rttMs?: number }
  | { readonly kind: 'reconnecting'; readonly attempt: number; readonly reason: ReconnectReason }
  | { readonly kind: 'failed'; readonly error: P2PErrorCode; readonly at: EpochMs }
  | { readonly kind: 'closed'; readonly at: EpochMs };

export type ReconnectReason = 'network-change' | 'ice-disconnected' | 'ice-failed' | 'datachannel-closed';

/** 可公開分享的節點身分（放在邀請碼、Hello 封包） */
export interface PeerIdentity {
  readonly peerId: PeerId;
  /** Ed25519 簽章公鑰（驗證每則訊息的作者） */
  readonly signPublicKey: B64;
  /** X25519 金鑰交換公鑰（建立點對點加密通道、分發群組金鑰） */
  readonly dhPublicKey: B64;
  readonly displayName: string;           // 使用者自訂名稱（≤ 20 字）
  readonly userCode: string;              // 使用者代號（英數 4–12 碼，群內唯一）
}

/** 執行期節點資訊（只存在本機記憶體，不上網路） */
export interface PeerInfo {
  readonly identity: PeerIdentity;
  readonly state: PeerConnectionState;
  /** 最近一次成功連線的位址（下次重連優先嘗試） */
  readonly lastKnown?: {
    readonly ip: string;
    readonly port: number;
    readonly candidateType: IceCandidateType;
    readonly at: EpochMs;
  };
  /** 本機已產生、尚未送出的 Candidate（Trickle ICE 佇列） */
  readonly pendingLocalCandidates: readonly IceCandidate[];
  /** 對方已送來、但 remoteDescription 尚未設定時暫存的 Candidate */
  readonly pendingRemoteCandidates: readonly IceCandidate[];
  /** 對方最後已知的同步向量（決定要補送哪些訊息） */
  readonly syncVector: SyncVector;
  /** 是否願意替無法直連的成員中繼（Peer Relay，取代 TURN 伺服器） */
  readonly canRelay: boolean;
}

// =====================================================================
// 2. P2P 信令封包
// =====================================================================

/**
 * 信令外層：所有信令都帶來源、目的與簽章；
 * 由群內 Peer 轉送時（Gossip Relay）中繼者看得到外層但無法竄改（簽章驗證），
 * SDP 內容另以接收者的 X25519 公鑰加密，中繼者看不到 IP 與指紋。
 */
export interface SignalEnvelope<P extends SignalPayload = SignalPayload> {
  readonly v: ProtocolVersion;
  readonly id: Brand<string, 'SignalId'>;   // 去重用（隨機 128 bit）
  readonly groupId: GroupId;
  readonly from: PeerId;
  readonly to: PeerId | '*';                 // '*' = 群內廣播（如 SyncVector）
  readonly ttl: number;                      // 轉送剩餘跳數（預設 4，每轉一次 -1，0 丟棄）
  readonly sentAt: EpochMs;
  readonly payload: P;
  readonly sig: B64;                         // Ed25519(from 私鑰, canonical(本物件去除 sig))
}

/** 信令內容：以 kind 區分的可辨識聯合 */
export type SignalPayload =
  | OfferSignal
  | AnswerSignal
  | CandidateSignal
  | IceRestartSignal
  | SyncVectorSignal
  | HelloSignal
  | ByeSignal;

/** 發起連線：SDP 以接收者 dhPublicKey 加密 */
export interface OfferSignal {
  readonly kind: 'offer';
  readonly sessionId: Brand<string, 'SessionId'>; // 同一對 Peer 的協商回合
  readonly encryptedSdp: B64;                       // Encrypt(SessionDescription)
  readonly iceRestart: boolean;
}

export interface AnswerSignal {
  readonly kind: 'answer';
  readonly sessionId: Brand<string, 'SessionId'>;
  readonly encryptedSdp: B64;
}

/** Trickle ICE：一次可批次送多個候選，減少中繼次數 */
export interface CandidateSignal {
  readonly kind: 'candidate';
  readonly sessionId: Brand<string, 'SessionId'>;
  readonly encryptedCandidates: B64;  // Encrypt(IceCandidate[])
  readonly endOfCandidates: boolean;
}

/** 網路切換（Wi-Fi ↔ 行動網路）時要求對方配合重做 ICE */
export interface IceRestartSignal {
  readonly kind: 'ice-restart';
  readonly sessionId: Brand<string, 'SessionId'>;
  readonly reason: ReconnectReason;
}

/** 同步向量公告：上線、重連、定期（每 30 秒）廣播，讓對方知道要補什麼 */
export interface SyncVectorSignal {
  readonly kind: 'sync-vector';
  readonly vector: SyncVector;
  /** 文件頭雜湊（CRDT 引擎的 heads），向量相同但雜湊不同代表需要完整比對 */
  readonly headsHash: B64;
}

/** 加入／重新上線打招呼，附上身分與能力 */
export interface HelloSignal {
  readonly kind: 'hello';
  readonly identity: PeerIdentity;
  readonly capabilities: readonly Capability[];
}

export type Capability = 'relay' | 'file-transfer' | 'crdt-automerge-v2' | 'crdt-yjs-v13';

export interface ByeSignal {
  readonly kind: 'bye';
  readonly reason: 'leave' | 'kicked' | 'shutdown';
}

/**
 * 首次連線用的邀請碼（QR Code／分享連結的內容）
 * 序列化後 deflate + base64url，約 600–1200 字元，QR Code 版本 25 左右可容納
 */
export interface InvitePacket {
  readonly v: ProtocolVersion;
  readonly groupId: GroupId;
  readonly groupName: string;
  /** 群組對稱金鑰，以「邀請密碼」衍生的金鑰加密（PBKDF2/Argon2id），避免 QR 外流即入群 */
  readonly wrappedGroupKey: B64;
  readonly inviter: PeerIdentity;
  /** 邀請者預先產生的 Offer（被邀請者掃描後直接回 Answer） */
  readonly offer: SessionDescription;
  readonly iceServers: readonly IceServerConfig[];
  readonly expiresAt: EpochMs;                    // 預設 10 分鐘，過期 SDP 失效
}

export interface IceServerConfig {
  readonly urls: string | readonly string[];      // 'stun:stun.l.google.com:19302'
  readonly username?: string;
  readonly credential?: string;
}

// =====================================================================
// 3. 加密訊息與 CRDT 聊天紀錄模型
// =====================================================================

/**
 * 同步向量（版本向量）：每個作者已收到的最大 Lamport
 * 比對規則：對方 vector[p] < 我方 vector[p] → 補送 p 在 (對方值, 我方值] 的訊息
 */
export type SyncVector = Readonly<Record<PeerId, Lamport>>;

/** 明文內容（加密前），以 kind 區分 */
export type MessageBody =
  | { readonly kind: 'text'; readonly text: string }                       // 含 emoji，UTF-8
  | { readonly kind: 'image'; readonly file: FileRef; readonly width: number; readonly height: number; readonly thumbB64?: B64 }
  | { readonly kind: 'file'; readonly file: FileRef }
  | { readonly kind: 'reaction'; readonly target: MessageId; readonly emoji: string }
  | { readonly kind: 'edit'; readonly target: MessageId; readonly text: string }   // 編輯＝新增操作
  | { readonly kind: 'delete'; readonly target: MessageId };                      // 刪除＝墓碑

/** 檔案參照：內容不進 CRDT，只存雜湊；檔案以分塊（chunk）在 DataChannel 傳輸 */
export interface FileRef {
  readonly fileId: FileId;            // SHA-256(明文)，下載完成後驗證
  readonly name: string;
  readonly mime: string;
  readonly size: number;              // bytes，上限由 UI 限制（例如 50 MB）
  readonly chunkSize: number;         // 預設 64 KiB（DataChannel 單訊息安全上限內）
  readonly chunkCount: number;
  /** 檔案對稱金鑰，與訊息一起被群組金鑰加密 */
  readonly fileKey: B64;
}

/**
 * 網路上傳輸與 CRDT 中保存的加密訊息
 * - 外層（作者、時鐘、群組）明文：CRDT 合併與同步向量需要
 * - 內容 body 以群組金鑰 AES-256-GCM（或 XChaCha20-Poly1305）加密
 * - 簽章涵蓋外層＋密文，防止中繼者竄改或冒名
 */
export interface EncryptedMessage {
  readonly v: ProtocolVersion;
  readonly id: MessageId;               // `${author}:${lamport}`
  readonly groupId: GroupId;
  readonly author: PeerId;
  readonly lamport: Lamport;
  readonly sentAt: EpochMs;
  readonly keyEpoch: number;            // 群組金鑰版本（成員異動時輪替）
  readonly nonce: B64;                  // 每則唯一（96 bit）
  readonly ciphertext: B64;             // Encrypt(groupKey[keyEpoch], canonical(MessageBody))
  readonly sig: B64;                    // Ed25519(author 私鑰, canonical(本物件去除 sig))
}

/** 解密並驗章後的訊息（只存在本機記憶體與 UI） */
export interface ChatMessage {
  readonly id: MessageId;
  readonly author: PeerId;
  readonly authorName: string;
  readonly lamport: Lamport;
  readonly sentAt: EpochMs;
  readonly body: MessageBody;
  readonly verified: true;              // 型別層級保證：未驗章的不會變成 ChatMessage
  /** 本地狀態：檔案下載進度、是否已讀 */
  readonly local: {
    readonly fileProgress?: number;     // 0–1
    readonly filePath?: string;
    readonly read: boolean;
  };
}

/**
 * CRDT 文件結構（Automerge／Yjs 皆可映射）
 * - messages：以 MessageId 為鍵的 Map（只增），天然冪等、交換律、結合律
 * - 排序：(lamport, author) 字典序，所有成員得到相同順序
 * - 成員與群組設定也放在同一份文件，用 LWW（最後寫入勝）暫存器
 */
export interface ChatDocument {
  readonly schema: 1;
  readonly groupId: GroupId;
  readonly messages: Readonly<Record<MessageId, EncryptedMessage>>;
  readonly members: Readonly<Record<PeerId, MemberRecord>>;
  readonly settings: LwwRegister<GroupSettings>;
}

export interface MemberRecord {
  readonly identity: PeerIdentity;
  readonly joinedAt: Lamport;
  readonly removed?: LwwRegister<boolean>;   // 移出群組（觸發金鑰輪替）
}

export interface GroupSettings {
  readonly name: string;
  readonly maxFileSizeMb: number;
  readonly relayAllowed: boolean;
}

/** 最後寫入勝暫存器：(lamport, author) 較大者勝，作者 ID 打破平手 */
export interface LwwRegister<T> {
  readonly value: T;
  readonly lamport: Lamport;
  readonly author: PeerId;
}

/** CRDT 增量（傳給其他 Peer 的變更），Automerge 為 change bytes、Yjs 為 update bytes */
export interface CrdtDelta {
  readonly kind: 'crdt-delta';
  readonly engine: 'automerge' | 'yjs';
  readonly bytes: B64;
  /** 本增量後的同步向量，接收方用來判斷是否還缺 */
  readonly vectorAfter: SyncVector;
}

// =====================================================================
// 4. DataChannel 上的應用層封包
// =====================================================================

/** DataChannel 只傳這幾種封包；信令走 SignalEnvelope（可經中繼） */
export type ChannelPacket =
  | { readonly kind: 'msg'; readonly message: EncryptedMessage }
  | CrdtDelta
  | { readonly kind: 'sync-request'; readonly since: SyncVector }
  | { readonly kind: 'file-chunk'; readonly fileId: FileId; readonly index: number; readonly data: B64 }
  | { readonly kind: 'file-request'; readonly fileId: FileId; readonly missing: readonly number[] }
  | { readonly kind: 'relay'; readonly envelope: SignalEnvelope }
  | { readonly kind: 'ping'; readonly t: EpochMs }
  | { readonly kind: 'pong'; readonly t: EpochMs };

// =====================================================================
// 5. 錯誤碼與型別守衛
// =====================================================================

export type P2PErrorCode =
  | 'ICE_FAILED'              // 打洞失敗（多半是雙方對稱 NAT）→ 嘗試 Peer Relay
  | 'SIGNAL_TIMEOUT'          // 信令逾時（邀請過期或中繼斷線）
  | 'BAD_SIGNATURE'           // 簽章不符，丟棄並記錄
  | 'DECRYPT_FAILED'          // 金鑰版本不符，要求重送金鑰
  | 'PROTOCOL_MISMATCH'
  | 'DATACHANNEL_CLOSED';

/** 結果型別：網路層函式一律回傳，不丟例外，強迫呼叫端處理錯誤 */
export type Result<T, E extends P2PErrorCode = P2PErrorCode> =
  | { readonly ok: true; readonly value: T }
  | { readonly ok: false; readonly error: E; readonly detail?: string };

/** 窮盡檢查：switch 漏處理新的 kind 時編譯失敗 */
export function assertNever(x: never): never {
  throw new Error('Unhandled variant: ' + JSON.stringify(x));
}

const isObj = (x: unknown): x is Record<string, unknown> => typeof x === 'object' && x !== null;
const isStr = (x: unknown): x is string => typeof x === 'string';
const isNum = (x: unknown): x is number => typeof x === 'number' && Number.isFinite(x);

/** 外部輸入的信令一律先過守衛；只做結構檢查，簽章由 Crypto 模組另外驗證 */
export function isSignalEnvelope(x: unknown): x is SignalEnvelope {
  if (!isObj(x)) return false;
  if (x.v !== PROTOCOL_VERSION) return false;
  if (!isStr(x.id) || !isStr(x.groupId) || !isStr(x.from) || !isStr(x.to) || !isStr(x.sig)) return false;
  if (!isNum(x.ttl) || x.ttl < 0 || x.ttl > 8 || !isNum(x.sentAt)) return false;
  const p = x.payload;
  if (!isObj(p) || !isStr(p.kind)) return false;
  switch (p.kind) {
    case 'offer': return isStr(p.sessionId) && isStr(p.encryptedSdp) && typeof p.iceRestart === 'boolean';
    case 'answer': return isStr(p.sessionId) && isStr(p.encryptedSdp);
    case 'candidate': return isStr(p.sessionId) && isStr(p.encryptedCandidates) && typeof p.endOfCandidates === 'boolean';
    case 'ice-restart': return isStr(p.sessionId) && isStr(p.reason);
    case 'sync-vector': return isObj(p.vector) && Object.values(p.vector).every(isNum) && isStr(p.headsHash);
    case 'hello': return isObj(p.identity) && Array.isArray(p.capabilities);
    case 'bye': return isStr(p.reason);
    default: return false;
  }
}

export function isEncryptedMessage(x: unknown): x is EncryptedMessage {
  return isObj(x) && x.v === PROTOCOL_VERSION && isStr(x.id) && isStr(x.groupId) && isStr(x.author)
    && isNum(x.lamport) && isNum(x.sentAt) && isNum(x.keyEpoch)
    && isStr(x.nonce) && isStr(x.ciphertext) && isStr(x.sig)
    && x.id === `${x.author}:${x.lamport}`;   // ID 必須與作者、時鐘一致，防止覆寫他人訊息
}

/** 同步向量比對：回傳對方缺少的 (作者, 起, 迄] 區間 */
export function diffVector(mine: SyncVector, theirs: SyncVector): ReadonlyArray<{ author: PeerId; after: Lamport; upTo: Lamport }> {
  const out: Array<{ author: PeerId; after: Lamport; upTo: Lamport }> = [];
  for (const [author, mineMax] of Object.entries(mine) as Array<[PeerId, Lamport]>) {
    const theirMax = (theirs[author] ?? 0) as Lamport;
    if (mineMax > theirMax) out.push({ author, after: theirMax, upTo: mineMax });
  }
  return out;
}

/** 全網一致的訊息排序：Lamport 優先，作者 ID 打破平手 */
export function compareMessages(a: Pick<EncryptedMessage, 'lamport' | 'author'>, b: Pick<EncryptedMessage, 'lamport' | 'author'>): number {
  return a.lamport !== b.lamport ? a.lamport - b.lamport : a.author < b.author ? -1 : a.author > b.author ? 1 : 0;
}
