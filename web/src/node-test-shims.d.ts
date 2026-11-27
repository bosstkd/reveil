// Déclarations minimales des modules Node utilisés par les tests Vitest (lecture de public/reveil-sw.js),
// sans dépendre de @types/node.
declare module 'node:fs' {
  export function readFileSync(path: string, encoding: 'utf8'): string;
}
declare module 'node:path' {
  export function resolve(...segments: string[]): string;
}
declare const process: { cwd(): string };
