import type { ErrorCode } from "./ErrorCode";
export interface ForgeError {
  code: ErrorCode;
  message: string;
  details: Record<string, string>;
}
