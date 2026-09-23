import { messageForCode } from './errorMessages';
import './components.css';

interface ErrorBannerProps {
  code: string;
}

/**
 * Deliberately takes only the `code`: the server's `detail` is untrusted,
 * re-wordable text, so the banner's words always come from the local table.
 */
export function ErrorBanner({ code }: ErrorBannerProps) {
  return (
    <div className="error-banner" role="alert">
      {messageForCode(code)}
    </div>
  );
}
