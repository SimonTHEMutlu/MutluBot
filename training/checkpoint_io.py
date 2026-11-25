"""Atomic checkpoint replacement with bounded retries for transient Windows locks."""
import time


def replace_with_retry(temporary, destination, attempts=20):
    if attempts < 1:
        raise ValueError("attempts must be positive")
    for attempt in range(attempts):
        try:
            temporary.replace(destination)
            return
        except PermissionError:
            if attempt+1 == attempts:
                raise
            time.sleep(min(0.05 * 2**attempt, 0.5))
