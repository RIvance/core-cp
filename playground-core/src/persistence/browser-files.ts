export function downloadFile(name: string, content: string, mediaType = 'text/plain'): void {
  downloadBlob(name, new Blob([content], { type: `${mediaType};charset=utf-8` }));
}

/** Trigger one browser download and release the object URL after the browser has consumed the click. */
export function downloadBlob(name: string, content: Blob): void {
  const url = URL.createObjectURL(content);
  const link = document.createElement('a');
  link.href = url;
  link.download = name;
  document.body.append(link);
  link.click();
  link.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

export async function copyText(text: string): Promise<void> {
  if (!navigator.clipboard) throw new Error('Clipboard access is unavailable. Use HTTPS or download the file instead.');
  await navigator.clipboard.writeText(text);
}
