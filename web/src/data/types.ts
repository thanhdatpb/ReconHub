export type Role = 'ACCOUNTANT' | 'APPROVER' | 'ADMIN';

export interface User {
  id: string;
  name: string;
  role: Role;
  title: string;
}

export type SourceCode = 'GHN' | 'GHTK' | 'SHOPEE' | 'TIKTOK' | 'VCB' | 'MOMO' | 'VTP';
export type SourceKind = 'CARRIER' | 'MARKETPLACE' | 'BANK' | 'EWALLET';

export type CanonicalField =
  | 'externalRef'
  | 'orderCode'
  | 'trackingCode'
  | 'amount'
  | 'fee'
  | 'txnDate'
  | 'description';

export interface MappingRow {
  field: CanonicalField;
  column: string | null;
  confidence?: number;
  aiNote?: string;
}

export interface FeeRule {
  name: string;
  formula: string;
  effectiveFrom: string;
}

export interface Source {
  code: SourceCode;
  name: string;
  kind: SourceKind;
  status: 'ACTIVE' | 'DRAFT';
  channel: string;
  filePattern: string;
  schedule: string;
  expectedHour: number | null;
  encoding: string;
  dateFormat: string;
  headers: string[];
  sampleRows: string[][];
  mapping: MappingRow[];
  feeRules: FeeRule[];
}

export type FileStatus = 'PROCESSING' | 'COMPLETED' | 'COMPLETED_WITH_ERRORS' | 'FAILED' | 'DUPLICATE';

export interface ImportFile {
  id: string;
  source: SourceCode;
  fileName: string;
  receivedAt: Date;
  status: FileStatus;
  totalRows: number;
  errorRows: number;
  amountTotal: number;
  checksum: string;
  jobExecutionId: number | null;
  message?: string;
}

export interface FileErrorRow {
  row: number;
  column: string;
  value: string;
  reason: string;
}

export interface ReconDay {
  date: Date;
  source: SourceCode;
  totalLines: number;
  autoMatched: number;
  manualMatched: number;
  exceptions: number;
  amountIn: number;
  feeTotal: number;
}

export type MatchMethod = 'EXACT' | 'AMOUNT_DATE' | 'MANY_TO_ONE' | 'MANUAL' | 'AI_CONFIRMED';

export interface MatchGroup {
  id: string;
  method: MatchMethod;
  orderCodes: string[];
  paymentRef: string;
  amount: number;
  fee: number;
  confidence: number;
}

export interface Order {
  code: string;
  channel: 'WEBSITE' | 'SHOPEE' | 'TIKTOK';
  customer: string;
  phone: string;
  amount: number;
  codAmount: number;
  payVia: SourceCode;
  trackingCode?: string;
  createdAt: Date;
  deliveredAt?: Date;
}

export interface PaymentLine {
  id: string;
  source: SourceCode;
  externalRef: string;
  description: string;
  amount: number;
  fee: number;
  txnDate: Date;
  fileName: string;
}

export type ExceptionType = 'UNMATCHED_PAYMENT' | 'UNPAID_ORDER' | 'AMOUNT_DIFF' | 'FEE_DIFF' | 'DUPLICATE';
export type ExceptionStatus = 'OPEN' | 'IN_REVIEW' | 'WRITE_OFF_PENDING' | 'RESOLVED' | 'WRITTEN_OFF';

export type ActionKind =
  | 'CREATED'
  | 'AI_SUGGESTED'
  | 'ASSIGNED'
  | 'COMMENT'
  | 'MATCHED'
  | 'CLAIM_SENT'
  | 'WRITE_OFF_REQUESTED'
  | 'APPROVED'
  | 'REJECTED'
  | 'MARKED_DUPLICATE';

export interface ExceptionAction {
  at: Date;
  actor: string;
  kind: ActionKind;
  note?: string;
}

export interface AiReason {
  ok: boolean;
  text: string;
}

export interface AiCandidate {
  orderCodes: string[];
  amount: number;
  score: number;
  reasons: AiReason[];
}

export type AiAction = 'MATCH' | 'WRITE_OFF' | 'CLAIM' | 'MARK_DUPLICATE' | 'UPDATE_FEE_RULE';

export interface AiSuggestion {
  summary: string;
  confidence: number;
  recommended: AiAction;
  candidates: AiCandidate[];
  evidence: string[];
  latencyMs: number;
  tokens: number;
  engine?: 'llm' | 'rules';
}

export interface WriteOffRequest {
  requestedBy: string;
  requestedById: string;
  reason: string;
  note: string;
  amount: number;
}

export interface ReconException {
  id: string;
  type: ExceptionType;
  source: SourceCode;
  status: ExceptionStatus;
  diffAmount: number;
  createdAt: Date;
  slaDueAt: Date;
  assigneeId: string | null;
  orders: Order[];
  payments: PaymentLine[];
  meta: {
    expectedAmount?: number;
    paidAmount?: number;
    expectedFee?: number;
    actualFee?: number;
    feeRule?: string;
    daysSinceDelivery?: number;
  };
  ai: AiSuggestion | null;
  actions: ExceptionAction[];
  writeOff: WriteOffRequest | null;
  version: number;
}

export type JobName =
  | 'fileIngestionJob'
  | 'matchingJob'
  | 'feeValidationJob'
  | 'agingAndReportJob'
  | 'archiveAndPurgeJob'
  | 'seedOrdersJob';

export type BatchStatus = 'STARTED' | 'COMPLETED' | 'FAILED' | 'STOPPED';

export interface StepExecution {
  name: string;
  status: BatchStatus;
  read: number;
  write: number;
  skip: number;
  commits: number;
  durationMs: number;
  exitMessage?: string;
}

export interface JobExecution {
  id: number;
  job: JobName;
  params: Record<string, string>;
  status: BatchStatus;
  startTime: Date;
  endTime: Date | null;
  steps: StepExecution[];
  exitMessage?: string;
  /** restartedAs, supersededBy and acknowledged are only set on failed or stopped runs. */
  restartedAs?: number;
  /** A failed file's replacement: same source and business date, received later, loaded. */
  supersededBy?: { fileId: string; fileName: string; receivedAt: Date };
  /** "Đánh dấu đã xử lý": who (user id and name), when, and the required note. */
  acknowledged?: { by: string; byName: string; at: Date; note: string };
}

export interface BatchExplanation {
  cause: string;
  evidence: string[];
  steps: string[];
  /** 'llm' when the explanation came from the model, 'rules' for the built-in rules. */
  engine?: 'llm' | 'rules';
}

export interface AgingRow {
  source: SourceCode;
  buckets: [number, number, number, number];
}

export interface JournalEntry {
  date: Date;
  voucher: string;
  description: string;
  debit: string;
  credit: string;
  amount: number;
  source: SourceCode;
}
