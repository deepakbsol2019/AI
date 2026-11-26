/** Email credential secrets are consumed in memory and removed before making requests. */
export function takeCredentialFragment(): { token: string; tenantCode: string } {
  const values = new URLSearchParams(location.hash.replace(/^#/, ''));
  const result = { token: values.get('token') ?? '', tenantCode: values.get('tenantCode') ?? '' };
  if (location.hash) history.replaceState(history.state, '', location.pathname + location.search);
  return result;
}
