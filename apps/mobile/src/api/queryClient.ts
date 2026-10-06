import { QueryClient } from '@tanstack/react-query';
import { ApiRequestError } from './client';

/**
 * The single query cache. Lives in its own module so the session store can
 * clear it whenever the signed-in identity changes — otherwise the next person
 * to sign in on a shared phone would briefly see the previous user's data.
 */
export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      // Retry only what retrying can fix: network drops, timeouts and 5xx.
      // A 4xx is a definite answer — retrying a 401 or a 404 just wastes the
      // user's data and delays the error screen.
      retry: (failureCount, error) => {
        const status = error instanceof ApiRequestError ? error.status : 0;
        const transient = status === 0 || status >= 500;
        return transient && failureCount < 3;
      },
      retryDelay: (attempt) => Math.min(1000 * 2 ** attempt, 8000),
      refetchOnWindowFocus: false,
      staleTime: 30_000,
    },
  },
});
