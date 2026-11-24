function safeFilename(filename: string): string {
  // Treat server values only as a suggested basename, never a local path.
  return (filename.split(/[\\/]/).pop() ?? '')
    .replace(/[\u0000-\u001F\u007F<>:"|?*]/g, '')
    .replace(/[. ]+$/g, '')
    .trim();
}

export function downloadFilename(disposition: string | null, fallback: string): string {
  let filename = '';
  const extended = disposition?.match(/(?:^|;)\s*filename\*\s*=\s*(?:"([^"]*)"|([^;]*))/i);
  if (extended) {
    const encoded = (extended[1] ?? extended[2] ?? '').trim();
    const parts = encoded.match(/^([^']*)'[^']*'(.*)$/);
    if (parts) {
      try {
        if (/^(?:utf-8|utf8)$/i.test(parts[1])) filename = decodeURIComponent(parts[2]);
        else if (/^(?:iso-8859-1|latin1)$/i.test(parts[1])) {
          filename = parts[2].replace(/%([\da-f]{2})/gi, (_, code: string) =>
            String.fromCharCode(Number.parseInt(code, 16)),
          );
        }
      } catch {
        filename = '';
      }
    }
  }
  if (!filename) {
    const ordinary = disposition?.match(
      /(?:^|;)\s*filename\s*=\s*(?:"((?:\\.|[^"\\])*)"|([^;]*))/i,
    );
    filename = ordinary
      ? (ordinary[1]?.replace(/\\(["\\])/g, '$1') ?? ordinary[2] ?? '').trim()
      : '';
  }
  return safeFilename(filename) || safeFilename(fallback) || 'download.pdf';
}
