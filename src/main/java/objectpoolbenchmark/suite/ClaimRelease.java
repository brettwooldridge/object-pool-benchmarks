/**
 * Copyright (c) 2002-2013 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package objectpoolbenchmark.suite;

import java.util.concurrent.TimeUnit;

import nf.fr.eraasoft.pool.PoolSettings;
import nf.fr.eraasoft.pool.impl.PoolControler;
import org.apache.commons.pool.impl.GenericObjectPool;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.openjdk.jmh.annotations.*;
import stormpot.*;
import objectpoolbenchmark.suite.commonspool.MyCommonsObject;
import objectpoolbenchmark.suite.commonspool.MyPoolableObjectFactory;
import objectpoolbenchmark.suite.commonspool2.MyCommons2Object;
import objectpoolbenchmark.suite.commonspool2.MyPooledObjectFactory;
import objectpoolbenchmark.suite.furious.MyFuriousObject;
import objectpoolbenchmark.suite.furious.MyPoolableObject;
import objectpoolbenchmark.suite.stormpot.GenericAllocator;
import objectpoolbenchmark.suite.stormpot.GenericPoolable;
import stormpot.bpool.BlazePool;
import stormpot.qpool.QueuePool;

@State(Scope.Benchmark)
public abstract class ClaimRelease
{
  protected static final int poolSize = Integer.getInteger("pool.size", 10);
  protected static final int objsToClaim = Integer.getInteger("cycle.claim.count", 1);

  @Setup
  public abstract void preparePool() throws Exception;

  @TearDown
  public abstract void tearDownPool() throws Exception;

  @CompilerControl(CompilerControl.Mode.INLINE)
  public abstract Object claim() throws Exception;

  @CompilerControl(CompilerControl.Mode.INLINE)
  public abstract void release(Object obj) throws Exception;


  @GenerateMicroBenchmark
  public void cycle() throws Exception {
    int claimsLeft = objsToClaim;
    claimRelease(claimsLeft);
  }

  @CompilerControl(CompilerControl.Mode.INLINE)
  private void claimRelease(int claimsLeft) throws Exception {
    if (claimsLeft == 0) {
      return;
    }
    Object obj = claim();
    claimRelease(claimsLeft - 1);
    release(obj);
  }


  public abstract static class Stormpot extends ClaimRelease {
    private final Timeout timeout = new Timeout(10, TimeUnit.SECONDS);
    private LifecycledPool<GenericPoolable> pool;

    @Override
    public void preparePool() {
      Config<GenericPoolable> config = new Config<>().setAllocator(new GenericAllocator());
      config.setSize(poolSize);
      Expiration<GenericPoolable> expiration = new Expiration<GenericPoolable>() {
        @Override
        public boolean hasExpired(SlotInfo<? extends GenericPoolable> info) {
          Costs.expendValidation();
          return false;
        }
      };
      config.setExpiration(expiration);
      pool = buildPool(config);
    }

    protected abstract LifecycledPool<GenericPoolable> buildPool(Config<GenericPoolable> config);

    @Override
    public void tearDownPool() throws InterruptedException {
      pool.shutdown().await(timeout);
    }

    @Override
    public Object claim() throws Exception {
      return pool.claim(timeout);
    }

    @Override
    public void release(Object obj) {
      ((GenericPoolable)obj).release();
    }
  }

  public static class StormpotBlazePool extends Stormpot {
    @Override
    protected LifecycledPool<GenericPoolable> buildPool(Config<GenericPoolable> config) {
      return new BlazePool<>(config);
    }
  }

  public static class StormpotQueuePool extends Stormpot {
    @Override
    protected LifecycledPool<GenericPoolable> buildPool(Config<GenericPoolable> config) {
      return new QueuePool<>(config);
    }
  }

  public static class Furious extends ClaimRelease {
    private nf.fr.eraasoft.pool.ObjectPool<MyFuriousObject> pool;

    @Override
    public void preparePool() throws Exception {
      PoolSettings<MyFuriousObject> settings = new PoolSettings<>(new MyPoolableObject());
      settings.min(0).max(poolSize);
      pool = settings.pool();
    }

    @Override
    public void tearDownPool() throws Exception {
      PoolControler.shutdown();
    }

    @Override
    public Object claim() throws Exception {
      return pool.getObj();
    }

    @Override
    public void release(Object obj) throws Exception {
      pool.returnObj((MyFuriousObject) obj);
    }
  }

  public static class CommonsPool2 extends ClaimRelease {
    private org.apache.commons.pool2.ObjectPool<MyCommons2Object> pool;

    @Override
    public void preparePool() throws Exception {
      GenericObjectPoolConfig config = new GenericObjectPoolConfig();
      config.setMaxTotal(poolSize);
      config.setBlockWhenExhausted(true);
      config.setTestOnBorrow(true);
      pool = new org.apache.commons.pool2.impl.GenericObjectPool<>(
          new MyPooledObjectFactory(), config);
    }

    @Override
    public void tearDownPool() throws Exception {
      pool.close();
    }

    @Override
    public Object claim() throws Exception {
      return pool.borrowObject();
    }

    @Override
    public void release(Object obj) throws Exception {
      pool.returnObject((MyCommons2Object) obj);
    }
  }

  public abstract static class CommonsPool extends ClaimRelease {
    protected org.apache.commons.pool.ObjectPool<MyCommonsObject> pool;

    @Override
    public void tearDownPool() throws Exception {
      pool.close();
    }

    @Override
    public Object claim() throws Exception {
      return pool.borrowObject();
    }

    @Override
    public void release(Object obj) throws Exception {
      pool.returnObject((MyCommonsObject) obj);
    }
  }

  public static class CommonsPoolGeneric extends CommonsPool {
    @Override
    public void preparePool() throws Exception {
      pool = new org.apache.commons.pool.impl.GenericObjectPool<>(
          new MyPoolableObjectFactory(),
          poolSize,
          GenericObjectPool.WHEN_EXHAUSTED_BLOCK,
          GenericObjectPool.DEFAULT_MAX_WAIT,
          GenericObjectPool.DEFAULT_MAX_IDLE,
          GenericObjectPool.DEFAULT_MIN_IDLE,
          true, // test on borrow
          false,
          GenericObjectPool.DEFAULT_TIME_BETWEEN_EVICTION_RUNS_MILLIS,
          GenericObjectPool.DEFAULT_NUM_TESTS_PER_EVICTION_RUN,
          GenericObjectPool.DEFAULT_MIN_EVICTABLE_IDLE_TIME_MILLIS,
          false,
          GenericObjectPool.DEFAULT_SOFT_MIN_EVICTABLE_IDLE_TIME_MILLIS,
          GenericObjectPool.DEFAULT_LIFO);
    }
  }

  public static class CommonsPoolStack extends CommonsPool {
    @Override
    public void preparePool() throws Exception {
      pool = new org.apache.commons.pool.impl.StackObjectPool<>(
          new MyPoolableObjectFactory(),
          poolSize);
    }
  }
}