import { spawn } from 'node:child_process';
import { cp, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('../', import.meta.url));
const temporary = await mkdtemp(join(tmpdir(), 'language-playground-package-'));
const consumer = join(temporary, 'consumer');

function run(args, cwd, capture = false) {
  return new Promise((resolve, reject) => {
    const child = spawn('npm', args, { cwd, stdio: ['ignore', capture ? 'pipe' : 'inherit', 'inherit'] });
    let output = '';
    child.stdout?.setEncoding('utf8').on('data', (chunk) => (output += chunk));
    child.on('error', reject);
    child.on('exit', (code, signal) => {
      if (code === 0) resolve(output);
      else reject(new Error(`npm ${args.join(' ')} failed (${signal ?? code}).`));
    });
  });
}

try {
  // Install the archive outside the workspace so source aliases, hoisted dependencies, and compiler files cannot help.
  const [archive] = JSON.parse(
    await run(['pack', '--ignore-scripts', '--json', '--pack-destination', temporary], root, true),
  );
  const manifest = JSON.parse(await readFile(join(root, 'package.json'), 'utf8'));
  await cp(join(root, 'tests/package-consumer'), consumer, { recursive: true });
  const fixture = JSON.parse(await readFile(join(consumer, 'package.json'), 'utf8'));
  fixture.dependencies = { ...manifest.peerDependencies, [manifest.name]: `file:${join(temporary, archive.filename)}` };
  fixture.devDependencies = Object.fromEntries(
    [
      'vite',
      'typescript',
      '@vitejs/plugin-react',
      '@playwright/test',
      '@types/node',
      'eslint',
      '@eslint/js',
      'typescript-eslint',
      'globals',
    ].map((name) => [name, manifest.devDependencies[name]]),
  );
  await writeFile(join(consumer, 'package.json'), `${JSON.stringify(fixture, null, 2)}\n`);
  await run(['install', '--ignore-scripts', '--no-audit', '--no-fund', '--no-package-lock'], consumer);
  await run(['run', 'build'], consumer);
  await run(['run', 'lint'], consumer);
  await run(['test'], consumer);
} finally {
  await rm(temporary, { recursive: true, force: true });
}
