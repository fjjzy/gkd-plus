import { createDeviceAutomation } from '../lib/device-automation.ts';
import { createAdb } from '../lib/device-adb.ts';

export async function deviceRecovery(serial: string, operation: string, release = false) {
  if (!['status', 'restore'].includes(operation)) throw new Error('Expected status or restore');
  const adb = createAdb(serial);
  const { restoreAutomation, automationRecoveryStatus } = createDeviceAutomation(release ? 'li.songe.gkd' : 'li.songe.gkd.debug');
  return operation === 'restore' ? restoreAutomation(adb) : automationRecoveryStatus(adb);
}
