// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package sample;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Scanner;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Callable;
import java.util.Arrays;

import javax.crypto.SecretKey;

import com.azure.storage.blob.BlobContainerAsyncClient;
import com.azure.storage.blob.BlobServiceAsyncClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.blob.models.ListBlobsOptions;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.microsoft.azure.AzureEnvironment;
import com.microsoft.azure.credentials.ApplicationTokenCredentials;
import com.microsoft.azure.management.mediaservices.v2020_05_01.Asset;
import com.microsoft.azure.management.mediaservices.v2020_05_01.BuiltInStandardEncoderPreset;
import com.microsoft.azure.management.mediaservices.v2020_05_01.ContentKeyPolicy;
import com.microsoft.azure.management.mediaservices.v2020_05_01.ContentKeyPolicyOption;
import com.microsoft.azure.management.mediaservices.v2020_05_01.ContentKeyPolicyPlayReadyConfiguration;
import com.microsoft.azure.management.mediaservices.v2020_05_01.ContentKeyPolicyPlayReadyContentEncryptionKeyFromHeader;
import com.microsoft.azure.management.mediaservices.v2020_05_01.ContentKeyPolicyPlayReadyContentType;
import com.microsoft.azure.management.mediaservices.v2020_05_01.ContentKeyPolicyPlayReadyExplicitAnalogTelevisionRestriction;
import com.microsoft.azure.management.mediaservices.v2020_05_01.ContentKeyPolicyPlayReadyLicense;
import com.microsoft.azure.management.mediaservices.v2020_05_01.ContentKeyPolicyPlayReadyLicenseType;
import com.microsoft.azure.management.mediaservices.v2020_05_01.ContentKeyPolicyPlayReadyPlayRight;
import com.microsoft.azure.management.mediaservices.v2020_05_01.ContentKeyPolicyPlayReadyUnknownOutputPassingOption;
import com.microsoft.azure.management.mediaservices.v2020_05_01.ContentKeyPolicyProperties;
import com.microsoft.azure.management.mediaservices.v2020_05_01.ContentKeyPolicyRestriction;
import com.microsoft.azure.management.mediaservices.v2020_05_01.ContentKeyPolicyRestrictionTokenKey;
import com.microsoft.azure.management.mediaservices.v2020_05_01.ContentKeyPolicyRestrictionTokenType;
import com.microsoft.azure.management.mediaservices.v2020_05_01.ContentKeyPolicySymmetricTokenKey;
import com.microsoft.azure.management.mediaservices.v2020_05_01.ContentKeyPolicyTokenClaim;
import com.microsoft.azure.management.mediaservices.v2020_05_01.ContentKeyPolicyTokenRestriction;
import com.microsoft.azure.management.mediaservices.v2020_05_01.EncoderNamedPreset;
import com.microsoft.azure.management.mediaservices.v2020_05_01.Job;
import com.microsoft.azure.management.mediaservices.v2020_05_01.JobInputHttp;
import com.microsoft.azure.management.mediaservices.v2020_05_01.JobOutput;
import com.microsoft.azure.management.mediaservices.v2020_05_01.JobOutputAsset;
import com.microsoft.azure.management.mediaservices.v2020_05_01.JobState;
import com.microsoft.azure.management.mediaservices.v2020_05_01.ListPathsResponse;
import com.microsoft.azure.management.mediaservices.v2020_05_01.StreamingEndpoint;
import com.microsoft.azure.management.mediaservices.v2020_05_01.StreamingEndpointResourceState;
import com.microsoft.azure.management.mediaservices.v2020_05_01.StreamingLocator;
import com.microsoft.azure.management.mediaservices.v2020_05_01.StreamingLocatorContentKey;
import com.microsoft.azure.management.mediaservices.v2020_05_01.StreamingLocatorContentKeyType;
import com.microsoft.azure.management.mediaservices.v2020_05_01.StreamingPath;
import com.microsoft.azure.management.mediaservices.v2020_05_01.StreamingPolicyStreamingProtocol;
import com.microsoft.azure.management.mediaservices.v2020_05_01.Transform;
import com.microsoft.azure.management.mediaservices.v2020_05_01.TransformOutput;
import com.microsoft.azure.management.mediaservices.v2020_05_01.implementation.MediaManager;
import com.microsoft.rest.LogLevel;

import org.apache.commons.codec.binary.Base64;

import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;

public class BasicPlayReady {
    private static final String ADAPTIVE_STREAMING_TRANSFORM_NAME = "MyTransformWithAdaptiveStreamingPreset";
    private static final String ISSUER = "myIssuer";
    private static final String AUDIENCE = "myAudience";
    private static final String CONTENT_KEY_POLICY_NAME = "PlayReadyContentKeyPolicy";
    private static final String BASE_URI = "https://nimbuscdn-nimbuspm.streaming.mediaservices.windows.net/2b533311-b215-4409-80af-529c3e853622/";
    private static final String MP4_FILE_NAME = "Ignite-short.mp4";
    private static final String CONTENT_KEY_IDENTIFIER_CLAIM = "urn:microsoft:azure:mediaservices:contentkeyidentifier";
    private static final String MULTI_DRM_CENC_STREAMING = "Predefined_MultiDrmCencStreaming";
    private static final String DEFAULT_STREAMING_ENDPOINT_NAME = "se"; // Please change this to your Streaming Endpoint name.

    private static byte[] TOKEN_SIGNING_KEY;

    public static void main(String[] args) {
        ConfigWrapper config = new ConfigWrapper();
        runPlayReadyTest(config);

        config.close();
        System.exit(0);
    }

    /**
     * Run the sample.
     *
     * @param config This param is of type ConfigWrapper, which reads values from
     *               local configuration file.
     */
    private static void runPlayReadyTest(ConfigWrapper config) {
        // Connect to media services, please see https://docs.microsoft.com/en-us/azure/media-services/latest/configure-connect-java-howto
        // for details.
        ApplicationTokenCredentials credentials = new ApplicationTokenCredentials(config.getAadClientId(),
                config.getAadTenantId(), config.getAadSecret(), AzureEnvironment.AZURE);
        credentials.withDefaultSubscriptionId(config.getSubscriptionId());

        // Get MediaManager, the entry point to Azure Media resource management.
        MediaManager manager = MediaManager.configure().withLogLevel(LogLevel.BODY_AND_HEADERS)
                .authenticate(credentials, credentials.defaultSubscriptionId());
        // Signed in.

        // Create a unique suffix so that we don't have name collisions if you run the
        // sample multiple times without cleaning up.
        UUID uuid = UUID.randomUUID();
        String uniqueness = uuid.toString();
        String jobName = "job-" + uniqueness;
        String outputAssetName = "output-" + uniqueness;
        String locatorName = "locator-" + uniqueness;
        MediaServicesEventProcessor eventProcessorHost = null;
        boolean stopEndpoint = false;

        Scanner scanner = new Scanner(System.in);

        try {
            // Ensure that you have the desired encoding Transform. This is really a one
            // time setup operation.
            Transform transform = getOrCreateTransform(manager, config.getResourceGroup(), config.getAccountName(),
                    ADAPTIVE_STREAMING_TRANSFORM_NAME);

            // Output from the encoding Job must be written to an Asset, so let's create one
            Asset outputAsset = createOutputAsset(manager, config.getResourceGroup(), config.getAccountName(),
                    outputAssetName);

            Job job = submitJob(manager, config.getResourceGroup(), config.getAccountName(),
                    transform.name(), outputAsset.name(), jobName);

            long startedTime = System.currentTimeMillis();

            try {
                // First we will try to process Job events through Event Hub in real-time. If this fails for any reason,
                // we will fall-back on polling Job status instead.

                System.out.println();
                System.out.println("Creating an event processor host to process events from Event Hub...");

                String storageConnectionString = "DefaultEndpointsProtocol=https;AccountName=" +
                        config.getStorageAccountName() +
                        ";AccountKey=" + config.getStorageAccountKey() + ";EndpointSuffix=core.windows.net";

                // Cleanup storage container. We will config Event Hub to use the storage container configured in appsettings.json.
                // All the blobs in <The container configured in appsettings.json> will be deleted.
                BlobServiceAsyncClient client = new BlobServiceClientBuilder()
                        .connectionString(storageConnectionString)
                        .buildAsyncClient();
                BlobContainerAsyncClient container = client.getBlobContainerAsyncClient(config.getStorageContainerName());
                container.listBlobs().subscribe(blobItem -> {
                            container.getBlobAsyncClient(blobItem.getName()).delete();
                        });

                // Create a new host to process events from an Event Hub.
                Object monitor = new Object();
                eventProcessorHost = new MediaServicesEventProcessor(jobName, monitor, null,
                        config.getEventHubConnectionString(), config.getEventHubName(),
                        container);

                // Define a task to wait for the job to finish.
                Callable<String> jobTask = () -> {
                    synchronized (monitor) {
                        monitor.wait();
                    }
                    return "Job";
                };

                // Define another task
                Callable<String> timeoutTask = () -> {
                    TimeUnit.MINUTES.sleep(30);
                    return "Timeout";
                };

                ExecutorService executor = Executors.newFixedThreadPool(2);
                List<Callable<String>> tasks = Arrays.asList(jobTask, timeoutTask);

                String result = executor.invokeAny(tasks);
                if (result.equalsIgnoreCase("Job")) {
                    // Job finished. Shutdown timeout.
                    executor.shutdownNow();
                } else {
                    // Timeout happened. Switch to polling method.
                    synchronized (monitor) {
                        monitor.notify();
                    }

                    throw new Exception("Timeout happened.");
                }

                // Get the latest status of the job.
                job = manager.jobs().getAsync(config.getResourceGroup(), config.getAccountName(), transform.name(), jobName).toBlocking().first();
            } catch (Exception e) {
                System.out.println("Warning: Failed to connect to Event Hub, please refer README for Event Hub and storage settings.");
                // if Event Grid or Event Hub is not configured, We will fall-back on polling instead.
                // Polling is not a recommended best practice for production applications because of the latency it introduces.
                // Overuse of this API may trigger throttling. Developers should instead use Event Grid.
                System.out.println("Failed to start Event Grid monitoring, will use polling job status instead...");
                job = waitForJobToFinish(manager, config.getResourceGroup(), config.getAccountName(),
                        transform.name(), jobName);
            }

            long elapsed = (System.currentTimeMillis() - startedTime) / 1000; // Elapsed time in seconds
            System.out.println("Job elapsed time: " + elapsed + " second(s).");

            if (job.state() == JobState.FINISHED) {
                // Set a token signing key that you want to use, in this example, we use the key defined in the configuration file.
                TOKEN_SIGNING_KEY = Base64.decodeBase64(config.getSymmetricKey());

                // Create the content key policy that configures how the content key is delivered
                // to end clients via the Key Delivery component of Azure Media Services.
                // We are using the ContentKeyIdentifierClaim in the ContentKeyPolicy which
                // means that the token presented to the Key Delivery Component must have
                // the identifier of the content key in it.
                ContentKeyPolicy policy = ensureContentKeyPolicyExists(manager, config.getResourceGroup(),
                        config.getAccountName(), CONTENT_KEY_POLICY_NAME);

                StreamingLocator locator = manager.streamingLocators().define(locatorName)
                        .withExistingMediaservice(config.getResourceGroup(), config.getAccountName())
                        .withAssetName(outputAssetName)
                        .withStreamingPolicyName(MULTI_DRM_CENC_STREAMING)
                        .withDefaultContentKeyPolicyName(policy.name())
                        .create();

                // In this example, we want to play the PlayReady (CENC) encrypted stream. 
                // We need to get the key identifier of the content key where its type is CommonEncryptionCenc.
                String keyIdentifier = null;
                List<StreamingLocatorContentKey> keys = locator.contentKeys()
                        .stream()
                        .filter(k -> k.type() == StreamingLocatorContentKeyType.COMMON_ENCRYPTION_CENC)
                        .collect(Collectors.toList());
                if (keys.size() > 0) {
                    keyIdentifier = keys.get(0).id().toString();
                }

                System.out.println("KeyIdentifier = " + keyIdentifier);

                // In order to generate our test token we must get the ContentKeyId to put in the ContentKeyIdentifierClaim claim.
                String token = createToken(ISSUER, AUDIENCE, keyIdentifier, TOKEN_SIGNING_KEY);

                StreamingEndpoint streamingEndpoint = manager.streamingEndpoints()
                        .getAsync(config.getResourceGroup(), config.getAccountName(), DEFAULT_STREAMING_ENDPOINT_NAME)
                        .toBlocking().first();

                if (streamingEndpoint != null) {
                    // Start The Streaming Endpoint if it is not running.
                    if (streamingEndpoint.resourceState() != StreamingEndpointResourceState.RUNNING) {
                        manager.streamingEndpoints().startAsync(config.getResourceGroup(), config.getAccountName(), DEFAULT_STREAMING_ENDPOINT_NAME).await();

                        // We started the endpoint, we should stop it in cleanup.
                        stopEndpoint = true;
                    }

                    String dashPath = getDASHStreamingUrl(manager, config.getResourceGroup(), config.getAccountName(), locator.name(), streamingEndpoint);

                    System.out.println();
                    System.out.println("Copy and paste the following URL in your browser to play back the file in the Azure Media Player.");
                    System.out.println("You can use Edge/IE11 for PlayReady.");
                    System.out.println();

                    System.out.println("https://ampdemo.azureedge.net/?url=" + dashPath + "&playready=true&token=Bearer%3D" + token);
                    System.out.println();
                } else {
                    System.out.println("Could not find streaming endpoint: " + DEFAULT_STREAMING_ENDPOINT_NAME);
                }

                System.out.println("When finished testing press ENTER to cleanup.");
                System.out.flush();
                scanner.nextLine();
            }
        } catch (Exception e) {
            System.out.println(e);
            e.printStackTrace();
        } finally {
            System.out.println("Cleaning up...");
            if (scanner != null) {
                scanner.close();
            }

            if (eventProcessorHost != null) {
                eventProcessorHost.stop();
                eventProcessorHost = null;
            }

            cleanup(manager, config.getResourceGroup(), config.getAccountName(), ADAPTIVE_STREAMING_TRANSFORM_NAME, jobName,
                    outputAssetName, locatorName, CONTENT_KEY_POLICY_NAME, stopEndpoint, DEFAULT_STREAMING_ENDPOINT_NAME);
        }
    }

    /**
     * If the specified transform exists, get that transform. If the it does not
     * exist, creates a new transform with the specified output. In this case, the
     * output is set to encode a video using one of the built-in encoding presets.
     *
     * @param manager       The entry point of Azure Media resource management.
     * @param resourceGroup The name of the resource group within the Azure subscription.
     * @param accountName   The Media Services account name.
     * @param transformName The name of the transform.
     * @return The transform found or created.
     */
    private static Transform getOrCreateTransform(MediaManager manager, String resourceGroup, String accountName,
                                                  String transformName) {
        Transform transform;
        try {
            // Does a Transform already exist with the desired name? Assume that an existing
            // Transform with the desired name.
            transform = manager.transforms().getAsync(resourceGroup, accountName, transformName).toBlocking().first();
        } catch (NoSuchElementException e) {
            transform = null; // Media Services V3 throws an exception when not found.
        }

        if (transform == null) {
            // Start by defining the desired outputs.
            BuiltInStandardEncoderPreset preset = new BuiltInStandardEncoderPreset()
                    .withPresetName(EncoderNamedPreset.ADAPTIVE_STREAMING);
            TransformOutput transformOutput = new TransformOutput().withPreset(preset);
            List<TransformOutput> outputs = new ArrayList<TransformOutput>();
            outputs.add(transformOutput);

            // Create the Transform with the output defined above.
            System.out.println("Creating a transform...");
            transform = manager.transforms().define(transformName).withExistingMediaservice(resourceGroup, accountName)
                    .withOutputs(outputs).create();
        }

        return transform;
    }

    /**
     * Creates an output asset. The output from the encoding Job must be written to
     * an Asset.
     *
     * @param manager           This is the entry point of Azure Media resource management.
     * @param resourceGroupName The name of the resource group within the Azure subscription.
     * @param accountName       The Media Services account name.
     * @param assetName         The output asset name.
     * @return The output asset created.
     */
    private static Asset createOutputAsset(MediaManager manager, String resourceGroupName, String accountName,
                                           String assetName) {
        // We are assuming the asset name is unique.
        System.out.println("Creating an output asset...");
        Asset outputAsset = manager.assets().define(assetName).withExistingMediaservice(resourceGroupName, accountName)
                .create();

        return outputAsset;
    }

    /**
     * Submits a request to Media Services to apply the specified Transform to a
     * given input video.
     *
     * @param manager           This is the entry point of Azure Media resource management.
     * @param resourceGroupName The name of the resource group within the Azure subscription.
     * @param accountName       The Media Services account name.
     * @param transformName     The name of the transform.
     * @param outputAssetName   The (unique) name of the output asset that will
     *                          store the result of the encoding job.
     * @param jobName           The (unique) name of the job.
     * @return The job created.
     */
    private static Job submitJob(MediaManager manager, String resourceGroupName, String accountName,
                                 String transformName, String outputAssetName, String jobName) {
        // This example shows how to encode from any HTTPs source URL - a new feature of the v3 API.
        // Change the URL to any accessible HTTPs URL or SAS URL from Azure.
        List<String> files = new ArrayList<>();
        files.add(MP4_FILE_NAME);
        JobInputHttp jobInput = new JobInputHttp().withBaseUri(BASE_URI);
        jobInput.withFiles(files);

        JobOutput output = new JobOutputAsset().withAssetName(outputAssetName);
        List<JobOutput> jobOutputs = new ArrayList<>();
        jobOutputs.add(output);

        // In this example, we are assuming that the job name is unique.
        // If you already have a job with the desired name, use the Jobs.Get method
        // to get the existing job. In Media Services v3, the Get method on entities returns null
        // if the entity doesn't exist (a case-insensitive check on the name).
        System.out.println("Creating a job...");
        Job job = manager.jobs().define(jobName).withExistingTransform(resourceGroupName, accountName, transformName)
                .withInput(jobInput).withOutputs(jobOutputs).create();

        return job;
    }

    /**
     * Polls Media Services for the status of the Job.
     *
     * @param manager       This is the entry point of Azure Media resource management.
     * @param resourceGroup The name of the resource group within the Azure subscription.
     * @param accountName   The Media Services account name.
     * @param transformName The name of the transform.
     * @param jobName       The name of the job you submitted.
     * @return The job.
     */
    private static Job waitForJobToFinish(MediaManager manager, String resourceGroup, String accountName,
                                          String transformName, String jobName) {
        final int SLEEP_INTERVAL = 60 * 1000;

        Job job = null;
        boolean exit = false;

        do {
            job = manager.jobs().getAsync(resourceGroup, accountName, transformName, jobName).toBlocking().first();

            if (job.state() == JobState.FINISHED || job.state() == JobState.ERROR || job.state() == JobState.CANCELED) {
                exit = true;
            } else {
                System.out.println("Job is " + job.state());

                int i = 0;
                for (JobOutput output : job.outputs()) {
                    System.out.print("\tJobOutput[" + i++ + "] is " + output.state() + ".");
                    if (output.state() == JobState.PROCESSING) {
                        System.out.print("  Progress: " + output.progress());
                    }
                    System.out.println();
                }

                try {
                    Thread.sleep(SLEEP_INTERVAL);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        } while (!exit);

        return job;
    }

    /**
     * Create the content key policy that configures how the content key is delivered
     * to end clients via the Key Delivery component of Azure Media Services.
     *
     * @param manager              The entry point of Azure Media resource management.
     * @param resourceGroup        The name of the resource group within the Azure subscription.
     * @param accountName          The Media Services account name.
     * @param contentKeyPolicyName The name of the content key policy resource.
     * @return The content key policy.
     * @throws JsonProcessingException
     */
    private static ContentKeyPolicy ensureContentKeyPolicyExists(MediaManager manager, String resourceGroup,
                                                                 String accountName, String contentKeyPolicyName) throws JsonProcessingException {
        ContentKeyPolicy policy;
        try {
            // Get the policy if exists.
            policy = manager.contentKeyPolicies().getAsync(resourceGroup, accountName, contentKeyPolicyName)
                    .toBlocking().first();
        } catch (NoSuchElementException e) {
            policy = null;
        }

        if (policy == null) {
            // We use the key defined in the configuration file.
            ContentKeyPolicySymmetricTokenKey primaryKey = new ContentKeyPolicySymmetricTokenKey()
                    .withKeyValue(TOKEN_SIGNING_KEY);
            List<ContentKeyPolicyRestrictionTokenKey> alternateKeys = null;

            // Add content key identifier claim. This claim requires that the value of the claim in the token
            // must match the key identifier of the key being requested by the client. Adding this claim means
            // that the token issued to the client authorizes access to the content key identifier
            // listed in the token.
            List<ContentKeyPolicyTokenClaim> requiredClaims = new ArrayList<>();
            requiredClaims.add(new ContentKeyPolicyTokenClaim().withClaimType(CONTENT_KEY_IDENTIFIER_CLAIM));

            // Issuer:
            //   The token issuer.
            //
            // Audience:
            //   The audience for the token.
            //
            // PrimaryVerificationKey:
            //   The primary verification key.
            //
            // RestrictionTokenType:
            //   The type of token. Possible values include: 'Unknown', 'Swt', 'Jwt'
            //
            // AlternateVerificationKeys:
            //   A list of alternative verification keys.
            //
            // RequiredClaims:
            //   A list of required token claims.
            ContentKeyPolicyTokenRestriction restriction = new ContentKeyPolicyTokenRestriction()
                    .withIssuer(ISSUER)
                    .withAudience(AUDIENCE)
                    .withPrimaryVerificationKey(primaryKey)
                    .withRestrictionTokenType(ContentKeyPolicyRestrictionTokenType.JWT)
                    .withAlternateVerificationKeys(alternateKeys)
                    .withRequiredClaims(requiredClaims);

            // Create a configuration for PlayReady licenses.
            ContentKeyPolicyPlayReadyConfiguration playReadyConfig = configurePlayReadyLicenseTemplate();

            List<ContentKeyPolicyOption> options = new ArrayList<>();

            options.add(new ContentKeyPolicyOption()
                    .withConfiguration(playReadyConfig)
                    .withRestriction(restriction));

            // Content Key Policy does not exist, create one.
            policy = manager.contentKeyPolicies().define(contentKeyPolicyName)
                    .withExistingMediaservice(resourceGroup, accountName).withOptions(options).create();
        } else {
            // Get the signing key from the existing policy.
            ContentKeyPolicyProperties policyProperties = manager.contentKeyPolicies()
                    .getPolicyPropertiesWithSecretsAsync(resourceGroup, accountName, contentKeyPolicyName).toBlocking().first();
            ContentKeyPolicyRestriction restriction = policyProperties.options().get(0).restriction();
            if (restriction != null && restriction instanceof ContentKeyPolicyTokenRestriction) {
                ContentKeyPolicyTokenRestriction contentKeyPolicyTokenRestriction = (ContentKeyPolicyTokenRestriction) restriction;
                ContentKeyPolicySymmetricTokenKey signingKey = (ContentKeyPolicySymmetricTokenKey) contentKeyPolicyTokenRestriction.primaryVerificationKey();
                if (signingKey != null) {
                    TOKEN_SIGNING_KEY = signingKey.keyValue();
                }
            }
        }

        return policy;
    }

    /**
     * Configures PlayReady license template.
     *
     * @return ContentKeyPolicyPlayReadyConfiguration
     */
    private static ContentKeyPolicyPlayReadyConfiguration configurePlayReadyLicenseTemplate() {
        ContentKeyPolicyPlayReadyLicense objContentKeyPolicyPlayReadyLicense = new ContentKeyPolicyPlayReadyLicense()
                .withAllowTestDevices(true)
                .withContentKeyLocation(new ContentKeyPolicyPlayReadyContentEncryptionKeyFromHeader())
                .withContentType(ContentKeyPolicyPlayReadyContentType.ULTRA_VIOLET_STREAMING)
                .withLicenseType(ContentKeyPolicyPlayReadyLicenseType.NON_PERSISTENT)
                .withPlayRight(new ContentKeyPolicyPlayReadyPlayRight()
                        .withImageConstraintForAnalogComponentVideoRestriction(true)
                        .withExplicitAnalogTelevisionOutputRestriction(
                                new ContentKeyPolicyPlayReadyExplicitAnalogTelevisionRestriction()
                                        .withBestEffort(true)
                                        .withConfigurationData(2))
                        .withAllowPassingVideoContentToUnknownOutput(ContentKeyPolicyPlayReadyUnknownOutputPassingOption.ALLOWED));

        List<ContentKeyPolicyPlayReadyLicense> licenses = new ArrayList<>();
        licenses.add(objContentKeyPolicyPlayReadyLicense);

        ContentKeyPolicyPlayReadyConfiguration objContentKeyPolicyPlayReadyConfiguration = new ContentKeyPolicyPlayReadyConfiguration()
                .withLicenses(licenses);

        return objContentKeyPolicyPlayReadyConfiguration;
    }

    /**
     * Create a token that will be used to protect your stream.
     * Only authorized clients would be able to play the video.
     *
     * @param issuer               The issuer is the secure token service that issues the token.
     * @param audience             The audience, sometimes called scope, describes the intent of the token or the resource the token authorizes access to.
     * @param keyIdentifier        The content key ID.
     * @param tokenVerificationKey Contains the key that the token was signed with.
     * @return The token.
     * @throws JwtException
     */
    private static String createToken(String issuer, String audience, String keyIdentifier,
                                      byte[] tokenVerificationKey) throws JwtException {

        String jws = null;
        SecretKey key = Keys.hmacShaKeyFor(tokenVerificationKey);

        JwtBuilder builder = Jwts.builder()
                .setIssuer(issuer)
                .setAudience(audience)
                .claim(CONTENT_KEY_IDENTIFIER_CLAIM, keyIdentifier)
                .setNotBefore(Date.from(LocalDateTime.now().minusMinutes(5).atZone(ZoneId.systemDefault()).toInstant()))
                .setExpiration(Date.from(LocalDateTime.now().plusMinutes(60).atZone(ZoneId.systemDefault()).toInstant()))
                .signWith(key, SignatureAlgorithm.HS256);

        jws = builder.compact();

        return jws;
    }

    /**
     * Checks if the streaming endpoint is in the running state, if not, starts it.
     * Then, builds the streaming URLs.
     *
     * @param manager           The entry point of Azure Media resource management.
     * @param resourceGroup     The name of the resource group within the Azure subscription.
     * @param accountName       The Media Services account name.
     * @param locatorName       The name of the StreamingLocator that was created.
     * @param streamingEndpoint The streaming endpoint.
     * @return DASH url.
     */
    private static String getDASHStreamingUrl(MediaManager manager, String resourceGroup, String accountName, String locatorName, StreamingEndpoint streamingEndpoint) {
        String dashPath = "";

        ListPathsResponse paths = manager.streamingLocators()
                .listPathsAsync(resourceGroup, accountName, locatorName)
                .toBlocking().first();

        for (StreamingPath path : paths.streamingPaths()) {
            if (path.paths().size() > 0) {
                StringBuilder uriBuilder = new StringBuilder();
                uriBuilder.append("https://").append(streamingEndpoint.hostName());

                // Look for just the DASH path and generate a URL for the Azure Media Player to playback the content with the AES token to decrypt.
                // Note that the JWT token is set to expire in 1 hour.
                if (path.streamingProtocol() == StreamingPolicyStreamingProtocol.DASH) {
                    uriBuilder.append("/").append(path.paths().get(0));
                    dashPath = uriBuilder.toString();
                }
            }
        }

        return dashPath;
    }

    /**
     * Deletes the jobs and assets that were created.
     * Generally, you should clean up everything except objects
     * that you are planning to reuse (typically, you will reuse Transforms, and you will persist StreamingLocators).
     *
     * @param manager               The entry point of Azure Media resource management.
     * @param resourceGroup         The name of the resource group within the Azure subscription.
     * @param accountName           The Media Services account name.
     * @param transformName         The transform name.
     * @param jobName               The job name.
     * @param assetName             The asset name.
     * @param locatorName           The name of the StreamingLocator that was created.
     * @param contentKeyPolicyName  The content key policy name.
     * @param stopEndpoint          Stop endpoint if true, otherwise keep endpoint running.
     * @param streamingEndpointName The endpoint name.
     */
    public static void cleanup(MediaManager manager, String resourceGroup, String accountName, String transformName, String jobName,
                               String assetName, String locatorName, String contentKeyPolicyName, boolean stopEndpoint, String streamingEndpointName) {
        if (manager == null) {
            return;
        }

        manager.jobs().deleteAsync(resourceGroup, accountName, transformName, jobName).await();
        manager.assets().deleteAsync(resourceGroup, accountName, assetName).await();

        manager.streamingLocators().deleteAsync(resourceGroup, accountName, locatorName).await();
        manager.contentKeyPolicies().deleteAsync(resourceGroup, accountName, contentKeyPolicyName).await();

        if (stopEndpoint) {
            // Because we started the endpoint, we'll stop it.
            manager.streamingEndpoints().stopAsync(resourceGroup, accountName, streamingEndpointName).await();
        } else {
            // We will keep the endpoint running because it was not started by this sample. Please note, There are costs to keep it running.
            // Please refer https://azure.microsoft.com/en-us/pricing/details/media-services/ for pricing.
            System.out.println("The endpoint ''" + streamingEndpointName + "'' is running. To halt further billing on the endpoint, please stop it in azure portal or AMS Explorer.");
        }
    }
}
