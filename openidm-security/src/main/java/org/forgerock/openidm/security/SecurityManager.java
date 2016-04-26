/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright 2013-2014 ForgeRock AS.
 *
 * The contents of this file are subject to the terms
 * of the Common Development and Distribution License
 * (the License). You may not use this file except in
 * compliance with the License.
 *
 * You can obtain a copy of the License at
 * http://forgerock.org/license/CDDLv1.0.html
 * See the License for the specific language governing
 * permission and limitations under the License.
 *
 * When distributing Covered Code, include this CDDL
 * Header Notice in each file and include the License file
 * at http://forgerock.org/license/CDDLv1.0.html
 * If applicable, add the following below the CDDL Header,
 * with the fields enclosed by brackets [] replaced by
 * your own identifying information:
 * "Portions Copyrighted [year] [name of copyright owner]"
 */

package org.forgerock.openidm.security;

import java.security.KeyStore;
import java.security.Security;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;

import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.ConfigurationPolicy;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Properties;
import org.apache.felix.scr.annotations.Property;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.Service;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.forgerock.json.fluent.JsonValue;
import org.forgerock.json.resource.ActionRequest;
import org.forgerock.json.resource.CreateRequest;
import org.forgerock.json.resource.DeleteRequest;
import org.forgerock.json.resource.InternalServerErrorException;
import org.forgerock.json.resource.PatchRequest;
import org.forgerock.json.resource.QueryRequest;
import org.forgerock.json.resource.QueryResultHandler;
import org.forgerock.json.resource.ReadRequest;
import org.forgerock.json.resource.RequestHandler;
import org.forgerock.json.resource.Requests;
import org.forgerock.json.resource.Resource;
import org.forgerock.json.resource.ResourceException;
import org.forgerock.json.resource.ResultHandler;
import org.forgerock.json.resource.RootContext;
import org.forgerock.json.resource.Router;
import org.forgerock.json.resource.ServerContext;
import org.forgerock.json.resource.UpdateRequest;
import org.forgerock.openidm.cluster.ClusterUtils;
import org.forgerock.openidm.core.IdentityServer;
import org.forgerock.openidm.core.ServerConstants;
import org.forgerock.openidm.crypto.factory.CryptoUpdateService;
import org.forgerock.openidm.jetty.Config;
import org.forgerock.openidm.jetty.Param;
import org.forgerock.openidm.repo.RepositoryService;
import org.forgerock.openidm.security.impl.CertificateResourceProvider;
import org.forgerock.openidm.security.impl.EntryResourceProvider;
import org.forgerock.openidm.security.impl.JcaKeyStoreHandler;
import org.forgerock.openidm.security.impl.KeystoreResourceProvider;
import org.forgerock.openidm.security.impl.PrivateKeyResourceProvider;
import org.osgi.framework.Constants;
import org.osgi.service.component.ComponentContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import sun.security.pkcs11.SunPKCS11;

/**
 * A Security Manager Service which handles operations on the java security
 * keystore and truststore files.
 */
@Component(name = SecurityManager.PID, policy = ConfigurationPolicy.IGNORE, metatype = true, 
        description = "OpenIDM Security Management Service", immediate = true)
@Service
@Properties({
    @Property(name = Constants.SERVICE_VENDOR, value = ServerConstants.SERVER_VENDOR_NAME),
    @Property(name = Constants.SERVICE_DESCRIPTION, value = "Security Management Service"),
    @Property(name = ServerConstants.ROUTER_PREFIX, value = "/security/*") })
@SuppressWarnings({"unused", "StatementWithEmptyBody"})
public class SecurityManager implements RequestHandler, KeyStoreManager {

    static final String PID = "org.forgerock.openidm.security";

    /**
     * Setup logging for the {@link SecurityManager}.
     */
    private final static Logger logger = LoggerFactory.getLogger(SecurityManager.class);
    private static final String OPENIDM_KEYSTORE_TYPE = "openidm.keystore.type";
    private static final String PKCS11_CONFIG = "openidm.security.pkcs11.config";
    private static final String PKCS11 = "pkcs11";

    @Reference
    private RepositoryService repoService;

    @Reference
    private CryptoUpdateService cryptoUpdateService;

    private final Router router = new Router();
    
    private KeyStoreHandler trustStoreHandler = null;
    private KeyStoreHandler keyStoreHandler = null;

    @Activate
    void activate(@SuppressWarnings("unused") ComponentContext compContext) throws Exception {
        logger.debug("Activating Security Management Service {}", compContext);
        // Add the Bouncy Castle provider
        final String keystoreType =
                IdentityServer.getInstance().getProperty(OPENIDM_KEYSTORE_TYPE, KeyStore.getDefaultType());
        if (PKCS11.equals(keystoreType.toLowerCase())) {
            final String config = IdentityServer.getInstance().getProperty(PKCS11_CONFIG);
            if (config != null) {
                Security.addProvider(new SunPKCS11(config));
            } else {
                throw new RuntimeException("No pkcs11 config provided in the boot.properties");
            }
        } else {
            Security.addProvider(new BouncyCastleProvider());
        }
        
        String keyStoreType = Param.getKeystoreType();
        String keyStoreLocation = Param.getKeystoreLocation();
        String keyStorePassword = Param.getKeystorePassword(false); 
        
        String trustStoreType = Param.getTruststoreType();
        String trustStoreLocation = Param.getTruststoreLocation();
        String trustStorePassword = Param.getTruststorePassword(false);

        // Set System properties
        if (System.getProperty("javax.net.ssl.keyStore") == null) {
            System.setProperty("javax.net.ssl.keyStore", keyStoreLocation);
            System.setProperty("javax.net.ssl.keyStorePassword", keyStorePassword);
            System.setProperty("javax.net.ssl.keyStoreType", keyStoreType);
        }
        if (System.getProperty("javax.net.ssl.trustStore") == null) {
            System.setProperty("javax.net.ssl.trustStore", trustStoreLocation);
            System.setProperty("javax.net.ssl.trustStorePassword", trustStorePassword);
            System.setProperty("javax.net.ssl.trustStoreType", trustStoreType);
        }
        
        keyStoreHandler = new JcaKeyStoreHandler(keyStoreType, keyStoreLocation, keyStorePassword);
        KeystoreResourceProvider keystoreProvider = new KeystoreResourceProvider("keystore", keyStoreHandler, this, repoService);
        EntryResourceProvider keystoreCertProvider = new CertificateResourceProvider("keystore", keyStoreHandler, this, repoService);
        EntryResourceProvider privateKeyProvider = new PrivateKeyResourceProvider("keystore", keyStoreHandler, this, repoService);

        router.addRoute("/keystore", keystoreProvider);
        router.addRoute("/keystore/cert", keystoreCertProvider);
        router.addRoute("/keystore/privatekey", privateKeyProvider);

        trustStoreHandler = new JcaKeyStoreHandler(trustStoreType, trustStoreLocation, trustStorePassword);
        KeystoreResourceProvider truststoreProvider = new KeystoreResourceProvider("truststore", trustStoreHandler, this, repoService);
        EntryResourceProvider truststoreCertProvider = new CertificateResourceProvider("truststore", trustStoreHandler, this, repoService);

        router.addRoute("/truststore", truststoreProvider);
        router.addRoute("/truststore/cert", truststoreCertProvider);
        
        String instanceType = IdentityServer.getInstance().getProperty("openidm.instance.type", ClusterUtils.TYPE_STANDALONE);
        
        String propValue = Param.getProperty("openidm.https.keystore.cert.alias");
        String privateKeyAlias = (propValue == null) ? "openidm-localhost" : propValue;

        final boolean isPkcs11 = PKCS11.equalsIgnoreCase(keystoreType);

        try {
            if (instanceType.equals(ClusterUtils.TYPE_CLUSTERED_ADDITIONAL) && !isPkcs11) {
                // Load keystore and truststore from the repository
                keystoreProvider.loadStoreFromRepo();
                truststoreProvider.loadStoreFromRepo();

                // Reload the SSL context
                reload();
                // Update CryptoService
                cryptoUpdateService.updateKeySelector(keyStoreHandler.getStore(), keyStorePassword);
            } else {
                // Check if the default alias exists in keystore and truststore
                final boolean defaultPrivateKeyEntryExists = privateKeyProvider.hasEntry(privateKeyAlias);
                final boolean defaultTruststoreEntryExists = truststoreCertProvider.hasEntry(privateKeyAlias);
                if (!defaultPrivateKeyEntryExists && !defaultTruststoreEntryExists) {
                    // dafault keystore/truststore entries do not exist
                    // Create the default private key
                    createDefaultKeystoreAndTruststoreEntries(privateKeyAlias, privateKeyProvider, keystoreCertProvider,
                            truststoreCertProvider);

                    // Reload the SSL context
                    reload();

                    Config.updateConfig(null);
                } else if (!defaultPrivateKeyEntryExists) {
                    // no default keystore entry, but truststore has default entry
                    // this should only happen if the enduser is manually editing the keystore/truststore
                    logger.error("Keystore and truststore out of sync. The keystore doesn't contain the default "
                            + "entry, but the truststore does.");
                    throw new InternalServerErrorException("Keystore and truststore out of sync. The keystore "
                            + "doesn't contain the default entry, but the truststore does.");

                } else if (!defaultTruststoreEntryExists) {
                    // default keystore entry exists, but truststore default entry does not exist
                    // this should only happen if the enduser is manually editing the keystore/truststore
                    logger.error("Keystore and truststore out of sync. The keystore contains the default entry, but "
                            + "the truststore doesn't");
                    throw new InternalServerErrorException("Keystore and truststore out of sync. The keystore "
                            + "contains the default entry, but the truststore doesn't");
                } else {
                    // the default entry exists in both the truststore and keystore
                    // do nothing
                }

                // If this is the first/primary node in a cluster, then save the keystore and truststore to the repository
                if (instanceType.equals(ClusterUtils.TYPE_CLUSTERED_FIRST) && !isPkcs11) {
                    keystoreProvider.saveStoreToRepo();
                    truststoreProvider.saveStoreToRepo();
                }
            }
        } catch (Exception e) {
            logger.warn("Error initializing keys", e);
        }
    }

    @Deactivate
    void deactivate(ComponentContext compContext) {
        logger.debug("Deactivating Security Management Service {}", compContext);
        router.removeAllRoutes();
    }
    
    // ----- Implementation of KeyStoreManager interface
    
    public void reload() throws Exception {
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStoreHandler.getStore());
        TrustManager [] trustManagers = tmf.getTrustManagers();

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStoreHandler.getStore(), keyStoreHandler.getPassword().toCharArray());
        KeyManager [] keyManagers = kmf.getKeyManagers();

        
        SSLContext context = SSLContext.getInstance("SSL");
        context.init(keyManagers, trustManagers, null);
        SSLContext.setDefault(context);
    }

    // ----- Implementation of RequestHandler interface

    @Override
    public void handleAction(final ServerContext context, final ActionRequest request,
            final ResultHandler<JsonValue> handler) {
        router.handleAction(context, request, handler);
    }

    @Override
    public void handleCreate(final ServerContext context, final CreateRequest request,
            final ResultHandler<Resource> handler) {
        router.handleCreate(context, request, handler);
    }

    @Override
    public void handleDelete(final ServerContext context, final DeleteRequest request,
            final ResultHandler<Resource> handler) {
        router.handleDelete(context, request, handler);
    }

    @Override
    public void handlePatch(final ServerContext context, final PatchRequest request,
            final ResultHandler<Resource> handler) {
        router.handlePatch(context, request, handler);
    }

    @Override
    public void handleQuery(final ServerContext context, final QueryRequest request,
            final QueryResultHandler handler) {
        router.handleQuery(context, request, handler);
    }

    @Override
    public void handleRead(final ServerContext context, final ReadRequest request,
            final ResultHandler<Resource> handler) {
        router.handleRead(context, request, handler);
    }

    @Override
    public void handleUpdate(final ServerContext context, final UpdateRequest request,
            final ResultHandler<Resource> handler) {
        router.handleUpdate(context, request, handler);
    }

    private void createDefaultKeystoreAndTruststoreEntries(final String alias,
            final EntryResourceProvider privateKeyProvider,
            final EntryResourceProvider keystoreCertProvider,
            final EntryResourceProvider truststoreCertProvider)
    throws Exception {

        //create the keystore default entry
        privateKeyProvider.createDefaultEntry(alias);

        final DefaultEntriesResultHandler defaultEntriesResultHandler = new DefaultEntriesResultHandler();

        //get the keystore default entry cert
        final ReadRequest readRequest = Requests.newReadRequest("/keystore/cert");
        keystoreCertProvider.readInstance(
            new ServerContext(new RootContext()), alias, readRequest, defaultEntriesResultHandler);

        //add the keystore default entry cert to the truststore
        final CreateRequest createRequest = Requests.newCreateRequest("/truststore/cert", alias,
                defaultEntriesResultHandler.result.getContent());
        truststoreCertProvider.createInstance(
                new ServerContext(new RootContext()), createRequest, defaultEntriesResultHandler);

    }

    private final static class DefaultEntriesResultHandler implements ResultHandler<Resource> {

        private Resource result;

        @Override
        public void handleError(ResourceException e) {
            logger.error("Unable to create default keystore/truststore entries", e);
            throw new RuntimeException("Unable to create default keystore/truststore entries", e);
        }

        @Override
        public void handleResult(Resource resource) {
            result = resource;
        }
    }
}
