"""Bound transport waits without waiting indefinitely for cancellation."""

from __future__ import annotations

import asyncio
from collections.abc import Awaitable
from typing import TypeVar

T = TypeVar("T")
CLEANUP_TIMEOUT_SECONDS = 5.0


def _retrieve_exception(task: asyncio.Future[object]) -> None:
    if not task.cancelled():
        task.exception()


async def bounded_await(
    awaitable: Awaitable[T], timeout: float = CLEANUP_TIMEOUT_SECONDS
) -> T:
    """Cancel on deadline, but do not wait for a hung backend's cancellation.

    Unlike wait_for/asyncio.timeout, this also bounds a coroutine whose finally
    block stalls after cancellation. Callers must retire that transport after
    a timeout; never reuse an iterator with an outstanding anext().
    """

    task = asyncio.ensure_future(awaitable)
    try:
        done, _ = await asyncio.wait({task}, timeout=timeout)
        if not done:
            raise TimeoutError("Transport cleanup/health deadline exceeded")
        return task.result()
    finally:
        if not task.done():
            task.cancel()
            task.add_done_callback(_retrieve_exception)
