import assert from 'node:assert/strict';
import { test } from 'node:test';
import { mkdir, mkdtemp, readFile, rm } from 'node:fs/promises';
import { join } from 'node:path';
import { createDeviceAutomation, type Adb } from './device-automation.ts';
import { desktopTests } from './local-paths.ts';

for (const reopenApp of [true, false]) test(`Release failure restores saved fields with reopenApp=${reopenApp}`, async () => {
  await mkdir(desktopTests, { recursive: true });
  const directory = await mkdtemp(join(desktopTests, 'release-recovery-'));
  let store: Record<string, unknown> = { enableAutomator: true, automatorMode: 2, theme: 'light' };
  let pending: Record<string, unknown>;
  const starts: string[][] = [];
  const adb: Adb = async (...args) => {
    assert.ok(!args.join(' ').includes('li.songe.gkd.debug'), 'Release recovery must never access Debug');
    if (args[0] === 'get-serialno') return Buffer.from('release-device');
    if (args[0] === 'push') pending = JSON.parse(await readFile(args[1]!, 'utf8'));
    if (args[1] === 'mv') store = pending;
    if (args[1] === 'am' && args[2] === 'start') starts.push(args);
    return Buffer.from(args[1]?.startsWith('if [ -f') ? JSON.stringify(store) : '');
  };
  const release = createDeviceAutomation('li.songe.gkd');
  try {
    await assert.rejects(release.withAutomationPaused(adb, directory, async () => {
      assert.equal(store.enableAutomator, false);
      assert.equal(store.automatorMode, 1);
      assert.equal((await release.automationRecoveryStatus(adb, directory)).record?.packageName, 'li.songe.gkd');
      store.theme = 'dark';
      throw new Error('Install rejected');
    }, directory, { reopenApp }), /Install rejected/);
    assert.deepEqual(store, { enableAutomator: true, automatorMode: 2, theme: 'dark' });
    assert.equal((await release.automationRecoveryStatus(adb, directory)).record, null);
    assert.equal(starts.length, reopenApp ? 1 : 0, 'Silent restoration must not launch a UI');
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});
