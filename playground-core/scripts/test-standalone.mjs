import { spawn } from 'node:child_process';
import { cp, lstat, mkdir, mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const source = fileURLToPath(new URL('../', import.meta.url));
const temporary = await mkdtemp(join(tmpdir(), 'playground-standalone-'));

function run(command, args, cwd, capture = false) {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, { cwd, stdio: ['ignore', capture ? 'pipe' : 'inherit', 'inherit'] });
    let output = '';
    child.stdout?.setEncoding('utf8').on('data', (chunk) => (output += chunk));
    child.on('error', reject);
    child.on('exit', (code, signal) => {
      if (code === 0) resolve(output);
      else reject(new Error(`${command} ${args.join(' ')} failed (${signal ?? code}).`));
    });
  });
}

try {
  // Export only this project's tracked files, with no parent repository, dependencies, or build outputs.
  const files = (await run('git', ['ls-files', '-z', '--', '.'], source, true)).split('\0').filter(Boolean);
  if (!files.includes('package.json')) throw new Error('Run the standalone check from a tracked source checkout.');
  for (const path of files) {
    const input = join(source, path);
    if ((await lstat(input)).isSymbolicLink())
      throw new Error(`The standalone check requires ordinary source files; ${path} is a symbolic link.`);
    const output = join(temporary, path);
    await mkdir(dirname(output), { recursive: true });
    await cp(input, output);
  }
  await run('git', ['init', '--quiet'], temporary);
  await run('git', ['add', '.'], temporary);
  await run('npm', ['ci'], temporary);
  for (const script of ['build', 'test', 'lint', 'format:check', 'test:e2e', 'test:package'])
    await run('npm', ['run', script], temporary);
  const changes = await run('git', ['diff', '--name-only'], temporary, true);
  const untracked = await run('git', ['ls-files', '--others', '--exclude-standard'], temporary, true);
  if (changes || untracked)
    throw new Error(`Verification changed source or left unignored output:\n${changes}${untracked}`);
  console.log('The complete playground checkout builds and passes verification in its own Git repository.');
} finally {
  await rm(temporary, { recursive: true, force: true });
}
