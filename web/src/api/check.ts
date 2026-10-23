// Compile-time check, never imported by the app: the HTTP client must offer everything the mock does,
// with compatible types, so switching VITE_API_MODE cannot break a screen.
import * as http from './http';
import type * as mock from './mock';

type Api = Omit<typeof mock, 'MODE' | 'fileArrivals' | 'ApiError'>;
export const httpMatchesMock: Api = http;
