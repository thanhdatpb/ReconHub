import type { JobExecution } from '../data/types';

export const failedOrStopped = (x: JobExecution) => x.status === 'FAILED' || x.status === 'STOPPED';

/** Same rule as JobView.needsAction on the server: failed or stopped, not restarted, not replaced, not acknowledged. */
export const needsAction = (x: JobExecution) => failedOrStopped(x) && !x.restartedAs && !x.supersededBy && !x.acknowledged;
