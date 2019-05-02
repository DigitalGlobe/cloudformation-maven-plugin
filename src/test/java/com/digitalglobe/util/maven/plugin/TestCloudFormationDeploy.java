package com.digitalglobe.util.maven.plugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.amazonaws.services.cloudformation.model.*;
import org.apache.maven.plugin.MojoExecutionException;
import org.testng.Assert;
import org.testng.annotations.Test;

import com.amazonaws.AmazonServiceException;
import com.amazonaws.AmazonWebServiceRequest;
import com.amazonaws.HttpMethod;
import com.amazonaws.ResponseMetadata;
import com.amazonaws.SdkClientException;
import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.BasicSessionCredentials;
import com.amazonaws.regions.Region;
import com.amazonaws.services.cloudformation.AmazonCloudFormation;
import com.amazonaws.services.cloudformation.waiters.AmazonCloudFormationWaiters;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.S3ClientOptions;
import com.amazonaws.services.s3.S3ResponseMetadata;
import com.amazonaws.services.s3.model.*;
import com.amazonaws.services.s3.model.analytics.AnalyticsConfiguration;
import com.amazonaws.services.s3.model.inventory.InventoryConfiguration;
import com.amazonaws.services.s3.model.metrics.MetricsConfiguration;
import com.amazonaws.services.s3.waiters.AmazonS3Waiters;
import com.amazonaws.services.securitytoken.AWSSecurityTokenService;
import com.amazonaws.services.securitytoken.model.AssumeRoleRequest;
import com.amazonaws.services.securitytoken.model.AssumeRoleResult;
import com.amazonaws.services.securitytoken.model.AssumeRoleWithSAMLRequest;
import com.amazonaws.services.securitytoken.model.AssumeRoleWithSAMLResult;
import com.amazonaws.services.securitytoken.model.AssumeRoleWithWebIdentityRequest;
import com.amazonaws.services.securitytoken.model.AssumeRoleWithWebIdentityResult;
import com.amazonaws.services.securitytoken.model.Credentials;
import com.amazonaws.services.securitytoken.model.DecodeAuthorizationMessageRequest;
import com.amazonaws.services.securitytoken.model.DecodeAuthorizationMessageResult;
import com.amazonaws.services.securitytoken.model.GetCallerIdentityRequest;
import com.amazonaws.services.securitytoken.model.GetCallerIdentityResult;
import com.amazonaws.services.securitytoken.model.GetFederationTokenRequest;
import com.amazonaws.services.securitytoken.model.GetFederationTokenResult;
import com.amazonaws.services.securitytoken.model.GetSessionTokenRequest;
import com.amazonaws.services.securitytoken.model.GetSessionTokenResult;
import com.amazonaws.waiters.Waiter;
import com.amazonaws.waiters.WaiterHandler;
import com.amazonaws.waiters.WaiterParameters;
import com.amazonaws.waiters.WaiterTimedOutException;
import com.amazonaws.waiters.WaiterUnrecoverableException;

/**
 * Created by Michael Leedahl on 6/23/17.
 *
 * Use this class to test the deploy of a CloudFormation Stack
 */
public class TestCloudFormationDeploy {

    /**
     * Use to override some plugin methods for mocking purposes and to mock the Amazon Security Token Service.  In
     * this mocking scenario, this method overrides the S3 and STS Client Builders as well as initialize the test
     * of the execute function.  Then it invokes the execute function of the plugin for unit testing.
     */
    static private class OverridePlugin extends CloudFormationDeployMavenPlugin implements AWSSecurityTokenService {

        static {stsBuilder = OverridePlugin.class;}
        static {s3Builder = s3Client.class;}
        static {cfBuilder = cfClient.class;}

        static public AWSSecurityTokenService defaultClient() {

            return new OverridePlugin();
        }

        /**
         * Use this method to mock assuming a role.
         *
         * @param assumeRoleRequest contains information about the role to mock.
         * @return a result that contains the new set of credentials to use in the mock.
         */
        @Override
        public AssumeRoleResult assumeRole(AssumeRoleRequest assumeRoleRequest) {

            Assert.assertTrue(assumeRoleRequest.getRoleArn().equals("aws:iam::1111:role/test"));

            return new AssumeRoleResult()
                    .withCredentials(new Credentials()
                            .withAccessKeyId("A")
                            .withSecretAccessKey("B")
                            .withSessionToken("C")
                            .withExpiration(new Date(LocalDateTime.now()
                                    .plusHours(1)
                                    .toEpochSecond(ZoneOffset.UTC))));
        }

        /**
         * Use this method to begin the test against the mocked class.
         */
        private Exception beginTesting(String templateS3Bucket, String roleArn, String s3Bucket, String repository,
                                       String groupId, String artifactId, String version,
                                       String stackName, String stackPath, String[] parameterPath,
                                       CloudFormationDeployMavenPlugin.StackGroup[] secondaryStackGroups,
                                       CloudFormationDeployMavenPlugin.StackInputParameter[] inputParameters,
                                       CloudFormationDeployMavenPlugin.CliCommandOutputParameterMapping[] mappings) {

            Exception ex = null;

            try {

                setField("templateS3Bucket", templateS3Bucket);
                setField("outputDirectory", new File("target"));
                if(roleArn != null) setField("roleArn", roleArn);
                setField("s3Bucket", s3Bucket);
                if(repository != null) setField("repositoryPath", repository);
                setField("groupId", groupId);
                setField("artifactId", artifactId);
                setField("version", version);
                setField("stackName", stackName);
                setField("stackPath", stackPath);
                setField("stackParameterFilePaths", parameterPath);
                setField("secondaryStackGroups", secondaryStackGroups);
                setField("inputParameters", inputParameters);
                setField("cliCommandOutputParameterMappings", mappings);

                this.execute();

            } catch(MojoExecutionException meEx) {

                ex = meEx;

            } catch(NoSuchFieldException nsfEx) {

                ex = nsfEx;

            } catch(IllegalAccessException iaEx) {

                ex = iaEx;
            }

            return ex;
        }

        /**
         * Use this method to set a filed value with reflection.
         *
         * @param name is the name of the field to set.
         * @param value is the value to set the field with.
         * @throws NoSuchFieldException
         * @throws IllegalAccessException
         */
        private void setField(String name, Object value)
                throws NoSuchFieldException, IllegalAccessException {

            Class plugin = this.getClass().getSuperclass();

            Field roleArnParam = plugin.getDeclaredField(name);
            roleArnParam.setAccessible(true);
            roleArnParam.set(this, value);
        }

        //#region The rest of the methods are not part of this mocking scenario
        @Override
        @Deprecated
        public void setEndpoint(String s) {

        }

        @Override
        @Deprecated
        public void setRegion(Region region) {

        }

        @Override
        public AssumeRoleWithSAMLResult assumeRoleWithSAML(AssumeRoleWithSAMLRequest assumeRoleWithSAMLRequest) {
            return null;
        }

        @Override
        public AssumeRoleWithWebIdentityResult assumeRoleWithWebIdentity(AssumeRoleWithWebIdentityRequest assumeRoleWithWebIdentityRequest) {
            return null;
        }

        @Override
        public DecodeAuthorizationMessageResult decodeAuthorizationMessage(DecodeAuthorizationMessageRequest decodeAuthorizationMessageRequest) {
            return null;
        }

        @Override
        public GetCallerIdentityResult getCallerIdentity(GetCallerIdentityRequest getCallerIdentityRequest) {
            return null;
        }

        @Override
        public GetFederationTokenResult getFederationToken(GetFederationTokenRequest getFederationTokenRequest) {
            return null;
        }

        @Override
        public GetSessionTokenResult getSessionToken(GetSessionTokenRequest getSessionTokenRequest) {
            return null;
        }

        @Override
        public GetSessionTokenResult getSessionToken() {
            return null;
        }

        @Override
        public void shutdown() {

        }

        @Override
        public ResponseMetadata getCachedResponseMetadata(AmazonWebServiceRequest amazonWebServiceRequest) {
            return null;
        }
        //#endregion
    }

    /**
     * Use this method in the test to set the current test scenario for the cloud formation tests.  Create test equal
     * to true will indicate that the test should throw and exception when describing stacks.  Set to false it
     * indicates that the stack exists and the test returns a stack description.
     */
    static private class StackScenario {

        static boolean createTest = true;
        static boolean createTestComplete = false;
        static boolean changeset = true;
    }

    /**
     * Use to mock the cloud formation client and client builder.  In this mock scenario, a cloud formation template
     * is used to create or update a new stack.  The stack defines the infrastructure needed for the jar that will
     * be deployed.  The cloud formation stack also deploys the jar into this infrastructure.
     */
    static public class cfClient implements AmazonCloudFormation {

        //#region Mocking methods for CloudFormation Client Builder
        private AWSCredentialsProvider credentials = null;

        /**
         * Use to mock the setting of credentials.
         *
         * @param credentials are the credentials to create the mock client with.
         * @return a reference to this instance for initialization chaining.
         */
        public cfClient withCredentials(AWSCredentialsProvider credentials) {

            Assert.assertTrue(credentials.getCredentials().getAWSAccessKeyId().equals("A"));
            Assert.assertTrue(credentials.getCredentials().getAWSSecretKey().equals("B"));
            Assert.assertTrue(((BasicSessionCredentials)credentials.getCredentials()).getSessionToken().equals("C"));

            this.credentials = credentials;
            return this;
        }

        static public AmazonCloudFormation defaultClient() {

            return new cfClient();
        }

        static public cfClient standard() {

            return new cfClient();
        }

        public AmazonCloudFormation build() {

            return this;
        }
        //#endregion

        /**
         * Use this method to determine if a stack exists and to get information about the stack for this mocking
         * scenario.  The mock of this method validates that you are looking for the stack in this mock.  It uses
         * a static class with with a create flag to signal if the current test is testing create or update.  In the
         * create scenario it will throw an exception indicating that the stack doesn't exist.  In the update
         * scenario it will return a result object.
         *
         * @param describeStacksRequest is a request containing the name of the stack to lookup.
         * @return a result indicating the properties of the existing stack.
         */
        @Override
        public DescribeStacksResult describeStacks(DescribeStacksRequest describeStacksRequest) {

            if(StackScenario.createTest && !StackScenario.createTestComplete)
                throw new AmazonCloudFormationException("Stack doesn't exist.");

            Collection<Output> outputs = new ArrayList<>();
            Output output = new Output().withOutputKey("HELLO").withOutputValue("World");
            outputs.add(output);

            Stack stack = new Stack();
            stack.setOutputs(outputs);
            stack.setStackName("Test");

            ArrayList<Stack> stacks = new ArrayList<>();
            stacks.add(stack);

            return new DescribeStacksResult().withStacks(stacks);
        }

        /**
         * Use this method to mock the creation of a new cloud formation stack.  The mocking scenario validates
         * the template name and the stack name.  It returns a blank result.
         *
         * @param createStackRequest contains a request describing the stack to deploy.
         * @return a result of the stack creation.
         */
        @Override
        public CreateStackResult createStack(CreateStackRequest createStackRequest) {

            Assert.assertTrue(createStackRequest.getStackName().equals("Test"));

            return new CreateStackResult()
                    .withStackId("arn:aws:cloudformation:us-east-1:1111:stack/Test/" +
                            UUID.randomUUID().toString());
        }

        /**
         * Use this method to mock the update of a cloud formation stack.  This mocking scenario validates the
         * template name and stack name.  It returns a blank result.
         *
         * @param updateStackRequest contains the request to update a stack.
         * @return the result of the update operation.
         */
        @Override
        public UpdateStackResult updateStack(UpdateStackRequest updateStackRequest) {

            Assert.assertTrue(updateStackRequest.getStackName().equals("Test"));

            return new UpdateStackResult()
                    .withStackId("arn:aws:cloudformation:us-east-1:1111:stack/Test/" +
                            UUID.randomUUID().toString());
        }

        /**
         * Use this method to return mock waiter to pretend to wait for the cloud formation operation to finish.
         *
         * @return a mock waiter instance.
         */
        @Override
        public AmazonCloudFormationWaiters waiters() {

            return StackScenario.createTest ? MockWaiter.defaultClient() : StackScenario.changeset ?
                    MockWaiterChangeSet.defaultClient() : MockWaiter.defaultClient();
        }

        /**
         * Use this method to mock creating a change set.  In this scenario we validate that the request is for the
         * stack we want to update.  It returns a blank response.
         *
         * @param createChangeSetRequest contains the request to create a change set.
         * @return a result of initiating the creation of the change set.
         */
        @Override
        public CreateChangeSetResult createChangeSet(CreateChangeSetRequest createChangeSetRequest) {

            Assert.assertTrue(createChangeSetRequest.getStackName().equals("Test"));
            Assert.assertTrue(createChangeSetRequest.getChangeSetType().equals("UPDATE"));

            return new CreateChangeSetResult().withStackId("arn:aws:cloudformation:us-east-1:1111:stack/Test/" +
                    UUID.randomUUID().toString());
        }

        /**
         * Use this method to mock the retrieval of the status of a change set.  This scenario returns 1 change
         * without any details.
         *
         * @param describeChangeSetRequest contains the request to describe a change set request.
         * @return a response with the status of a change set request.
         */
        @Override
        public DescribeChangeSetResult describeChangeSet(DescribeChangeSetRequest describeChangeSetRequest) {

            Assert.assertTrue(describeChangeSetRequest.getStackName().equals("Test"));

            Collection<Change> changes = new ArrayList<>();
            Change change = new Change();
            changes.add(change);

            return new DescribeChangeSetResult()
                    .withChanges(changes)
                    .withStatus("CREATE_COMPLETE")
                    .withStackId("arn:aws:cloudformation:us-east-1:1111:stack/Test/" +
                            UUID.randomUUID().toString());
        }

        //#region Methods not used in this mocking scenario.
        @Override
        @Deprecated
        public void setEndpoint(String s) {

        }

        @Override
        @Deprecated
        public void setRegion(Region region) {

        }

        @Override
        public CancelUpdateStackResult cancelUpdateStack(CancelUpdateStackRequest cancelUpdateStackRequest) {
            return null;
        }

        @Override
        public ContinueUpdateRollbackResult continueUpdateRollback(ContinueUpdateRollbackRequest continueUpdateRollbackRequest) {
            return null;
        }

        @Override
        public CreateStackInstancesResult createStackInstances(CreateStackInstancesRequest createStackInstancesRequest) {
            return null;
        }

        @Override
        public CreateStackSetResult createStackSet(CreateStackSetRequest createStackSetRequest) {
            return null;
        }

        @Override
        public DeleteChangeSetResult deleteChangeSet(DeleteChangeSetRequest deleteChangeSetRequest) {
            return null;
        }

        @Override
        public DeleteStackResult deleteStack(DeleteStackRequest deleteStackRequest) {
            return null;
        }

        @Override
        public DeleteStackInstancesResult deleteStackInstances(DeleteStackInstancesRequest deleteStackInstancesRequest) {
            return null;
        }

        @Override
        public DeleteStackSetResult deleteStackSet(DeleteStackSetRequest deleteStackSetRequest) {
            return null;
        }

        @Override
        public DescribeAccountLimitsResult describeAccountLimits(DescribeAccountLimitsRequest describeAccountLimitsRequest) {
            return null;
        }

        @Override
        public DescribeStackEventsResult describeStackEvents(DescribeStackEventsRequest describeStackEventsRequest) {
            return null;
        }

        @Override
        public DescribeStackInstanceResult describeStackInstance(DescribeStackInstanceRequest describeStackInstanceRequest) {
            return null;
        }

        @Override
        public DescribeStackResourceResult describeStackResource(DescribeStackResourceRequest describeStackResourceRequest) {
            return null;
        }

        @Override
        public DescribeStackResourcesResult describeStackResources(DescribeStackResourcesRequest describeStackResourcesRequest) {
            return null;
        }

        @Override
        public DescribeStackSetResult describeStackSet(DescribeStackSetRequest describeStackSetRequest) {
            return null;
        }

        @Override
        public DescribeStackSetOperationResult describeStackSetOperation(DescribeStackSetOperationRequest describeStackSetOperationRequest) {
            return null;
        }

        @Override
        public DescribeStacksResult describeStacks() {
            return null;
        }

        @Override
        public EstimateTemplateCostResult estimateTemplateCost(EstimateTemplateCostRequest estimateTemplateCostRequest) {
            return null;
        }

        @Override
        public EstimateTemplateCostResult estimateTemplateCost() {
            return null;
        }

        @Override
        public ExecuteChangeSetResult executeChangeSet(ExecuteChangeSetRequest executeChangeSetRequest) {
            return null;
        }

        @Override
        public GetStackPolicyResult getStackPolicy(GetStackPolicyRequest getStackPolicyRequest) {
            return null;
        }

        @Override
        public GetTemplateResult getTemplate(GetTemplateRequest getTemplateRequest) {
            return null;
        }

        @Override
        public GetTemplateSummaryResult getTemplateSummary(GetTemplateSummaryRequest getTemplateSummaryRequest) {
            return null;
        }

        @Override
        public GetTemplateSummaryResult getTemplateSummary() {
            return null;
        }

        @Override
        public ListChangeSetsResult listChangeSets(ListChangeSetsRequest listChangeSetsRequest) {
            return null;
        }

        @Override
        public ListExportsResult listExports(ListExportsRequest listExportsRequest) {
            return null;
        }

        @Override
        public ListImportsResult listImports(ListImportsRequest listImportsRequest) {
            return null;
        }

        @Override
        public ListStackInstancesResult listStackInstances(ListStackInstancesRequest listStackInstancesRequest) {
            return null;
        }

        @Override
        public ListStackResourcesResult listStackResources(ListStackResourcesRequest listStackResourcesRequest) {
            return null;
        }

        @Override
        public ListStackSetOperationResultsResult listStackSetOperationResults(ListStackSetOperationResultsRequest listStackSetOperationResultsRequest) {
            return null;
        }

        @Override
        public ListStackSetOperationsResult listStackSetOperations(ListStackSetOperationsRequest listStackSetOperationsRequest) {
            return null;
        }

        @Override
        public ListStackSetsResult listStackSets(ListStackSetsRequest listStackSetsRequest) {
            return null;
        }

        @Override
        public ListStacksResult listStacks(ListStacksRequest listStacksRequest) {
            return null;
        }

        @Override
        public ListStacksResult listStacks() {
            return null;
        }

        @Override
        public SetStackPolicyResult setStackPolicy(SetStackPolicyRequest setStackPolicyRequest) {
            return null;
        }

        @Override
        public SignalResourceResult signalResource(SignalResourceRequest signalResourceRequest) {
            return null;
        }

        @Override
        public StopStackSetOperationResult stopStackSetOperation(StopStackSetOperationRequest stopStackSetOperationRequest) {
            return null;
        }

        @Override
        public UpdateStackSetResult updateStackSet(UpdateStackSetRequest updateStackSetRequest) {
            return null;
        }

        @Override
        public ValidateTemplateResult validateTemplate(ValidateTemplateRequest validateTemplateRequest) {
            return null;
        }

        @Override
        public void shutdown() {

        }

        @Override
        public ResponseMetadata getCachedResponseMetadata(AmazonWebServiceRequest amazonWebServiceRequest) {
            return null;
        }

        @Override
        public UpdateStackInstancesResult updateStackInstances(UpdateStackInstancesRequest updateStackInstancesRequest) {
            return null;
        }

        @Override
        public UpdateTerminationProtectionResult updateTerminationProtection(UpdateTerminationProtectionRequest updateTerminationProtectionRequest) {
            return null;
        }

        @Override
        public DescribeStackDriftDetectionStatusResult describeStackDriftDetectionStatus(DescribeStackDriftDetectionStatusRequest describeStackDriftDetectionStatusRequest) {
            return null;
        }

        @Override
        public DescribeStackResourceDriftsResult describeStackResourceDrifts(DescribeStackResourceDriftsRequest describeStackResourceDriftsRequest) {
            return null;
        }

        @Override
        public DetectStackDriftResult detectStackDrift(DetectStackDriftRequest detectStackDriftRequest) {
            return null;
        }

        @Override
        public DetectStackResourceDriftResult detectStackResourceDrift(DetectStackResourceDriftRequest detectStackResourceDriftRequest) {
            return null;
        }
        //#endregion
    }

    /**
     * Use to mock the CloudFormation Waiters.  The waiter returns immediately in this mock.
     */
    private static class MockWaiter extends AmazonCloudFormationWaiters implements Waiter<DescribeStacksRequest> {

        private static AmazonCloudFormationWaiters defaultClient() {
            return new MockWaiter(cfClient.defaultClient());
        }

        private MockWaiter(AmazonCloudFormation client) {
            super(client);
        }

        @Override
        public void run(WaiterParameters<DescribeStacksRequest> waiterParameters) throws AmazonServiceException, WaiterTimedOutException, WaiterUnrecoverableException {

        }

        @Override
        public Future<Void> runAsync(WaiterParameters<DescribeStacksRequest> waiterParameters, WaiterHandler waiterHandler) throws AmazonServiceException, WaiterTimedOutException, WaiterUnrecoverableException {
            return null;
        }

        @Override
        public Waiter<DescribeStacksRequest> stackCreateComplete() {

            StackScenario.createTestComplete = true;
            return this;
        }

        @Override
        public Waiter<DescribeStacksRequest> stackUpdateComplete() {
            return this;
        }
    }

    /**
     * Use to mock the CloudFormation Waiters for Change Sets.  The waiter returns immediately in this mock.
     */
    private static class MockWaiterChangeSet extends AmazonCloudFormationWaiters
            implements Waiter<DescribeChangeSetRequest> {

        private static AmazonCloudFormationWaiters defaultClient() {
            return new MockWaiter(cfClient.defaultClient());
        }

        private MockWaiterChangeSet(AmazonCloudFormation client) {
            super(client);
        }

        @Override
        public void run(WaiterParameters<DescribeChangeSetRequest> waiterParameters)
                throws AmazonServiceException, WaiterTimedOutException, WaiterUnrecoverableException {

        }

        @Override
        public Future<Void> runAsync(WaiterParameters<DescribeChangeSetRequest> waiterParameters,
                                     WaiterHandler waiterHandler)
                throws AmazonServiceException, WaiterTimedOutException, WaiterUnrecoverableException {

            return null;
        }

        @Override
        public Waiter<DescribeChangeSetRequest> changeSetCreateComplete() {

            StackScenario.changeset = false;

            return this;
        }
    }

    /**
     * Use to mock an S3 Client and S3 Client Builder.  In this mock scenario, a jar file is sent to an S3 bucket so
     * that a CloudFormation Stack can reference the file from the S3 bucket and deploy it to a lambda.
     */
    static public class s3Client implements AmazonS3 {

        //#region Mocking methods for S3 Client Builder
        private AWSCredentialsProvider credentials = null;

        /**
         * Use to mock the setting of credentials.
         *
         * @param credentials are the credentials to create the mock client with.
         * @return a reference to this instance for initialization chaining.
         */
        public s3Client withCredentials(AWSCredentialsProvider credentials) {

            Assert.assertTrue(credentials.getCredentials().getAWSAccessKeyId().equals("A"));
            Assert.assertTrue(credentials.getCredentials().getAWSSecretKey().equals("B"));
            Assert.assertTrue(((BasicSessionCredentials)credentials.getCredentials()).getSessionToken().equals("C"));

            this.credentials = credentials;
            return this;
        }

        static public AmazonS3 defaultClient() {

            return new s3Client();
        }

        static public s3Client standard() {

            return new s3Client();
        }

        public AmazonS3 build() {

            return this;
        }
        //#endregion

        /**
         * Use this method to mock the sending of a file to s3.
         *
         * @param s is the bucket to put the file in.
         * @param s1 is the name of the file to put in the bucket.
         * @param file is the file to upload to the bucket.
         * @return a result object to indicate the mock has stored the file.
         * @throws SdkClientException
         * @throws AmazonServiceException
         */
        @Override
        public PutObjectResult putObject(String s, String s1, File file)
                throws SdkClientException, AmazonServiceException {

            Assert.assertTrue(s.equals("Test"));
            Assert.assertTrue(s1.equals("application-test-1.0.jar"));

            return new PutObjectResult();
        }

        //#region Methods not used in this mocking scenario.
        @Override
        public void setEndpoint(String s) {

        }

        @Override
        public void setRegion(Region region) throws IllegalArgumentException {

        }

        @Override
        public void setS3ClientOptions(S3ClientOptions s3ClientOptions) {

        }

        @Override
        @Deprecated
        public void changeObjectStorageClass(String s, String s1, StorageClass storageClass) throws SdkClientException, AmazonServiceException {

        }

        @Override
        @Deprecated
        public void setObjectRedirectLocation(String s, String s1, String s2) throws SdkClientException, AmazonServiceException {

        }

        @Override
        public ObjectListing listObjects(String s) throws SdkClientException, AmazonServiceException {
            return null;
        }

        @Override
        public ObjectListing listObjects(String s, String s1) throws SdkClientException, AmazonServiceException {
            return null;
        }

        @Override
        public ObjectListing listObjects(ListObjectsRequest listObjectsRequest) throws SdkClientException, AmazonServiceException {
            return null;
        }

        @Override
        public ListObjectsV2Result listObjectsV2(String s) throws SdkClientException, AmazonServiceException {
            return null;
        }

        @Override
        public ListObjectsV2Result listObjectsV2(String s, String s1) throws SdkClientException, AmazonServiceException {
            return null;
        }

        @Override
        public ListObjectsV2Result listObjectsV2(ListObjectsV2Request listObjectsV2Request) throws SdkClientException, AmazonServiceException {
            return null;
        }

        @Override
        public ObjectListing listNextBatchOfObjects(ObjectListing objectListing) throws SdkClientException, AmazonServiceException {
            return null;
        }

        @Override
        public ObjectListing listNextBatchOfObjects(ListNextBatchOfObjectsRequest listNextBatchOfObjectsRequest) throws SdkClientException, AmazonServiceException {
            return null;
        }

        @Override
        public VersionListing listVersions(String s, String s1) throws SdkClientException, AmazonServiceException {
            return null;
        }

        @Override
        public VersionListing listNextBatchOfVersions(VersionListing versionListing) throws SdkClientException, AmazonServiceException {
            return null;
        }

        @Override
        public VersionListing listNextBatchOfVersions(ListNextBatchOfVersionsRequest listNextBatchOfVersionsRequest) throws SdkClientException, AmazonServiceException {
            return null;
        }

        @Override
        public VersionListing listVersions(String s, String s1, String s2, String s3, String s4, Integer integer) throws SdkClientException, AmazonServiceException {
            return null;
        }

        @Override
        public VersionListing listVersions(ListVersionsRequest listVersionsRequest) throws SdkClientException, AmazonServiceException {
            return null;
        }

        @Override
        public Owner getS3AccountOwner() throws SdkClientException, AmazonServiceException {
            return null;
        }

        @Override
        public Owner getS3AccountOwner(GetS3AccountOwnerRequest getS3AccountOwnerRequest) throws SdkClientException, AmazonServiceException {
            return null;
        }

        @Override
        @Deprecated
        public boolean doesBucketExist(String s) throws SdkClientException, AmazonServiceException {
            return false;
        }

        @Override
        public boolean doesBucketExistV2(String s) throws SdkClientException, AmazonServiceException {
            return false;
        }

        @Override
        public HeadBucketResult headBucket(HeadBucketRequest headBucketRequest) throws SdkClientException, AmazonServiceException {
            return null;
        }

        @Override
        public List<Bucket> listBuckets() throws SdkClientException, AmazonServiceException {
            return null;
        }

        @Override
        public List<Bucket> listBuckets(ListBucketsRequest listBucketsRequest) throws SdkClientException, AmazonServiceException {
            return null;
        }

        @Override
        public String getBucketLocation(String s) throws SdkClientException, AmazonServiceException {
            return null;
        }

        @Override
        public String getBucketLocation(GetBucketLocationRequest getBucketLocationRequest) throws SdkClientException, AmazonServiceException {
            return null;
        }

        @Override
        public Bucket createBucket(CreateBucketRequest createBucketRequest) throws SdkClientException, AmazonServiceException {
            return null;
        }

        @Override
        public Bucket createBucket(String s) throws SdkClientException, AmazonServiceException {
            return null;
        }

        @Override
        @Deprecated
        public Bucket createBucket(String s, com.amazonaws.services.s3.model.Region region) throws SdkClientException, AmazonServiceException {
            return null;
        }

        @Override
        @Deprecated
        public Bucket createBucket(String s, String s1) throws SdkClientException, AmazonServiceException {
            return null;
        }

        @Override
        public AccessControlList getObjectAcl(String s, String s1) throws SdkClientException, AmazonServiceException {
            return null;
        }

        @Override
        public AccessControlList getObjectAcl(String s, String s1, String s2) throws SdkClientException, AmazonServiceException {
            return null;
        }

        @Override
        public AccessControlList getObjectAcl(GetObjectAclRequest getObjectAclRequest) throws SdkClientException, AmazonServiceException {
            return null;
        }

        @Override
        public void setObjectAcl(String s, String s1, AccessControlList accessControlList) throws SdkClientException, AmazonServiceException {

        }

        @Override
        public void setObjectAcl(String s, String s1, CannedAccessControlList cannedAccessControlList) throws SdkClientException, AmazonServiceException {

        }

        @Override
        public void setObjectAcl(String s, String s1, String s2, AccessControlList accessControlList) throws SdkClientException, AmazonServiceException {

        }

        @Override
        public void setObjectAcl(String s, String s1, String s2, CannedAccessControlList cannedAccessControlList) throws SdkClientException, AmazonServiceException {

        }

        @Override
        public void setObjectAcl(SetObjectAclRequest setObjectAclRequest) throws SdkClientException, AmazonServiceException {

        }

        @Override
        public AccessControlList getBucketAcl(String s) throws SdkClientException, AmazonServiceException {
            return null;
        }

        @Override
        public void setBucketAcl(SetBucketAclRequest setBucketAclRequest) throws SdkClientException, AmazonServiceException {

        }

        @Override
        public AccessControlList getBucketAcl(GetBucketAclRequest getBucketAclRequest) throws SdkClientException, AmazonServiceException {
            return null;
        }

        @Override
        public void setBucketAcl(String s, AccessControlList accessControlList) throws SdkClientException, AmazonServiceException {

        }

        @Override
        public void setBucketAcl(String s, CannedAccessControlList cannedAccessControlList) throws SdkClientException, AmazonServiceException {

        }

        @Override
        public ObjectMetadata getObjectMetadata(String s, String s1) throws SdkClientException, AmazonServiceException {
            return null;
        }

        @Override
        public ObjectMetadata getObjectMetadata(GetObjectMetadataRequest getObjectMetadataRequest) throws SdkClientException, AmazonServiceException {
            return null;
        }

        @Override
        public S3Object getObject(String s, String s1) throws SdkClientException, AmazonServiceException {
            return null;
        }

        @Override
        public S3Object getObject(GetObjectRequest getObjectRequest) throws SdkClientException, AmazonServiceException {
            return null;
        }

        @Override
        public ObjectMetadata getObject(GetObjectRequest getObjectRequest, File file) throws SdkClientException, AmazonServiceException {
            return null;
        }

        @Override
        public String getObjectAsString(String s, String s1) throws AmazonServiceException, SdkClientException {
            return null;
        }

        @Override
        public GetObjectTaggingResult getObjectTagging(GetObjectTaggingRequest getObjectTaggingRequest) {
            return null;
        }

        @Override
        public SetObjectTaggingResult setObjectTagging(SetObjectTaggingRequest setObjectTaggingRequest) {
            return null;
        }

        @Override
        public DeleteObjectTaggingResult deleteObjectTagging(DeleteObjectTaggingRequest deleteObjectTaggingRequest) {
            return null;
        }

        @Override
        public void deleteBucket(DeleteBucketRequest deleteBucketRequest) throws SdkClientException, AmazonServiceException {

        }

        @Override
        public void deleteBucket(String s) throws SdkClientException, AmazonServiceException {

        }

        @Override
        public PutObjectResult putObject(PutObjectRequest putObjectRequest) throws SdkClientException, AmazonServiceException {
            return null;
        }

        @Override
        public PutObjectResult putObject(String s, String s1, InputStream inputStream, ObjectMetadata objectMetadata) throws SdkClientException, AmazonServiceException {
            return null;
        }

        @Override
        public PutObjectResult putObject(String s, String s1, String s2) throws AmazonServiceException, SdkClientException {
            return null;
        }

        @Override
        public CopyObjectResult copyObject(String s, String s1, String s2, String s3) throws SdkClientException, AmazonServiceException {
            return null;
        }

        @Override
        public CopyObjectResult copyObject(CopyObjectRequest copyObjectRequest) throws SdkClientException, AmazonServiceException {
            return null;
        }

        @Override
        public CopyPartResult copyPart(CopyPartRequest copyPartRequest) throws SdkClientException, AmazonServiceException {
            return null;
        }

        @Override
        public void deleteObject(String s, String s1) throws SdkClientException, AmazonServiceException {

        }

        @Override
        public void deleteObject(DeleteObjectRequest deleteObjectRequest) throws SdkClientException, AmazonServiceException {

        }

        @Override
        public DeleteObjectsResult deleteObjects(DeleteObjectsRequest deleteObjectsRequest) throws SdkClientException, AmazonServiceException {
            return null;
        }

        @Override
        public void deleteVersion(String s, String s1, String s2) throws SdkClientException, AmazonServiceException {

        }

        @Override
        public void deleteVersion(DeleteVersionRequest deleteVersionRequest) throws SdkClientException, AmazonServiceException {

        }

        @Override
        public BucketLoggingConfiguration getBucketLoggingConfiguration(String s) throws SdkClientException, AmazonServiceException {
            return null;
        }

        @Override
        public BucketLoggingConfiguration getBucketLoggingConfiguration(GetBucketLoggingConfigurationRequest getBucketLoggingConfigurationRequest) throws SdkClientException, AmazonServiceException {
            return null;
        }

        @Override
        public void setBucketLoggingConfiguration(SetBucketLoggingConfigurationRequest setBucketLoggingConfigurationRequest) throws SdkClientException, AmazonServiceException {

        }

        @Override
        public BucketVersioningConfiguration getBucketVersioningConfiguration(String s) throws SdkClientException, AmazonServiceException {
            return null;
        }

        @Override
        public BucketVersioningConfiguration getBucketVersioningConfiguration(GetBucketVersioningConfigurationRequest getBucketVersioningConfigurationRequest) throws SdkClientException, AmazonServiceException {
            return null;
        }

        @Override
        public void setBucketVersioningConfiguration(SetBucketVersioningConfigurationRequest setBucketVersioningConfigurationRequest) throws SdkClientException, AmazonServiceException {

        }

        @Override
        public BucketLifecycleConfiguration getBucketLifecycleConfiguration(String s) {
            return null;
        }

        @Override
        public BucketLifecycleConfiguration getBucketLifecycleConfiguration(GetBucketLifecycleConfigurationRequest getBucketLifecycleConfigurationRequest) {
            return null;
        }

        @Override
        public void setBucketLifecycleConfiguration(String s, BucketLifecycleConfiguration bucketLifecycleConfiguration) {

        }

        @Override
        public void setBucketLifecycleConfiguration(SetBucketLifecycleConfigurationRequest setBucketLifecycleConfigurationRequest) {

        }

        @Override
        public void deleteBucketLifecycleConfiguration(String s) {

        }

        @Override
        public void deleteBucketLifecycleConfiguration(DeleteBucketLifecycleConfigurationRequest deleteBucketLifecycleConfigurationRequest) {

        }

        @Override
        public BucketCrossOriginConfiguration getBucketCrossOriginConfiguration(String s) {
            return null;
        }

        @Override
        public BucketCrossOriginConfiguration getBucketCrossOriginConfiguration(GetBucketCrossOriginConfigurationRequest getBucketCrossOriginConfigurationRequest) {
            return null;
        }

        @Override
        public void setBucketCrossOriginConfiguration(String s, BucketCrossOriginConfiguration bucketCrossOriginConfiguration) {

        }

        @Override
        public void setBucketCrossOriginConfiguration(SetBucketCrossOriginConfigurationRequest setBucketCrossOriginConfigurationRequest) {

        }

        @Override
        public void deleteBucketCrossOriginConfiguration(String s) {

        }

        @Override
        public void deleteBucketCrossOriginConfiguration(DeleteBucketCrossOriginConfigurationRequest deleteBucketCrossOriginConfigurationRequest) {

        }

        @Override
        public BucketTaggingConfiguration getBucketTaggingConfiguration(String s) {
            return null;
        }

        @Override
        public BucketTaggingConfiguration getBucketTaggingConfiguration(GetBucketTaggingConfigurationRequest getBucketTaggingConfigurationRequest) {
            return null;
        }

        @Override
        public void setBucketTaggingConfiguration(String s, BucketTaggingConfiguration bucketTaggingConfiguration) {

        }

        @Override
        public void setBucketTaggingConfiguration(SetBucketTaggingConfigurationRequest setBucketTaggingConfigurationRequest) {

        }

        @Override
        public void deleteBucketTaggingConfiguration(String s) {

        }

        @Override
        public void deleteBucketTaggingConfiguration(DeleteBucketTaggingConfigurationRequest deleteBucketTaggingConfigurationRequest) {

        }

        @Override
        public BucketNotificationConfiguration getBucketNotificationConfiguration(String s) throws SdkClientException, AmazonServiceException {
            return null;
        }

        @Override
        public BucketNotificationConfiguration getBucketNotificationConfiguration(GetBucketNotificationConfigurationRequest getBucketNotificationConfigurationRequest) throws SdkClientException, AmazonServiceException {
            return null;
        }

        @Override
        public void setBucketNotificationConfiguration(SetBucketNotificationConfigurationRequest setBucketNotificationConfigurationRequest) throws SdkClientException, AmazonServiceException {

        }

        @Override
        public void setBucketNotificationConfiguration(String s, BucketNotificationConfiguration bucketNotificationConfiguration) throws SdkClientException, AmazonServiceException {

        }

        @Override
        public BucketWebsiteConfiguration getBucketWebsiteConfiguration(String s) throws SdkClientException, AmazonServiceException {
            return null;
        }

        @Override
        public BucketWebsiteConfiguration getBucketWebsiteConfiguration(GetBucketWebsiteConfigurationRequest getBucketWebsiteConfigurationRequest) throws SdkClientException, AmazonServiceException {
            return null;
        }

        @Override
        public void setBucketWebsiteConfiguration(String s, BucketWebsiteConfiguration bucketWebsiteConfiguration) throws SdkClientException, AmazonServiceException {

        }

        @Override
        public void setBucketWebsiteConfiguration(SetBucketWebsiteConfigurationRequest setBucketWebsiteConfigurationRequest) throws SdkClientException, AmazonServiceException {

        }

        @Override
        public void deleteBucketWebsiteConfiguration(String s) throws SdkClientException, AmazonServiceException {

        }

        @Override
        public void deleteBucketWebsiteConfiguration(DeleteBucketWebsiteConfigurationRequest deleteBucketWebsiteConfigurationRequest) throws SdkClientException, AmazonServiceException {

        }

        @Override
        public BucketPolicy getBucketPolicy(String s) throws SdkClientException, AmazonServiceException {
            return null;
        }

        @Override
        public BucketPolicy getBucketPolicy(GetBucketPolicyRequest getBucketPolicyRequest) throws SdkClientException, AmazonServiceException {
            return null;
        }

        @Override
        public void setBucketPolicy(String s, String s1) throws SdkClientException, AmazonServiceException {

        }

        @Override
        public void setBucketPolicy(SetBucketPolicyRequest setBucketPolicyRequest) throws SdkClientException, AmazonServiceException {

        }

        @Override
        public void deleteBucketPolicy(String s) throws SdkClientException, AmazonServiceException {

        }

        @Override
        public void deleteBucketPolicy(DeleteBucketPolicyRequest deleteBucketPolicyRequest) throws SdkClientException, AmazonServiceException {

        }

        @Override
        public URL generatePresignedUrl(String s, String s1, Date date) throws SdkClientException {
            return null;
        }

        @Override
        public URL generatePresignedUrl(String s, String s1, Date date, HttpMethod httpMethod) throws SdkClientException {
            return null;
        }

        @Override
        public URL generatePresignedUrl(GeneratePresignedUrlRequest generatePresignedUrlRequest) throws SdkClientException {
            return null;
        }

        @Override
        public InitiateMultipartUploadResult initiateMultipartUpload(InitiateMultipartUploadRequest initiateMultipartUploadRequest) throws SdkClientException, AmazonServiceException {
            return null;
        }

        @Override
        public UploadPartResult uploadPart(UploadPartRequest uploadPartRequest) throws SdkClientException, AmazonServiceException {
            return null;
        }

        @Override
        public PartListing listParts(ListPartsRequest listPartsRequest) throws SdkClientException, AmazonServiceException {
            return null;
        }

        @Override
        public void abortMultipartUpload(AbortMultipartUploadRequest abortMultipartUploadRequest) throws SdkClientException, AmazonServiceException {

        }

        @Override
        public CompleteMultipartUploadResult completeMultipartUpload(CompleteMultipartUploadRequest completeMultipartUploadRequest) throws SdkClientException, AmazonServiceException {
            return null;
        }

        @Override
        public MultipartUploadListing listMultipartUploads(ListMultipartUploadsRequest listMultipartUploadsRequest) throws SdkClientException, AmazonServiceException {
            return null;
        }

        @Override
        public S3ResponseMetadata getCachedResponseMetadata(AmazonWebServiceRequest amazonWebServiceRequest) {
            return null;
        }

        @Override
        @Deprecated
        public void restoreObject(RestoreObjectRequest restoreObjectRequest) throws AmazonServiceException {

        }

        @Override
        public RestoreObjectResult restoreObjectV2(RestoreObjectRequest restoreObjectRequest) throws AmazonServiceException {
            return null;
        }

        @Override
        @Deprecated
        public void restoreObject(String s, String s1, int i) throws AmazonServiceException {

        }

        @Override
        public void enableRequesterPays(String s) throws AmazonServiceException, SdkClientException {

        }

        @Override
        public void disableRequesterPays(String s) throws AmazonServiceException, SdkClientException {

        }

        @Override
        public boolean isRequesterPaysEnabled(String s) throws AmazonServiceException, SdkClientException {
            return false;
        }

        @Override
        public void setBucketReplicationConfiguration(String s, BucketReplicationConfiguration bucketReplicationConfiguration) throws AmazonServiceException, SdkClientException {

        }

        @Override
        public void setBucketReplicationConfiguration(SetBucketReplicationConfigurationRequest setBucketReplicationConfigurationRequest) throws AmazonServiceException, SdkClientException {

        }

        @Override
        public BucketReplicationConfiguration getBucketReplicationConfiguration(String s) throws AmazonServiceException, SdkClientException {
            return null;
        }

        @Override
        public BucketReplicationConfiguration getBucketReplicationConfiguration(GetBucketReplicationConfigurationRequest getBucketReplicationConfigurationRequest) throws AmazonServiceException, SdkClientException {
            return null;
        }

        @Override
        public void deleteBucketReplicationConfiguration(String s) throws AmazonServiceException, SdkClientException {

        }

        @Override
        public void deleteBucketReplicationConfiguration(DeleteBucketReplicationConfigurationRequest deleteBucketReplicationConfigurationRequest) throws AmazonServiceException, SdkClientException {

        }

        @Override
        public boolean doesObjectExist(String s, String s1) throws AmazonServiceException, SdkClientException {
            return false;
        }

        @Override
        public BucketAccelerateConfiguration getBucketAccelerateConfiguration(String s) throws AmazonServiceException, SdkClientException {
            return null;
        }

        @Override
        public BucketAccelerateConfiguration getBucketAccelerateConfiguration(GetBucketAccelerateConfigurationRequest getBucketAccelerateConfigurationRequest) throws AmazonServiceException, SdkClientException {
            return null;
        }

        @Override
        public void setBucketAccelerateConfiguration(String s, BucketAccelerateConfiguration bucketAccelerateConfiguration) throws AmazonServiceException, SdkClientException {

        }

        @Override
        public void setBucketAccelerateConfiguration(SetBucketAccelerateConfigurationRequest setBucketAccelerateConfigurationRequest) throws AmazonServiceException, SdkClientException {

        }

        @Override
        public DeleteBucketMetricsConfigurationResult deleteBucketMetricsConfiguration(String s, String s1) throws AmazonServiceException, SdkClientException {
            return null;
        }

        @Override
        public DeleteBucketMetricsConfigurationResult deleteBucketMetricsConfiguration(DeleteBucketMetricsConfigurationRequest deleteBucketMetricsConfigurationRequest) throws AmazonServiceException, SdkClientException {
            return null;
        }

        @Override
        public GetBucketMetricsConfigurationResult getBucketMetricsConfiguration(String s, String s1) throws AmazonServiceException, SdkClientException {
            return null;
        }

        @Override
        public GetBucketMetricsConfigurationResult getBucketMetricsConfiguration(GetBucketMetricsConfigurationRequest getBucketMetricsConfigurationRequest) throws AmazonServiceException, SdkClientException {
            return null;
        }

        @Override
        public SetBucketMetricsConfigurationResult setBucketMetricsConfiguration(String s, MetricsConfiguration metricsConfiguration) throws AmazonServiceException, SdkClientException {
            return null;
        }

        @Override
        public SetBucketMetricsConfigurationResult setBucketMetricsConfiguration(SetBucketMetricsConfigurationRequest setBucketMetricsConfigurationRequest) throws AmazonServiceException, SdkClientException {
            return null;
        }

        @Override
        public ListBucketMetricsConfigurationsResult listBucketMetricsConfigurations(ListBucketMetricsConfigurationsRequest listBucketMetricsConfigurationsRequest) throws AmazonServiceException, SdkClientException {
            return null;
        }

        @Override
        public DeleteBucketAnalyticsConfigurationResult deleteBucketAnalyticsConfiguration(String s, String s1) throws AmazonServiceException, SdkClientException {
            return null;
        }

        @Override
        public DeleteBucketAnalyticsConfigurationResult deleteBucketAnalyticsConfiguration(DeleteBucketAnalyticsConfigurationRequest deleteBucketAnalyticsConfigurationRequest) throws AmazonServiceException, SdkClientException {
            return null;
        }

        @Override
        public GetBucketAnalyticsConfigurationResult getBucketAnalyticsConfiguration(String s, String s1) throws AmazonServiceException, SdkClientException {
            return null;
        }

        @Override
        public GetBucketAnalyticsConfigurationResult getBucketAnalyticsConfiguration(GetBucketAnalyticsConfigurationRequest getBucketAnalyticsConfigurationRequest) throws AmazonServiceException, SdkClientException {
            return null;
        }

        @Override
        public SetBucketAnalyticsConfigurationResult setBucketAnalyticsConfiguration(String s, AnalyticsConfiguration analyticsConfiguration) throws AmazonServiceException, SdkClientException {
            return null;
        }

        @Override
        public SetBucketAnalyticsConfigurationResult setBucketAnalyticsConfiguration(SetBucketAnalyticsConfigurationRequest setBucketAnalyticsConfigurationRequest) throws AmazonServiceException, SdkClientException {
            return null;
        }

        @Override
        public ListBucketAnalyticsConfigurationsResult listBucketAnalyticsConfigurations(ListBucketAnalyticsConfigurationsRequest listBucketAnalyticsConfigurationsRequest) throws AmazonServiceException, SdkClientException {
            return null;
        }

        @Override
        public DeleteBucketInventoryConfigurationResult deleteBucketInventoryConfiguration(String s, String s1) throws AmazonServiceException, SdkClientException {
            return null;
        }

        @Override
        public DeleteBucketInventoryConfigurationResult deleteBucketInventoryConfiguration(DeleteBucketInventoryConfigurationRequest deleteBucketInventoryConfigurationRequest) throws AmazonServiceException, SdkClientException {
            return null;
        }

        @Override
        public GetBucketInventoryConfigurationResult getBucketInventoryConfiguration(String s, String s1) throws AmazonServiceException, SdkClientException {
            return null;
        }

        @Override
        public GetBucketInventoryConfigurationResult getBucketInventoryConfiguration(GetBucketInventoryConfigurationRequest getBucketInventoryConfigurationRequest) throws AmazonServiceException, SdkClientException {
            return null;
        }

        @Override
        public SetBucketInventoryConfigurationResult setBucketInventoryConfiguration(String s, InventoryConfiguration inventoryConfiguration) throws AmazonServiceException, SdkClientException {
            return null;
        }

        @Override
        public SetBucketInventoryConfigurationResult setBucketInventoryConfiguration(SetBucketInventoryConfigurationRequest setBucketInventoryConfigurationRequest) throws AmazonServiceException, SdkClientException {
            return null;
        }

        @Override
        public ListBucketInventoryConfigurationsResult listBucketInventoryConfigurations(ListBucketInventoryConfigurationsRequest listBucketInventoryConfigurationsRequest) throws AmazonServiceException, SdkClientException {
            return null;
        }

        @Override
        public DeleteBucketEncryptionResult deleteBucketEncryption(String s) throws AmazonServiceException, SdkClientException {
            return null;
        }

        @Override
        public DeleteBucketEncryptionResult deleteBucketEncryption(DeleteBucketEncryptionRequest deleteBucketEncryptionRequest) throws AmazonServiceException, SdkClientException {
            return null;
        }

        @Override
        public GetBucketEncryptionResult getBucketEncryption(String s) throws AmazonServiceException, SdkClientException {
            return null;
        }

        @Override
        public GetBucketEncryptionResult getBucketEncryption(GetBucketEncryptionRequest getBucketEncryptionRequest) throws AmazonServiceException, SdkClientException {
            return null;
        }

        @Override
        public SetBucketEncryptionResult setBucketEncryption(SetBucketEncryptionRequest setBucketEncryptionRequest) throws AmazonServiceException, SdkClientException {
            return null;
        }

        @Override
        public void shutdown() {

        }

        @Override
        public com.amazonaws.services.s3.model.Region getRegion() {
            return null;
        }

        @Override
        public String getRegionName() {
            return null;
        }

        @Override
        public URL getUrl(String s, String s1) {
            return null;
        }

        @Override
        public AmazonS3Waiters waiters() {
            return null;
        }

        @Override
        public SetPublicAccessBlockResult setPublicAccessBlock(SetPublicAccessBlockRequest setPublicAccessBlockRequest) {
            return null;
        }

        @Override
        public GetPublicAccessBlockResult getPublicAccessBlock(GetPublicAccessBlockRequest getPublicAccessBlockRequest) {
            return null;
        }

        @Override
        public DeletePublicAccessBlockResult deletePublicAccessBlock(DeletePublicAccessBlockRequest deletePublicAccessBlockRequest) {
            return null;
        }

        @Override
        public GetBucketPolicyStatusResult getBucketPolicyStatus(GetBucketPolicyStatusRequest getBucketPolicyStatusRequest) {
            return null;
        }

        @Override
        public SetObjectLegalHoldResult setObjectLegalHold(SetObjectLegalHoldRequest setObjectLegalHoldRequest) {
            return null;
        }

        @Override
        public GetObjectLegalHoldResult getObjectLegalHold(GetObjectLegalHoldRequest getObjectLegalHoldRequest) {
            return null;
        }

        @Override
        public SetObjectLockConfigurationResult setObjectLockConfiguration(SetObjectLockConfigurationRequest setObjectLockConfigurationRequest) {
            return null;
        }

        @Override
        public GetObjectLockConfigurationResult getObjectLockConfiguration(GetObjectLockConfigurationRequest getObjectLockConfigurationRequest) {
            return null;
        }

        @Override
        public SetObjectRetentionResult setObjectRetention(SetObjectRetentionRequest setObjectRetentionRequest) {
            return null;
        }

        @Override
        public GetObjectRetentionResult getObjectRetention(GetObjectRetentionRequest getObjectRetentionRequest) {
            return null;
        }

        @Override
        public PresignedUrlDownloadResult download(PresignedUrlDownloadRequest presignedUrlDownloadRequest) {
            return null;
        }

        @Override
        public void download(PresignedUrlDownloadRequest presignedUrlDownloadRequest, File file) {

        }

        @Override
        public PresignedUrlUploadResult upload(PresignedUrlUploadRequest presignedUrlUploadRequest) {
            return null;
        }

        @Override
        public SelectObjectContentResult selectObjectContent(SelectObjectContentRequest arg0) throws AmazonServiceException, SdkClientException {
            return null;
        }
        //#endregion
    }

    /**
     * Use this method to test the Deploy Maven Plugin for the default provider chain.  Also tests the create
     * stack scenario.
     */
    @Test(groups = {"unit"})
    public void TestDefaultProviderDeploy() throws Exception {

        StackScenario.createTest = true;
        StackScenario.changeset = true;

        OverridePlugin plugin = new OverridePlugin();

        String stackPath = this.getClass().getClassLoader().getResource("Test-Template.json").getPath();
        String parameterPath = this.getClass().getClassLoader().getResource("Test-Parameters.json").getPath();
        Exception ex = plugin.beginTesting("bucket", null, "Test", "test-repository",
                "com.test", "application-test", "1.0", "Test", stackPath,
                new String[] {parameterPath}, null,
                new CloudFormationDeployMavenPlugin.StackInputParameter[]
                        {
                                new CloudFormationDeployMavenPlugin.StackInputParameter()
                                        .withParameterName("s3Bucket")
                                        .withMatchingParameterName("ArtifactS3Bucket"),
                                new CloudFormationDeployMavenPlugin.StackInputParameter()
                                        .withParameterName("s3Key")
                                        .withMatchingParameterName("ArtifactS3Key")
                        }, null);

        Assert.assertNull(ex);

        // Validating the output log file to determine if the function ran successfully.
        validateLogFile(false, false, false);
    }

    /**
     * Use this method to test the Deploy Maven Plugin for an assumed role.  Also tests the update stack
     * scenario.
     */
    @Test(groups = {"unit"})
    public void TestRoleDeploy() throws Exception {

        StackScenario.createTest = false;
        StackScenario.changeset = true;
        OverridePlugin plugin =  new OverridePlugin();

        String stackPath = this.getClass().getClassLoader().getResource("Test-Template.json").getPath();
        String parameterPath = this.getClass().getClassLoader().getResource("Test-Parameters.json").getPath();
        Exception ex = plugin.beginTesting( "bucket","aws:iam::1111:role/test", "Test",
                "test-repository", "com.test", "application-test", "1.0",
                "Test", stackPath, new String[] {parameterPath}, null,
                new CloudFormationDeployMavenPlugin.StackInputParameter[]
                        {
                                new CloudFormationDeployMavenPlugin.StackInputParameter()
                                        .withParameterName("s3Bucket")
                                        .withMatchingParameterName("ArtifactS3Bucket"),
                                new CloudFormationDeployMavenPlugin.StackInputParameter()
                                        .withParameterName("s3Key")
                                        .withMatchingParameterName("ArtifactS3Key")
                        }, null);

        Assert.assertNull(ex);

        // Validate the log information to determine if the function ran successfully.
        validateLogFile(true, false, false);
    }

    /**
     * Use this method to test the Deploy Maven Plugin for an assumed role with a CLI Command.  Also tests the update
     * stack scenario.
     */
    @Test(groups = {"unit"})
    public void TestCliCommandWithDeploy() throws Exception {

        StackScenario.createTest = false;
        StackScenario.changeset = true;
        OverridePlugin plugin =  new OverridePlugin();

        String file = new File("test-repository/com/test/application-test/1.0/application-test-1.0.jar")
                .getAbsolutePath();

        HashMap<String, CloudFormationDeployMavenPlugin.StackOutputParameterMapping> map = new HashMap<>();
        map.put("Name", new CloudFormationDeployMavenPlugin.StackOutputParameterMapping()
                .withDescription("Person")
                .withParameterName("/hello/world/people[0]/name")
                .withMapParameterName("Michael"));
        map.put("Jane", new CloudFormationDeployMavenPlugin.StackOutputParameterMapping()
                .withDescription("Person")
                .withParameterName("/hello/world/people[1]/name"));

        String stackPath = this.getClass().getClassLoader().getResource("Test-Template.json").getPath();
        String parameterPath = this.getClass().getClassLoader().getResource("Test-Parameters.json").getPath();
        Exception ex = plugin.beginTesting( "bucket","aws:iam::1111:role/test", "Test",
                "test-repository", "com.test", "application-test", "1.0",
                "Test", stackPath, new String[] {parameterPath}, null,
                new CloudFormationDeployMavenPlugin.StackInputParameter[]
                        {
                                new CloudFormationDeployMavenPlugin.StackInputParameter()
                                        .withParameterName("s3Bucket")
                                        .withMatchingParameterName("ArtifactS3Bucket"),
                                new CloudFormationDeployMavenPlugin.StackInputParameter()
                                        .withParameterName("s3Key")
                                        .withMatchingParameterName("ArtifactS3Key")
                        },
                new CloudFormationDeployMavenPlugin.CliCommandOutputParameterMapping[]
                        {
                                new CloudFormationDeployMavenPlugin.CliCommandOutputParameterMapping()
                                .withCommand("java -jar " + file)
                                .withDescription("Test Command Execution.")
                                .withParameters(map)
                        });

        Assert.assertNull(ex);

        // Validate the log information to determine if the function ran successfully.
        validateLogFile(true, false, true);
    }

    /**
     * Use this method to test the Deploy Maven Plugin for an assumed role with a CLI Command containing a filter query.
     * Also tests the update stack scenario.
     */
    @Test(groups = {"unit"})
    public void TestCliCommandWithFilter() throws Exception {

        StackScenario.createTest = false;
        StackScenario.changeset = true;
        OverridePlugin plugin =  new OverridePlugin();

        String file = new File("test-repository/com/test/application-test/1.0/application-test-1.0.jar")
                .getAbsolutePath();

        HashMap<String, CloudFormationDeployMavenPlugin.StackOutputParameterMapping> map = new HashMap<>();
        map.put("Gender", new CloudFormationDeployMavenPlugin.StackOutputParameterMapping()
                .withDescription("Gender of Michael Leedahl")
                .withParameterName("/hello/world/people[name=Michael Leedahl]/gender")
                .withMapParameterName("gender"));

        String stackPath = this.getClass().getClassLoader().getResource("Test-Template.json").getPath();
        String parameterPath = this.getClass().getClassLoader().getResource("Test-Parameters.json").getPath();
        Exception ex = plugin.beginTesting( "bucket","aws:iam::1111:role/test", "Test",
                "test-repository", "com.test", "application-test", "1.0",
                "Test", stackPath, new String[] {parameterPath}, null,
                new CloudFormationDeployMavenPlugin.StackInputParameter[]
                        {
                                new CloudFormationDeployMavenPlugin.StackInputParameter()
                                        .withParameterName("s3Bucket")
                                        .withMatchingParameterName("ArtifactS3Bucket"),
                                new CloudFormationDeployMavenPlugin.StackInputParameter()
                                        .withParameterName("s3Key")
                                        .withMatchingParameterName("ArtifactS3Key")
                        },
                new CloudFormationDeployMavenPlugin.CliCommandOutputParameterMapping[]
                        {
                                new CloudFormationDeployMavenPlugin.CliCommandOutputParameterMapping()
                                        .withCommand("java -jar " + file)
                                        .withDescription("Test Command Execution.")
                                        .withParameters(map)
                        });

        Assert.assertNull(ex);

        // Validate the log information to determine if the function ran successfully.
        validateLogFile(true, false, true);
    }

    /**
     * Use this method to test the Deploy Maven Plugin for a master stack and a secondary stack scenario.
     */
    @Test(groups = {"unit"})
    public void TestTwoStackDeploy() throws Exception {

        StackScenario.createTest = false;
        StackScenario.changeset = true;
        OverridePlugin plugin =  new OverridePlugin();

        String stackPath = this.getClass().getClassLoader().getResource("Test-Template.json").getPath();
        String parameterPath = this.getClass().getClassLoader().getResource("Test-Parameters.json").getPath();
        Exception ex = plugin.beginTesting("bucket","aws:iam::1111:role/test", "Test",
                "test-repository", "com.test", "application-test", "1.0",
                "Test", stackPath, new String[] {parameterPath},
                new CloudFormationDeployMavenPlugin.StackGroup[]
                        {new CloudFormationDeployMavenPlugin.StackGroup()
                                .withRepositoryFilter("-test-")
                                .withStacks(new CloudFormationDeployMavenPlugin.SecondaryStack[]
                                {new CloudFormationDeployMavenPlugin.SecondaryStack()
                                        .withStackName("Test")
                                        .withStackPath(stackPath)
                                        .withStackParameterFilePath(parameterPath)
                                })
                        },
                new CloudFormationDeployMavenPlugin.StackInputParameter[]
                        {
                                new CloudFormationDeployMavenPlugin.StackInputParameter()
                                        .withParameterName("s3Bucket")
                                        .withMatchingParameterName("ArtifactS3Bucket"),
                                new CloudFormationDeployMavenPlugin.StackInputParameter()
                                        .withParameterName("s3Key")
                                        .withMatchingParameterName("ArtifactS3Key")
                        }, null);

        Assert.assertNull(ex);

        // Validate the log information to determine if the function ran successfully.
        validateLogFile(true, true, false);
    }

    /**
     * Use this method to test the Deploy Maven Plugin in a scenario where the initiator configured the pom file
     * incorrectly by providing two master stack parameter files without specifying two secondary stacks.
     */
    @Test(groups = {"unit"})
    public void TestTooManyParametersDeploy() throws Exception {

        StackScenario.createTest = false;
        StackScenario.changeset = true;
        OverridePlugin plugin =  new OverridePlugin();

        String stackPath = this.getClass().getClassLoader().getResource("Test-Template.json").getPath();
        String parameterPath = this.getClass().getClassLoader().getResource("Test-Parameters.json").getPath();
        Exception ex = plugin.beginTesting("bucket","aws:iam::1112:role/test", "Test",
                "test-repository", "com.test", "application-test", "1.0",
                "Test", stackPath, new String[] {parameterPath, parameterPath}, null,
                null, null);

        Assert.assertNotNull(ex);
        Assert.assertNotNull(ex.getCause());
        Assert.assertTrue(ex.getCause().getMessage().equals("Multiple Parameters without secondary stacks."));

        // Validate the log information to determine if the function ran successfully.
        int position = 0;
        File touch = new File("target/audit.txt");
        Object[] lines = Files.lines(Paths.get(touch.toURI())).toArray();

        // Validate Array Sizes
        Assert.assertTrue(lines[position++].equals("Can't have multiple parameter files without secondary stacks."));
        Assert.assertTrue(lines[position++].equals("Error executing the template stack or stack group."));
        Assert.assertTrue(lines[position++].equals("Multiple Parameters without secondary stacks."));
        Assert.assertEquals(lines.length, position);
    }

    /**
     * Use this method to test the Deploy Maven Plugin in a scenario where the initiator configured the pom file
     * incorrectly by providing two master stack parameter files and only specifying one secondary stacks.
     */
    @Test(groups = {"unit"})
    public void TestNonMatchingArrayDeploy() throws Exception {

        StackScenario.createTest = false;
        StackScenario.changeset = true;
        OverridePlugin plugin =  new OverridePlugin();

        String stackPath = this.getClass().getClassLoader().getResource("Test-Template.json").getPath();
        String parameterPath = this.getClass().getClassLoader().getResource("Test-Parameters.json").getPath();
        Exception ex = plugin.beginTesting("bucket","aws:iam::1112:role/test", "Test",
                "test-repository", "com.test", "application-test", "1.0",
                "Test", stackPath, new String[] {parameterPath, parameterPath},
                new CloudFormationDeployMavenPlugin.StackGroup[]
                        {new CloudFormationDeployMavenPlugin.StackGroup()
                                .withRepositoryFilter("filter").withStacks(
                                        new CloudFormationDeployMavenPlugin.SecondaryStack[]
                                                {new CloudFormationDeployMavenPlugin.SecondaryStack()
                                                        .withStackName("stackName")
                                                        .withStackPath("stackPath")
                                                        .withStackParameterFilePath("parameterFilePath")
                                                })
                        },
                new CloudFormationDeployMavenPlugin.StackInputParameter[]
                        {
                                new CloudFormationDeployMavenPlugin.StackInputParameter()
                                        .withParameterName("s3Bucket")
                                        .withMatchingParameterName("ArtifactS3Bucket"),
                                new CloudFormationDeployMavenPlugin.StackInputParameter()
                                        .withParameterName("s3Key")
                                        .withMatchingParameterName("ArtifactS3Key")
                        }, null);

        Assert.assertNotNull(ex);
        Assert.assertNotNull(ex.getCause());
        Assert.assertTrue(ex.getCause().getMessage().equals("Array counts don't match."));

        // Validate the log information to determine if the function ran successfully.
        int position = 0;
        File touch = new File("target/audit.txt");
        Object[] lines = Files.lines(Paths.get(touch.toURI())).toArray();

        // Validate Array Sizes
        Assert.assertTrue(lines[position++].equals("Array counts don't match."));
        Assert.assertTrue(lines[position++].equals("Error executing the template stack or stack group."));
        Assert.assertTrue(lines[position++].equals("Array counts don't match."));
        Assert.assertEquals(lines.length, position);
    }

    /**
     * Use this method to set a filed value with reflection.
     *
     * @param name is the name of the field to set.
     * @param value is the value to set the field with.
     * @throws NoSuchFieldException
     * @throws IllegalAccessException
     */
    private void setField(Object plugin, String name, Object value)
            throws NoSuchFieldException, IllegalAccessException {

        Field roleArnParam = plugin.getClass().getDeclaredField(name);
        roleArnParam.setAccessible(true);
        roleArnParam.set(plugin, value);
    }

    /**
     * Validate the log file to make sure that the test was successful.
     *
     * @param assumedRole tells if we are checking for the assumption of a role.
     * @param withSecondary tells if there are secondary stacks.
     * @param withExecution tells if there was a CLI Command Execution.
     * @throws IOException is thrown when we couldn't read from the log file.
     */
    private void validateLogFile(boolean assumedRole, boolean withSecondary, boolean withExecution) throws IOException {

        int position = 0;
        File touch = new File("target/audit.txt");
        Object[] lines = Files.lines(Paths.get(touch.toURI())).toArray();

        // Validate Array Sizes
        if(withSecondary) Assert.assertTrue(lines[position++].equals("Array counts match."));
        else Assert.assertTrue(lines[position++].equals("Valid because no secondary stack exist and only one stack " +
                "parameter file found."));

        // Validate role
        if(!assumedRole) Assert.assertTrue(lines[position++].equals("roleArn: From default provider chain."));
        else {

            Assert.assertTrue(lines[position++].equals("roleArn: aws:iam::1111:role/test"));
            Assert.assertTrue(lines[position++].equals("Role assumed."));
        }

        // Validate the number of jars found in the repository directory and file names.
        Matcher matcher = Pattern.compile("^([0-9]) artifact(?:s){0,1} (was|were) found[.]$")
                .matcher(lines[position++].toString());

        Assert.assertTrue(matcher.matches());

        int jarCount = Integer.parseInt(matcher.group(1)) + position;
        for (; position < jarCount; position++)
            Assert.assertTrue(lines[position].toString()
                    .contains("/com/test/application-test/1.0/application-test-1.0.jar"));

        Assert.assertTrue(lines[position++].toString()
                .equals("About to copy application-test-1.0.jar to S3."));

        // Validate the name of the file copied to s3.
        String test = "^application-test-1.0.jar was copied to the s3 bucket [(]Test[)][.]$";
        Assert.assertTrue(Pattern.matches(test, lines[position++].toString()));

        Assert.assertTrue(lines[position++].equals("Base64 Encoded SHA256 HASH value: " +
                "4S56TNwrXosYz6FIfsGVXbEgwNYCbzrqSVEyQCzppD0="));

        Assert.assertTrue(lines[position].toString().startsWith("Template URL: https://s3.amazonaws.com/bucket/")
                && lines[position++].toString().endsWith("-Test-Test-Template.json"));

        Assert.assertTrue(lines[position].toString().startsWith("Stack Parameter Path: "));
        Assert.assertTrue(lines[position++].toString().endsWith("/target/test-classes/Test-Parameters.json"));

        // Validate the stack is creating or updating
        if(StackScenario.createTest) {

            Assert.assertTrue(lines[position++].toString().equals("Creating the CloudFormation Stack (Test)."));

            test = "^Created Test with id: arn:aws:cloudformation:us-east-1:1111:stack/Test/[-0-9A-Za-z.]+$";
            Assert.assertTrue(Pattern.matches(test, lines[position++].toString()));

        }
        else {

            Assert.assertTrue(lines[position++].toString().equals("Updating the CloudFormation Stack (Test)."));

            test = "^Updated Test with id: arn:aws:cloudformation:us-east-1:1111:stack/Test/[-0-9A-Za-z.]+$";
            Assert.assertTrue(Pattern.matches(test, lines[position++].toString()));

        }

        // Validate that we finished the routine without errors.
        Assert.assertTrue(lines[position++].equals("Stack Finished."));

        if(withExecution) {

            Assert.assertTrue(lines[position++].equals("Using role from stack credentials."));

            Assert.assertTrue(lines[position++].equals("Using region: us-east-1"));

            Assert.assertTrue(lines[position].toString().startsWith("Executing: java -jar "));
            Assert.assertTrue(lines[position++].toString().endsWith("CloudFormationDeploy/test-repository/com/test/" +
                    "application-test/1.0/application-test-1.0.jar"));
        }

        if(withSecondary) {

            if(StackScenario.createTest) {

                Assert.assertTrue(lines[position++].toString()
                        .equals("Creating the CloudFormation Stack (Test)."));

                test = "^Created Test with id: arn:aws:cloudformation:us-east-1:1111:stack/Test/[-0-9A-Za-z.]+$";
                Assert.assertTrue(Pattern.matches(test, lines[position++].toString()));

            } else {

                Assert.assertTrue(lines[position++].equals("Updating the CloudFormation Stack (Test)."));

                test = "^Updated Test with id: arn:aws:cloudformation:us-east-1:1111:stack/Test/[-0-9A-Za-z.]+$";
                Assert.assertTrue(Pattern.matches(test, lines[position++].toString()));
            }

            Assert.assertTrue(lines[position++].equals("Stack Finished."));
            }

        Assert.assertEquals(lines.length, position);
    }
}
