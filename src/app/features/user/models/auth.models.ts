export interface AuthUser {
  readonly userId: number | string;
  readonly tenantId: number | string;
  readonly membershipId: number | string;
  readonly email: string;
  readonly displayName: string;
  readonly tenantName: string;
}

export interface AuthResponse {
  readonly accessToken: string;
  readonly tokenType: 'Bearer';
  readonly accessExpiresAt: string;
  readonly refreshExpiresAt: string;
  readonly absoluteExpiresAt: string | null;
  readonly serverTime: string;
  readonly sessionId: number | string;
  readonly user: AuthUser;
}

export interface AuthFeatures {
  readonly googleEnabled: boolean;
  readonly ssoEnabled: boolean;
}
export interface AuthMessage {
  readonly message: string;
}
export interface RegistrationRequest {
  readonly tenantType: 'INDIVIDUAL' | 'ORGANIZATION';
  readonly loginType: 'PASSWORD' | 'SSO';
  readonly email: string;
  readonly displayName: string;
  readonly tenantCode?: string;
  readonly organizationName?: string;
}

export type SessionEvent =
  | { readonly kind: 'session'; readonly response: AuthResponse; readonly clockOffset: number }
  | { readonly kind: 'clear'; readonly sessionId: number | string | null };
