/*
* Licensed to the Apache Software Foundation (ASF) under one
* or more contributor license agreements.  See the NOTICE file
* distributed with this work for additional information
* regarding copyright ownership.  The ASF licenses this file
* to you under the Apache License, Version 2.0 (the
* "License"); you may not use this file except in compliance
* with the License.  You may obtain a copy of the License at
*
*    http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing,
* software distributed under the License is distributed on an
* "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
* KIND, either express or implied.  See the License for the
* specific language governing permissions and limitations
* under the License.
*/
package org.apache.cassandra.security;

import org.apache.cassandra.io.util.File;
import java.io.IOException;
import java.security.cert.CertificateException;
import java.util.HashMap;
import java.util.Map;

import org.apache.commons.io.FileUtils;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.config.EncryptionOptions;
import org.apache.cassandra.config.EncryptionOptions.ServerEncryptionOptions;
import org.apache.cassandra.config.ParameterizedClass;

public class SSLFactoryTest
{
    private static final Logger logger = LoggerFactory.getLogger(SSLFactoryTest.class);

    static final SelfSignedCertificate ssc;
    static
    {
        DatabaseDescriptor.daemonInitialization();
        try
        {
            ssc = new SelfSignedCertificate();
        }
        catch (CertificateException e)
        {
            throw new RuntimeException("failed to create test certs");
        }
    }

    private ServerEncryptionOptions encryptionOptions;

    @Before
    public void setup()
    {
        encryptionOptions = new ServerEncryptionOptions()
                            .withTrustStore("test/conf/cassandra_ssl_test.truststore")
                            .withTrustStorePassword("cassandra")
                            .withRequireClientAuth(false)
                            .withCipherSuites("TLS_RSA_WITH_AES_128_CBC_SHA");
    }

    private ServerEncryptionOptions addKeystoreOptions(ServerEncryptionOptions options)
    {
        return options.withKeyStore("test/conf/cassandra_ssl_test.keystore")
                      .withKeyStorePassword("cassandra");
    }

    @Test
    public void testSslContextReload_HappyPath() throws IOException, InterruptedException
    {
        try
        {
            ServerEncryptionOptions options = addKeystoreOptions(encryptionOptions)
                                              .withInternodeEncryption(ServerEncryptionOptions.InternodeEncryption.all);

            SSLFactory.initHotReloading(options, options, true);

            SslContext oldCtx = SSLFactory.getOrCreateSslContext(options, true, ISslContextFactory.SocketType.CLIENT);
            File keystoreFile = new File(options.keystore);

            SSLFactory.checkCertFilesForHotReloading(options, options);

            keystoreFile.trySetLastModified(System.currentTimeMillis() + 15000);

            SSLFactory.checkCertFilesForHotReloading(options, options);
            SslContext newCtx = SSLFactory.getOrCreateSslContext(options, true, ISslContextFactory.SocketType.CLIENT);

            Assert.assertNotSame(oldCtx, newCtx);
        }
        catch (Exception e)
        {
            throw e;
        }
        finally
        {
            DatabaseDescriptor.loadConfig();
        }
    }

    @Test(expected = IOException.class)
    public void testSslFactorySslInit_BadPassword_ThrowsException() throws IOException
    {
        ServerEncryptionOptions options = addKeystoreOptions(encryptionOptions)
                                    .withKeyStorePassword("bad password")
                                    .withInternodeEncryption(ServerEncryptionOptions.InternodeEncryption.all);

        SSLFactory.validateSslContext("testSslFactorySslInit_BadPassword_ThrowsException", options, false, true);
    }

    @Test
    public void testSslFactoryHotReload_BadPassword_DoesNotClearExistingSslContext() throws IOException
    {
        try
        {
            ServerEncryptionOptions options = addKeystoreOptions(encryptionOptions);

            SSLFactory.initHotReloading(options, options, true);
            SslContext oldCtx = SSLFactory.getOrCreateSslContext(options, true, ISslContextFactory.SocketType.CLIENT);
            File keystoreFile = new File(options.keystore);

            SSLFactory.checkCertFilesForHotReloading(options, options);
            keystoreFile.trySetLastModified(System.currentTimeMillis() + 5000);

            ServerEncryptionOptions modOptions = new ServerEncryptionOptions(options)
                                                 .withKeyStorePassword("bad password");
            SSLFactory.checkCertFilesForHotReloading(modOptions, modOptions);
            SslContext newCtx = SSLFactory.getOrCreateSslContext(options, true, ISslContextFactory.SocketType.CLIENT);

            Assert.assertSame(oldCtx, newCtx);
        }
        finally
        {
            DatabaseDescriptor.loadConfig();
        }
    }

    @Test
    public void testSslFactoryHotReload_CorruptOrNonExistentFile_DoesNotClearExistingSslContext() throws IOException
    {
        try
        {
            ServerEncryptionOptions options = addKeystoreOptions(encryptionOptions);

            File testKeystoreFile = new File(options.keystore + ".test");
            FileUtils.copyFile(new File(options.keystore).toJavaIOFile(), testKeystoreFile.toJavaIOFile());
            options = options.withKeyStore(testKeystoreFile.path());


            SSLFactory.initHotReloading(options, options, true);
            SslContext oldCtx = SSLFactory.getOrCreateSslContext(options, true, ISslContextFactory.SocketType.CLIENT);
            SSLFactory.checkCertFilesForHotReloading(options, options);

            testKeystoreFile.trySetLastModified(System.currentTimeMillis() + 15000);
            FileUtils.forceDelete(testKeystoreFile.toJavaIOFile());

            SSLFactory.checkCertFilesForHotReloading(options, options);
            SslContext newCtx = SSLFactory.getOrCreateSslContext(options, true, ISslContextFactory.SocketType.CLIENT);

            Assert.assertSame(oldCtx, newCtx);
        }
        catch (Exception e)
        {
            throw e;
        }
        finally
        {
            DatabaseDescriptor.loadConfig();
            FileUtils.deleteQuietly(new File(encryptionOptions.keystore + ".test").toJavaIOFile());
        }
    }

    @Test
    public void getSslContext_ParamChanges() throws IOException
    {
        EncryptionOptions options = addKeystoreOptions(encryptionOptions)
                                    .withEnabled(true)
                                    .withCipherSuites("TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256");

        SslContext ctx1 = SSLFactory.getOrCreateSslContext(options, true,
                                                           ISslContextFactory.SocketType.SERVER);

        Assert.assertTrue(ctx1.isServer());
        Assert.assertEquals(ctx1.cipherSuites(), options.cipher_suites);

        options = options.withCipherSuites("TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256");

        SslContext ctx2 = SSLFactory.getOrCreateSslContext(options, true,
                                                           ISslContextFactory.SocketType.CLIENT);

        Assert.assertTrue(ctx2.isClient());
        Assert.assertEquals(ctx2.cipherSuites(), options.cipher_suites);
    }

    @Test
    public void testCacheKeyEqualityForCustomSslContextFactory() {

        Map<String,String> parameters1 = new HashMap<>();
        parameters1.put("key1", "value1");
        parameters1.put("key2", "value2");
        EncryptionOptions encryptionOptions1 =
        new EncryptionOptions()
        .withSslContextFactory(new ParameterizedClass(DummySslContextFactoryImpl.class.getName(), parameters1))
        .withProtocol("TLSv1.1")
        .withRequireClientAuth(true)
        .withRequireEndpointVerification(false);

        SSLFactory.CacheKey cacheKey1 = new SSLFactory.CacheKey(encryptionOptions1, ISslContextFactory.SocketType.SERVER
        );

        Map<String,String> parameters2 = new HashMap<>();
        parameters2.put("key1", "value1");
        parameters2.put("key2", "value2");
        EncryptionOptions encryptionOptions2 =
        new EncryptionOptions()
        .withSslContextFactory(new ParameterizedClass(DummySslContextFactoryImpl.class.getName(), parameters2))
        .withProtocol("TLSv1.1")
        .withRequireClientAuth(true)
        .withRequireEndpointVerification(false);

        SSLFactory.CacheKey cacheKey2 = new SSLFactory.CacheKey(encryptionOptions2, ISslContextFactory.SocketType.SERVER
        );

        Assert.assertEquals(cacheKey1, cacheKey2);
    }

    @Test
    public void testCacheKeyInequalityForCustomSslContextFactory() {

        Map<String,String> parameters1 = new HashMap<>();
        parameters1.put("key1", "value11");
        parameters1.put("key2", "value12");
        EncryptionOptions encryptionOptions1 =
        new EncryptionOptions()
        .withSslContextFactory(new ParameterizedClass(DummySslContextFactoryImpl.class.getName(), parameters1))
        .withProtocol("TLSv1.1");

        SSLFactory.CacheKey cacheKey1 = new SSLFactory.CacheKey(encryptionOptions1, ISslContextFactory.SocketType.SERVER
        );

        Map<String,String> parameters2 = new HashMap<>();
        parameters2.put("key1", "value21");
        parameters2.put("key2", "value22");
        EncryptionOptions encryptionOptions2 =
        new EncryptionOptions()
        .withSslContextFactory(new ParameterizedClass(DummySslContextFactoryImpl.class.getName(), parameters2))
        .withProtocol("TLSv1.1");

        SSLFactory.CacheKey cacheKey2 = new SSLFactory.CacheKey(encryptionOptions2, ISslContextFactory.SocketType.SERVER
        );

        Assert.assertNotEquals(cacheKey1, cacheKey2);
    }
}
