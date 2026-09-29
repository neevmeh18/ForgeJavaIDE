import type { ForgeError } from "./ForgeError";
export interface Result<T> {
  ok: boolean;
  value: T | null;
  error: ForgeError | null;
  executionId: string | null;
  pending: boolean;
}
