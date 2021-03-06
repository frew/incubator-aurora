/**
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.aurora.scheduler.storage.mem;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.concurrent.atomic.AtomicLong;

import javax.inject.Inject;
import javax.inject.Qualifier;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Supplier;
import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Key;
import com.twitter.common.inject.Bindings;
import com.twitter.common.inject.TimedInterceptor.Timed;
import com.twitter.common.stats.SlidingStats;
import com.twitter.common.stats.Stats;
import com.twitter.common.stats.StatsProvider;

import org.apache.aurora.scheduler.storage.AttributeStore;
import org.apache.aurora.scheduler.storage.JobStore;
import org.apache.aurora.scheduler.storage.JobUpdateStore;
import org.apache.aurora.scheduler.storage.LockStore;
import org.apache.aurora.scheduler.storage.QuotaStore;
import org.apache.aurora.scheduler.storage.ReadWriteLockManager;
import org.apache.aurora.scheduler.storage.ReadWriteLockManager.LockType;
import org.apache.aurora.scheduler.storage.SchedulerStore;
import org.apache.aurora.scheduler.storage.Storage;
import org.apache.aurora.scheduler.storage.TaskStore;
import org.apache.aurora.scheduler.storage.db.DbModule;

import static java.util.Objects.requireNonNull;

/**
 * A storage implementation comprised of individual in-memory store implementations.
 * <p>
 * This storage has a global read-write lock, which is used when invoking
 * {@link #consistentRead(Work)} and {@link #write(MutateWork)}.  However, no locks are used at this
 * level for {@link #weaklyConsistentRead(Work)}. It is the responsibility of the
 * individual stores to ensure that read operations are thread-safe (optimally supporting
 * concurrency).  Store implementations may assume that all methods invoked on {@code Mutable}
 * store interfaces are protected by the global write lock, and thus invoked serially.
 */
public class MemStorage implements Storage {
  private final AtomicLong readLockWaitNanos = Stats.exportLong("read_lock_wait_nanos");
  private final AtomicLong writeLockWaitNanos = Stats.exportLong("write_lock_wait_nanos");

  // We choose to not use the @Timed decorator for these stats since nested transactions are normal
  // and pollute the stats.
  private final SlidingStats readStats =
      new SlidingStats("mem_storage_consistent_read_operation", "nanos");
  private final SlidingStats writeStats =
      new SlidingStats("mem_storage_write_operation", "nanos");

  private final MutableStoreProvider storeProvider;
  private final ReadWriteLockManager lockManager = new ReadWriteLockManager();
  private final Storage delegatedStore;

  /**
   * Identifies a storage layer to be delegated to instead of mem storage.
   */
  @Retention(RetentionPolicy.RUNTIME)
  @Target({ ElementType.PARAMETER, ElementType.METHOD })
  @Qualifier
  public @interface Delegated { }

  @VisibleForTesting
  static final String THREADS_WAITING_GAUGE = "storage_lock_threads_waiting";

  @Inject
  MemStorage(
      @Delegated final SchedulerStore.Mutable schedulerStore,
      final JobStore.Mutable jobStore,
      final TaskStore.Mutable taskStore,
      @Delegated final LockStore.Mutable lockStore,
      @Delegated final Storage delegated,
      @Delegated final QuotaStore.Mutable quotaStore,
      @Delegated final AttributeStore.Mutable attributeStore,
      @Delegated final JobUpdateStore.Mutable updateStore,
      StatsProvider statsProvider) {

    this.delegatedStore = requireNonNull(delegated);
    storeProvider = new MutableStoreProvider() {
      @Override
      public SchedulerStore.Mutable getSchedulerStore() {
        return schedulerStore;
      }

      @Override
      public JobStore.Mutable getJobStore() {
        return jobStore;
      }

      @Override
      public TaskStore getTaskStore() {
        return taskStore;
      }

      @Override
      public TaskStore.Mutable getUnsafeTaskStore() {
        return taskStore;
      }

      @Override
      public LockStore.Mutable getLockStore() {
        return lockStore;
      }

      @Override
      public QuotaStore.Mutable getQuotaStore() {
        return quotaStore;
      }

      @Override
      public AttributeStore.Mutable getAttributeStore() {
        return attributeStore;
      }

      @Override
      public JobUpdateStore.Mutable getJobUpdateStore() {
        return updateStore;
      }
    };

    statsProvider.makeGauge(
        THREADS_WAITING_GAUGE,
        new Supplier<Integer>() {
          @Override
          public Integer get() {
            return lockManager.getQueueLength();
          }
        }
    );
  }

  /**
   * Creates a new empty in-memory storage for use in testing, exporting gauges to the provided
   * stats provider.  NOTE: Due to the fact that some libraries statically access {@link Stats},
   * not all guaranteed to use the stats provider.
   */
  @VisibleForTesting
  public static Storage newEmptyStorage(final StatsProvider statsProvider) {
    Injector injector = Guice.createInjector(
        DbModule.testModule(Bindings.annotatedKeyFactory(Delegated.class)),
        new MemStorageModule(Bindings.annotatedKeyFactory(Volatile.class)),
        new AbstractModule() {
          @Override
          protected void configure() {
            bind(StatsProvider.class).toInstance(statsProvider);
          }
        });

    Storage storage = injector.getInstance(Key.get(Storage.class, Volatile.class));
    storage.prepare();
    return storage;
  }

  /**
   * Creates a new empty in-memory storage for use in testing.
   */
  @VisibleForTesting
  public static Storage newEmptyStorage() {
    return newEmptyStorage(Stats.STATS_PROVIDER);
  }

  private <S extends StoreProvider, T, E extends Exception> T doWork(
      LockType lockType,
      S stores,
      StorageOperation<S, T, E> work,
      SlidingStats stats,
      AtomicLong lockWaitStat) throws StorageException, E {

    requireNonNull(work);

    // Perform the work, and only record stats for top-level transactions.  This prevents
    // over-counting when nested transactions are performed.
    long lockStartNanos = System.nanoTime();
    boolean topLevelOperation = lockManager.lock(lockType);
    if (topLevelOperation) {
      lockWaitStat.addAndGet(System.nanoTime() - lockStartNanos);
    }
    try {
      return work.apply(stores);
    } finally {
      lockManager.unlock(lockType);
      if (topLevelOperation) {
        stats.accumulate(System.nanoTime() - lockStartNanos);
      }
    }
  }

  @Override
  public <T, E extends Exception> T consistentRead(final Work<T, E> work)
          throws StorageException, E {
    return delegatedStore.consistentRead(new Work<T, E>() {
      @Override
      public T apply(StoreProvider provider) throws E {
        return doWork(LockType.READ, storeProvider, work, readStats, readLockWaitNanos);
      }
    });
  }

  @Override
  public <T, E extends Exception> T write(final MutateWork<T, E> work) throws StorageException, E {
    return delegatedStore.write(new MutateWork<T, E>() {
      @Override
      public T apply(MutableStoreProvider provider) throws E {
       return doWork(LockType.WRITE, storeProvider, work, writeStats, writeLockWaitNanos);
      }
    });
  }

  @Override
  public void prepare() throws StorageException {
    delegatedStore.prepare();
  }

  @Timed("mem_storage_weakly_consistent_read_operation")
  @Override
  public <T, E extends Exception> T weaklyConsistentRead(final Work<T, E> work)
      throws StorageException, E {

    return delegatedStore.weaklyConsistentRead(new Work<T, E>() {
      @Override
      public T apply(StoreProvider provider) throws E {
        return work.apply(storeProvider);
      }
    });

  }
}
