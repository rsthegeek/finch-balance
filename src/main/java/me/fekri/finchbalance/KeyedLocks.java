package me.fekri.finchbalance;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/** Reference-counted locks whose slots cover both owners and waiting acquirers. */
class KeyedLocks {
  private final ConcurrentHashMap<String, Slot> slots = new ConcurrentHashMap<>();

  Lease acquire(String key) {
    Objects.requireNonNull(key);
    Slot slot =
        slots.compute(
            key,
            (ignored, existing) -> {
              Slot retained = existing == null ? new Slot() : existing;
              retained.references++;
              return retained;
            });
    // Waiting here, outside compute, lets other keys and releases make progress.
    slot.lock.lock();
    return new Lease(key, slot);
  }

  int slotCount() {
    return slots.size();
  }

  private static final class Slot {
    private final ReentrantLock lock = new ReentrantLock();
    private int references;
  }

  final class Lease implements AutoCloseable {
    private final String key;
    private final Slot slot;
    private final Thread owner = Thread.currentThread();
    private boolean closed;

    private Lease(String key, Slot slot) {
      this.key = key;
      this.slot = slot;
    }

    @Override
    public void close() {
      if (Thread.currentThread() != owner || closed) {
        throw new IllegalStateException("A lease must be closed exactly once by its owning thread");
      }
      slot.lock.unlock();
      closed = true;
      slots.compute(
          key,
          (ignored, current) -> {
            if (current != slot || slot.references <= 0) {
              throw new IllegalStateException("Corrupt lock-slot lifecycle");
            }
            return --slot.references == 0 ? null : slot;
          });
    }
  }
}
