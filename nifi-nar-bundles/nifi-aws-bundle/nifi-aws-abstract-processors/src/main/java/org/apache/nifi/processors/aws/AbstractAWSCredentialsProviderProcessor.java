/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.nifi.processors.aws;

import com.amazonaws.AmazonWebServiceClient;
import com.amazonaws.ClientConfiguration;
import com.amazonaws.auth.AWSCredentialsProvider;
import org.apache.nifi.annotation.lifecycle.OnShutdown;
import org.apache.nifi.components.ConfigVerificationResult;
import org.apache.nifi.components.ConfigVerificationResult.Outcome;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.controller.ControllerService;
import org.apache.nifi.logging.ComponentLog;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.processor.VerifiableProcessor;
import org.apache.nifi.processors.aws.credentials.provider.service.AWSCredentialsProviderService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Base class for aws processors that uses AWSCredentialsProvider interface for creating aws clients.
 *
 * @param <ClientType> client type
 *
 * @see <a href="http://docs.aws.amazon.com/AWSJavaSDK/latest/javadoc/com/amazonaws/auth/AWSCredentialsProvider.html">AWSCredentialsProvider</a>
 */
public abstract class AbstractAWSCredentialsProviderProcessor<ClientType extends AmazonWebServiceClient>
    extends AbstractAWSProcessor<ClientType> implements VerifiableProcessor {

    /**
     * AWS credentials provider service
     *
     * @see  <a href="http://docs.aws.amazon.com/AWSJavaSDK/latest/javadoc/com/amazonaws/auth/AWSCredentialsProvider.html">AWSCredentialsProvider</a>
     */
    public static final PropertyDescriptor AWS_CREDENTIALS_PROVIDER_SERVICE = new PropertyDescriptor.Builder()
            .name("AWS Credentials Provider service")
            .description("The Controller Service that is used to obtain aws credentials provider")
            .required(false)
            .identifiesControllerService(AWSCredentialsProviderService.class)
            .build();

    /**
     * Attempts to create the client using the controller service first before falling back to the standard configuration.
     * @param context The process context
     * @return The created client
     */
    protected ClientType createClient(final ProcessContext context) {
        final ControllerService service = context.getProperty(AWS_CREDENTIALS_PROVIDER_SERVICE).asControllerService();
        if (service != null) {
            getLogger().debug("Using aws credentials provider service for creating client");
            return createClient(context, getCredentialsProvider(context), createConfiguration(context));
        } else {
            getLogger().debug("Using aws credentials for creating client");
            return super.createClient(context);
        }
    }

    @OnShutdown
    public void onShutDown() {
        if ( this.client != null ) {
           this.client.shutdown();
        }
    }

    @Override
    public List<ConfigVerificationResult> verify(final ProcessContext context, final ComponentLog verificationLogger, final Map<String, String> attributes) {
        final List<ConfigVerificationResult> results = new ArrayList<>();

        try {
            getConfiguration(context);
            results.add(new ConfigVerificationResult.Builder()
                    .outcome(Outcome.SUCCESSFUL)
                    .verificationStepName("Create Client and Configure Region")
                    .explanation("Successfully created AWS Client and configured Region")
                    .build());
        } catch (final Exception e) {
            verificationLogger.error("Failed to create AWS Client", e);
            results.add(new ConfigVerificationResult.Builder()
                    .outcome(Outcome.FAILED)
                    .verificationStepName("Create Client and Configure Region")
                    .explanation("Failed to crete AWS Client or configure Region: " + e.getMessage())
                    .build());
        }

        return results;
    }

    /**
     * Get credentials provider using the {@link AWSCredentialsProviderService}
     * @param context the process context
     * @return AWSCredentialsProvider the credential provider
     * @see  <a href="http://docs.aws.amazon.com/AWSJavaSDK/latest/javadoc/com/amazonaws/auth/AWSCredentialsProvider.html">AWSCredentialsProvider</a>
     */
    protected AWSCredentialsProvider getCredentialsProvider(final ProcessContext context) {

        final AWSCredentialsProviderService awsCredentialsProviderService =
              context.getProperty(AWS_CREDENTIALS_PROVIDER_SERVICE).asControllerService(AWSCredentialsProviderService.class);

        return awsCredentialsProviderService.getCredentialsProvider();

    }

    /**
     * Abstract method to create aws client using credentials provider.  This is the preferred method
     * for creating aws clients
     * @param context process context
     * @param credentialsProvider aws credentials provider
     * @param config aws client configuration
     * @return ClientType the client
     */
    protected abstract ClientType createClient(final ProcessContext context, final AWSCredentialsProvider credentialsProvider, final ClientConfiguration config);
}