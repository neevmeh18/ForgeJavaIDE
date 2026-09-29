export interface LoginResult {
  token: string;
  sessionId: string;
  user: { id: string; displayName: string; providerId: string };
  expiresAt: string;
}
