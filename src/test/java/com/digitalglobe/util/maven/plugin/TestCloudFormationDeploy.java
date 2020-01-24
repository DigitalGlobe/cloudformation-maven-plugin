package com.digitalglobe.util.maven.plugin;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.cloudformation.CloudFormationAsyncClient;
import software.amazon.awssdk.services.cloudformation.model.*;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.*;
import org.apache.maven.plugin.MojoExecutionException;
import org.testng.Assert;
import org.testng.annotations.Test;

import software.amazon.awssdk.services.s3.model.*;

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
    static public class OverridePlugin extends CloudFormationDeployMavenPlugin implements StsClient {

        static {s3Builder = s3Client.class;}
        static {cfAsyncBuilder = cfClient.class;}
        static {stsBuilder = OverridePlugin.class;}

        static public StsClient create() {

            return new OverridePlugin();
        }

        /**
         * Use this method to mock assuming a role.
         *
         * @param assumeRoleRequest contains information about the role to mock.
         * @return a result that contains the new set of credentials to use in the mock.
         */
        @Override
        public AssumeRoleResponse assumeRole(AssumeRoleRequest assumeRoleRequest) {

            Assert.assertEquals(assumeRoleRequest.roleArn(), "aws:iam::1111:role/test");


            return AssumeRoleResponse.builder()
                    .credentials(Credentials.builder()
                            .accessKeyId("A")
                            .secretAccessKey("B")
                            .sessionToken("C")
                            .expiration(Instant.now().plus(1, ChronoUnit.HOURS))
                            .build())
                    .build();
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

        @Override
        public String serviceName() {
            return null;
        }

        @Override
        public void close() {

        }
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
    static public class cfClient implements CloudFormationAsyncClient {

        //#region Mocking methods for CloudFormation Client Builder
        private AwsCredentialsProvider credentials = null;

        /**
         * Use to mock the setting of credentials.
         *
         * @param credentials are the credentials to create the mock client with.
         * @return a reference to this instance for initialization chaining.
         */
        public cfClient credentialsProvider(AwsCredentialsProvider credentials) {

            Assert.assertTrue(credentials.resolveCredentials().accessKeyId().equals("A"));
            Assert.assertTrue(credentials.resolveCredentials().secretAccessKey().equals("B"));
            Assert.assertTrue(((AwsSessionCredentials)credentials.resolveCredentials()).sessionToken().equals("C"));

            this.credentials = credentials;
            return this;
        }

        static public CloudFormationAsyncClient create() {

            return new cfClient();
        }

        static public cfClient builder() {

            return new cfClient();
        }

        public CloudFormationAsyncClient build() {

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
        public CompletableFuture<DescribeStacksResponse> describeStacks(DescribeStacksRequest describeStacksRequest) {

            return CompletableFuture.supplyAsync(() -> {
                try {Thread.sleep(500);}
                catch (InterruptedException e) {/*Ignore*/}

                if(StackScenario.createTest && !StackScenario.createTestComplete)
                    throw CloudFormationException.builder().message("Stack doesn't exist.").build();

                Collection<Output> outputs = new ArrayList<>();
                Output output = Output.builder().outputKey("HELLO").outputValue("World").build();
                outputs.add(output);

                Stack stack = Stack.builder().outputs(outputs).stackName("Test").build();

                ArrayList<Stack> stacks = new ArrayList<>();
                stacks.add(stack);

                return DescribeStacksResponse.builder().stacks(stacks).build();
            });
        }

        /**
         * Use this method to mock the creation of a new cloud formation stack.  The mocking scenario validates
         * the template name and the stack name.  It returns a blank result.
         *
         * @param createStackRequest contains a request describing the stack to deploy.
         * @return a result of the stack creation.
         */
        @Override
        public CompletableFuture<CreateStackResponse> createStack(CreateStackRequest createStackRequest) {

            return CompletableFuture.supplyAsync(() -> {
                Assert.assertEquals(createStackRequest.stackName(), "Test");
                StackScenario.createTestComplete = true;

                return CreateStackResponse.builder()
                        .stackId("arn:aws:cloudformation:us-east-1:1111:stack/Test/" + UUID.randomUUID().toString()).build();
            });
        }

        /**
         * Use this method to mock the update of a cloud formation stack.  This mocking scenario validates the
         * template name and stack name.  It returns a blank result.
         *
         * @param updateStackRequest contains the request to update a stack.
         * @return the result of the update operation.
         */
        @Override
        public CompletableFuture<ExecuteChangeSetResponse> executeChangeSet(ExecuteChangeSetRequest updateStackRequest) {

            return CompletableFuture.supplyAsync(() -> {
                Assert.assertEquals(updateStackRequest.stackName(), "Test");

                return ExecuteChangeSetResponse.builder().build();
            });
        }

       /**
         * Use this method to mock creating a change set.  In this scenario we validate that the request is for the
         * stack we want to update.  It returns a blank response.
         *
         * @param createChangeSetRequest contains the request to create a change set.
         * @return a result of initiating the creation of the change set.
         */
        @Override
        public CompletableFuture<CreateChangeSetResponse> createChangeSet(CreateChangeSetRequest createChangeSetRequest) {

            return CompletableFuture.supplyAsync(() -> {
                Assert.assertTrue(createChangeSetRequest.stackName().equals("Test"));
                Assert.assertTrue(createChangeSetRequest.changeSetType().toString().equals("UPDATE"));

                return CreateChangeSetResponse.builder().stackId("arn:aws:cloudformation:us-east-1:1111:stack/Test/" +
                        UUID.randomUUID().toString()).build();
            });
        }

        /**
         * Use this method to mock the retrieval of the status of a change set.  This scenario returns 1 change
         * without any details.
         *
         * @param describeChangeSetRequest contains the request to describe a change set request.
         * @return a response with the status of a change set request.
         */
        @Override
        public CompletableFuture<DescribeChangeSetResponse> describeChangeSet(DescribeChangeSetRequest describeChangeSetRequest) {

            return CompletableFuture.supplyAsync(() -> {
                Assert.assertTrue(describeChangeSetRequest.stackName().equals("Test"));

                Collection<Change> changes = new ArrayList<>();
                Change change = Change.builder().build();
                changes.add(change);

                return DescribeChangeSetResponse.builder()
                        .changes(changes)
                        .status("CREATE_COMPLETE")
                        .stackId("arn:aws:cloudformation:us-east-1:1111:stack/Test/" +
                                UUID.randomUUID().toString()).build();
            });
        }

        @Override
        public String serviceName() {
            return null;
        }

        @Override
        public void close() {

        }
    }

    /**
     * Use to mock an S3 Client and S3 Client Builder.  In this mock scenario, a jar file is sent to an S3 bucket so
     * that a CloudFormation Stack can reference the file from the S3 bucket and deploy it to a lambda.
     */
    static public class s3Client implements S3Client {

        //#region Mocking methods for S3 Client Builder
        private AwsCredentialsProvider credentials = null;

        /**
         * Use to mock the setting of credentials.
         *
         * @param credentials are the credentials to create the mock client with.
         * @return a reference to this instance for initialization chaining.
         */
        public s3Client credentialsProvider(AwsCredentialsProvider credentials) {

            Assert.assertTrue(credentials.resolveCredentials().accessKeyId().equals("A"));
            Assert.assertTrue(credentials.resolveCredentials().secretAccessKey().equals("B"));
            Assert.assertTrue(((AwsSessionCredentials)credentials.resolveCredentials()).sessionToken().equals("C"));

            this.credentials = credentials;
            return this;
        }

        static public S3Client create() {

            return new s3Client();
        }

        static public s3Client builder() {

            return new s3Client();
        }

        public S3Client build() {

            return this;
        }
        //#endregion

        /**
         * Use this method to mock the sending of a file to s3.
         *
         * @param x is put object request with bucket and key..
         * @param y is the body of the file.
         * @return a result object to indicate the mock has stored the file.
         * @throws SdkClientException
         */
        @Override
        public PutObjectResponse putObject(PutObjectRequest x, RequestBody y)
                throws SdkClientException {

            if(x.bucket().equals("Test")) Assert.assertTrue(x.key().equals("application-test-1.0.jar"));
            else {
                Assert.assertTrue(x.bucket().equals("bucket"));
                Assert.assertTrue(x.key().endsWith("-Test-Test-Template.json"));
            }

            return PutObjectResponse.builder().build();
        }

        @Override
        public String serviceName() {
            return null;
        }

        @Override
        public void close() {

        }
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
            Assert.assertTrue(lines[position++].toString().endsWith("cloudformation-maven-plugin/test-repository/com/test/" +
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
