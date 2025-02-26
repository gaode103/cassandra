/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.cassandra.auth;

import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

import org.junit.Test;

import org.apache.cassandra.db.ConsistencyLevel;
import org.apache.cassandra.exceptions.UnavailableException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class AuthCacheTest
{
    private int loadCounter = 0;
    private int validity = 2000;
    private boolean isCacheEnabled = true;

    @Test
    public void testCacheLoaderIsCalledOnFirst()
    {
        TestCache<String, Integer> authCache = new TestCache<>(this::countingLoader, this::setValidity, () -> validity, () -> isCacheEnabled);

        int result = authCache.get("10");

        assertEquals(10, result);
        assertEquals(1, loadCounter);
    }

    @Test
    public void testCacheLoaderIsNotCalledOnSecond()
    {
        TestCache<String, Integer> authCache = new TestCache<>(this::countingLoader, this::setValidity, () -> validity, () -> isCacheEnabled);
        authCache.get("10");
        assertEquals(1, loadCounter);

        int result = authCache.get("10");

        assertEquals(10, result);
        assertEquals(1, loadCounter);
    }

    @Test
    public void testCacheLoaderIsAlwaysCalledWhenDisabled()
    {
        isCacheEnabled = false;
        TestCache<String, Integer> authCache = new TestCache<>(this::countingLoader, this::setValidity, () -> validity, () -> isCacheEnabled);

        authCache.get("10");
        int result = authCache.get("10");

        assertEquals(10, result);
        assertEquals(2, loadCounter);
    }

    @Test
    public void testCacheLoaderIsAlwaysCalledWhenValidityIsZero()
    {
        setValidity(0);
        TestCache<String, Integer> authCache = new TestCache<>(this::countingLoader, this::setValidity, () -> validity, () -> isCacheEnabled);

        authCache.get("10");
        int result = authCache.get("10");

        assertEquals(10, result);
        assertEquals(2, loadCounter);
    }

    @Test
    public void testCacheLoaderIsCalledAfterFullInvalidate()
    {
        TestCache<String, Integer> authCache = new TestCache<>(this::countingLoader, this::setValidity, () -> validity, () -> isCacheEnabled);
        authCache.get("10");

        authCache.invalidate();
        int result = authCache.get("10");

        assertEquals(10, result);
        assertEquals(2, loadCounter);
    }

    @Test
    public void testCacheLoaderIsCalledAfterInvalidateKey()
    {
        TestCache<String, Integer> authCache = new TestCache<>(this::countingLoader, this::setValidity, () -> validity, () -> isCacheEnabled);
        authCache.get("10");

        authCache.invalidate("10");
        int result = authCache.get("10");

        assertEquals(10, result);
        assertEquals(2, loadCounter);
    }

    @Test
    public void testCacheLoaderIsCalledAfterReset()
    {
        TestCache<String, Integer> authCache = new TestCache<>(this::countingLoader, this::setValidity, () -> validity, () -> isCacheEnabled);
        authCache.get("10");

        authCache.cache = null;
        int result = authCache.get("10");

        assertEquals(10, result);
        assertEquals(2, loadCounter);
    }

    @Test
    public void testThatZeroValidityTurnOffCaching()
    {
        setValidity(0);
        TestCache<String, Integer> authCache = new TestCache<>(this::countingLoader, this::setValidity, () -> validity, () -> isCacheEnabled);
        authCache.get("10");
        int result = authCache.get("10");

        assertNull(authCache.cache);
        assertEquals(10, result);
        assertEquals(2, loadCounter);
    }

    @Test
    public void testThatRaisingValidityTurnOnCaching()
    {
        setValidity(0);
        TestCache<String, Integer> authCache = new TestCache<>(this::countingLoader, this::setValidity, () -> validity, () -> isCacheEnabled);

        authCache.setValidity(2000);
        authCache.cache = authCache.initCache(null);

        assertNotNull(authCache.cache);
    }

    @Test
    public void testDisableCache()
    {
        isCacheEnabled = false;
        TestCache<String, Integer> authCache = new TestCache<>(this::countingLoader, this::setValidity, () -> validity, () -> isCacheEnabled);

        assertNull(authCache.cache);
    }

    @Test
    public void testDynamicallyEnableCache()
    {
        isCacheEnabled = false;
        TestCache<String, Integer> authCache = new TestCache<>(this::countingLoader, this::setValidity, () -> validity, () -> isCacheEnabled);

        isCacheEnabled = true;
        authCache.cache = authCache.initCache(null);

        assertNotNull(authCache.cache);
    }

    @Test
    public void testDefaultPolicies()
    {
        TestCache<String, Integer> authCache = new TestCache<>(this::countingLoader, this::setValidity, () -> validity, () -> isCacheEnabled);

        assertTrue(authCache.cache.policy().expireAfterWrite().isPresent());
        assertTrue(authCache.cache.policy().refreshAfterWrite().isPresent());
        assertTrue(authCache.cache.policy().eviction().isPresent());
    }

    @Test(expected = UnavailableException.class)
    public void testCassandraExceptionPassThroughWhenCacheEnabled()
    {
        TestCache<String, Integer> cache = new TestCache<>(s -> { throw UnavailableException.create(ConsistencyLevel.QUORUM, 3, 1); }, this::setValidity, () -> validity, () -> isCacheEnabled);

        cache.get("expect-exception");
    }

    @Test(expected = UnavailableException.class)
    public void testCassandraExceptionPassThroughWhenCacheDisable()
    {
        isCacheEnabled = false;
        TestCache<String, Integer> cache = new TestCache<>(s -> { throw UnavailableException.create(ConsistencyLevel.QUORUM, 3, 1); }, this::setValidity, () -> validity, () -> isCacheEnabled);

        cache.get("expect-exception");
    }

    @Test
    public void testCassandraExceptionPassThroughWhenCacheRefreshed() throws InterruptedException
    {
        setValidity(50);
        TestCache<String, Integer> cache = new TestCache<>(this::countingLoaderWithException, this::setValidity, () -> validity, () -> isCacheEnabled);
        cache.get("10");

        // wait until the cached record expires
        Thread.sleep(60);

        for (int i = 1; i <= 5; i++)
        {
            try
            {
                cache.get("10");
                fail("Did not get expected Exception on attempt " + i);
            }
            catch (UnavailableException expected)
            {
            }
        }
    }

    @Test
    public void testCacheLoaderIsNotCalledOnGetAllWhenCacheIsDisabled()
    {
        isCacheEnabled = false;
        TestCache<String, Integer> authCache = new TestCache<>(this::countingLoader, this::setValidity, () -> validity, () -> isCacheEnabled);
        authCache.get("10");
        Map<String, Integer> result = authCache.getAll();

        // even though the cache is disabled and nothing is cache we still use loadFunction on get operation, so
        // its counter has been incremented
        assertThat(result).isEmpty();
        assertEquals(1, loadCounter);
    }

    @Test
    public void testCacheLoaderIsNotCalledOnGetAllWhenCacheIsEmpty()
    {
        TestCache<String, Integer> authCache = new TestCache<>(this::countingLoader, this::setValidity, () -> validity, () -> isCacheEnabled);

        Map<String, Integer> result = authCache.getAll();

        assertThat(result).isEmpty();
        assertEquals(0, loadCounter);
    }

    @Test
    public void testCacheLoaderIsNotCalledOnGetAllWhenCacheIsNotEmpty()
    {
        TestCache<String, Integer> authCache = new TestCache<>(this::countingLoader, this::setValidity, () -> validity, () -> isCacheEnabled);
        authCache.get("10");
        Map<String, Integer> result = authCache.getAll();

        assertThat(result).hasSize(1);
        assertThat(result).containsEntry("10", 10);
        assertEquals(1, loadCounter);
    }

    private void setValidity(int validity)
    {
        this.validity = validity;
    }

    private Integer countingLoader(String s)
    {
        loadCounter++;
        return Integer.parseInt(s);
    }

    private Integer countingLoaderWithException(String s)
    {
        Integer loadedValue = countingLoader(s);

        if (loadCounter > 1)
            throw UnavailableException.create(ConsistencyLevel.QUORUM, 3, 1);

        return loadedValue;
    }

    private static class TestCache<K, V> extends AuthCache<K, V>
    {
        private static int nameCounter = 0; // Allow us to create many instances of cache with same name prefix

        TestCache(Function<K, V> loadFunction, IntConsumer setValidityDelegate, IntSupplier getValidityDelegate, BooleanSupplier cacheEnabledDelegate)
        {
            super("TestCache" + nameCounter++,
                  setValidityDelegate,
                  getValidityDelegate,
                  (updateInterval) -> {},
                  () -> 1000,
                  (maxEntries) -> {},
                  () -> 10,
                  (updateActiveUpdate) -> {},
                  () -> false,
                  loadFunction,
                  cacheEnabledDelegate);
        }
    }
}
