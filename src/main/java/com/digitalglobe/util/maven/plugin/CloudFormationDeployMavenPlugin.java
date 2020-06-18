package com.digitalglobe.util.maven.plugin;

import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.providers.DefaultAwsRegionProviderChain;
import software.amazon.awssdk.regions.Region;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.digitalglobe.utils.ClientBuilder;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import software.amazon.awssdk.services.cloudformation.CloudFormationAsyncClient;
import software.amazon.awssdk.services.cloudformation.model.*;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;
import software.amazon.awssdk.services.ssm.model.ParameterNotFoundException;
import software.amazon.awssdk.services.ssm.model.PutParameterRequest;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.AssumeRoleRequest;
import software.amazon.awssdk.services.sts.model.Credentials;
import software.amazon.awssdk.services.sts.model.GetCallerIdentityRequest;
import software.amazon.awssdk.services.sts.model.GetCallerIdentityResponse;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Goal which touches a timestamp file.
 *
 * @goal deploy
 * 
 * @phase deploy
 */
@SuppressWarnings({"JavaDoc", "MismatchedReadAndWriteOfArray", "CanBeFinal", "unused"})
public class CloudFormationDeployMavenPlugin extends AbstractMojo {

    static Class stsBuilder = StsClient.class;
    static Class s3Builder = S3Client.class;
    static Class cfAsyncBuilder = CloudFormationAsyncClient.class;
    static private final Class ssmBuilder = SsmClient.class;

    static public class StackParameter {
        String parameterKey;
        String parameterValue;
        boolean usePreviousValue;

        public void setParameterKey(String parameterKey) {
            this.parameterKey = parameterKey;
        }

        public void setParameterValue(String parameterValue) {
            this.parameterValue = parameterValue;
        }

        public void setUsePreviousValue(boolean usePreviousValue) {
            this.usePreviousValue = usePreviousValue;
        }
    }

    /**
     * Stores a group of stacks to be processed in a sequential order.  Supports creating a lambda version stack
     * followed by a lambda alias stack.
     */
    @SuppressWarnings("JavaDoc")
    static public class StackGroup {

        /**
         * An optional string to use when filtering repository results for a file containing a specified substring in
         * the artifact filename.  Generally used for creating new versions of lambda functions as each version must
         * be tied to a unique hashed deployment package.
         *
         * @parameter repositoryFilter is the artifact filename filter substring.
         */
        String repositoryFilter = null;

        /**
         * A set of stacks to be deployed in sequence.
         *
         * @parameter stacks contains a sequenced list of stacks to deploy.
         * @required
         */
        SecondaryStack[] stacks;

        /**
         * Set the repository filter.
         *
         * @param repositoryFilter is a substring filter applied against a jar filename.
         * @return this instance for initialization chaining.
         */
        StackGroup withRepositoryFilter(String repositoryFilter) {

            this.repositoryFilter = repositoryFilter;

            return this;
        }

        /**
         * Set the list of stacks.
         *
         * @param stacks is a list of sequential stacks to operate on.
         * @return this instance for initialization chaining.
         */
        StackGroup withStacks(SecondaryStack[] stacks) {

            this.stacks = stacks;

            return this;
        }
    }

    /**
     * Use this class to hold information about an input parameter for a stack.
     */
    @SuppressWarnings("JavaDoc")
    static public class StackInputParameter {

        /**
         * The name of the input parameter.
         *
         * @parameter parameterName is the name of the input parameter.
         * @required
         */
        String parameterName;

        /**
         * The name of the output parameter to use to fulfill an input request specified by the parameter name above.
         * The POM File must contain a matching parameter name, a parameter store field name or a parameter value.  The
         * matching parameter name can occur with a parameter value where the parameter value is used when the matching
         * parameter name isn't found.  A matching parameter name can not be used with a parameter store field name.
         *
         * @parameter matchingParameterName is the name of the output parameter to use.
         */
        String matchingParameterName = null;

        /**
         * The value of the parameter.  This field is conditionally optional.  The POM file must contain a
         * matching parameter name, a parameter store field name or a parameter value.  The POM file may contain
         * both a matching parameter name and a parameter value or a parameter field name and a parameter value.
         * The matching name and parameter store field name takes precedence but the parameter value will be use
         * if the matching parameter or parameter store parameter doesn't exist.
         *
         * @parameter parameterValue is the value of the output parameter to use.
         */
        String parameterValue = null;

        /**
         * The name of a field in the System Manager Parameter Store to pull a value from and use as an input value.
         * This works similar to a parameter value in that it replace the input field value with a static value.  The
         * difference is that the static value is stored in AWS instead of the POM file.  The POM file must contain a
         * matching parameter name, a parameter store field name or a parameter value.  It may contain a parameter value
         * that acts as a default if the parameter is not found in the parameter store.
         *
         * @parameter parameterStoreFieldName contains the name of the field to pull the input value from.
         */
        String parameterStoreFieldName = null;

        /**
         * Sets the parameter name.
         *
         * @param parameterName is the name of the input parameter.
         * @return this instance for initialization chaining.
         */
        StackInputParameter withParameterName(String parameterName) {

            this.parameterName = parameterName;

            return this;
        }

        /**
         * Sets the matching parameter name.
         *
         * @param matchingParameterName is the name of the output parameter to full the input parameter.
         * @return this instance for initialization chaining.
         */
        StackInputParameter withMatchingParameterName(String matchingParameterName) {

            this.matchingParameterName = matchingParameterName;

            return this;
        }

    }

    @SuppressWarnings("unused")
    public enum ParameterType {

        STRING("String"),
        STRING_LIST("StringList"),
        SECURE_STRING("SecureString");

        private final String type;

        ParameterType(String type) {

            this.type = type;
        }

        @Override
        public String toString() {

            return this.type;
        }
    }

    /**
     * Use this class to hold information about special mapping of output parameters or where to store the output
     * parameters for future reference.
     */
    @SuppressWarnings({"JavaDoc", "CanBeFinal"})
    static public class StackOutputParameterMapping {

        /**
         * The name of the output parameter from a CloudFormation Template.  If used in a Cli Command Output Parameter
         * Mapping, the name is the full json path to parameter to extract from the result.
         *
         * For Example of Cli Command to extract VPN outside IP Addresses which come in an array:
         * Example Command: aws ec2 describe-vpn-connections
         * Example Parameter Names:
         * /VpnConnections/VgwTelemetry[0]/OutsideIpAddress
         * /VpnConnections/VgwTelemetry[1]/OutsideIpAddress
         *
         * @parameter parameterName is the name of the output parameter.
         * @required
         */
        String parameterName;

        /**
         * A description of the parameter and its use.  This is a required field.
         *
         * @parameter description tells what the parameter is and how it is used.
         * @required
         */
        String description;

        /**
         * The name of a condition that determines if the output mapping is required in the stack group or not.
         *
         * @parameter condition is the name of the condition to check.
         */
        String condition = null;

        /**
         * The role to assume before saving a parameter to the System Manager Parameter Store.  By default this value
         * is null which means that role specified for the template is used or if that is null the default provider
         * chain.  This supports the ability to do cross account writes to the System Manager Parameter Store.
         *
         * @parameter roleArn contains the AWS ARN for the role to assume.
         */
        private String roleArn = null;


        /**
         * The name of to map the output parameter to when storing the output parameter in the output parameter
         * array.
         *
         * @parameter mapParameterName is the parameter name to map the output parameter to in the parameter array.
         */
        String mapParameterName = null;

        /**
         * The name of a field in the System Manager Parameter Store to put the output parameter into.  If the
         * parameter store field doesn't exist it will be created and the value stored in it.  If it already exists,
         * the value will be updated.
         *
         * @parameter parameterStoreFieldName contains the name of the field to put the output parameter.
         */
        String parameterStoreFieldName = null;

        /**
         * The type of a field in the System Manager Parameter Store to put the output parameter into.  If the
         * parameter store field name is null then this field is ignored.
         *
         * @parameter parameterStoreFieldType contains the type of the value to use.  The default is String.
         */
        ParameterType parameterStoreFieldType = ParameterType.STRING;

        /**
         * The default Parameter value is used as the parameter value when the parameter with the parameter name is
         * not found.  If the default parameter value is null, an error is thrown if the parameter with the parameter
         * name is not found.
         *
         * @parameter defaultParameterValue is the value to set if the parameter isn't found.
         */
        String defaultParameterValue = null;

        /**
         * Sets the parameter name.
         *
         * @param parameterName is the name of the input parameter.
         * @return this instance for initialization chaining.
         */
        StackOutputParameterMapping withParameterName(String parameterName) {

            this.parameterName = parameterName;

            return this;
        }

        /**
         * Set the description of the parameter.
         *
         * @param description tells how the parameter is used.
         * @return this instance for initialization chaining.
         */
        StackOutputParameterMapping withDescription(String description) {

            this.description =  description;

            return this;
        }

        /**
         * Sets the matching parameter name.
         *
         * @param mapParameterName is the name of the map parameter to map the output parameter to.
         * @return this instance for initialization chaining.
         */
        StackOutputParameterMapping withMapParameterName(String mapParameterName) {

            this.mapParameterName = mapParameterName;

            return this;
        }

        /**
         * Set the default parameter value to use if the parameter is not found.
         *
         * @param defaultParameterValue is the value to use if the parameter is not found.
         * @return this instance for initialization chaining.
         */
        StackOutputParameterMapping withDefaultParameterValue(String defaultParameterValue) {

            this.defaultParameterValue = defaultParameterValue;

            return this;
        }
    }

    /**
     * Use this class to test an output parameter to determine if it has the same check value.  It it does then the
     * test should return true and the condition is satisfied; otherwise, it returns false.
     */
    static public class ParameterValueCheckCondition {

        /**
         * The name of the output parameter.
         *
         * @parameter parameterName is the name of the output parameter to test.
         * @required
         */
        String parameterName;

        /**
         * The value the parameter should contain.
         *
         * @parameter checkValue is the value the parameter should contain.
         */
        String checkValue;
    }

    /**
     * Use this class to specify output parameters that can't be extracted from the CloudFormation stack; however, can
     * be gather through the AWS CLI.  You would run these after a stack that produces resource values that the are
     * need for other stacks or that need to go to the parameter store.
     *
     * Example: Get the outside (public) IP Addresses for a VPN connection.
     * The CloudFormation template creates the VPN connection but there is no way to get the outside addresses as
     * output parameters.  However, the describe-vpn-connections cli command can retrieve the information.
     */
    @SuppressWarnings("JavaDoc")
    static public class CliCommandOutputParameterMapping {

        /**
         * A description of the mapping and its use.  This is a required field.
         *
         * @parameter description tells what the parameter is and how it is used.
         * @required
         */
        String description;

        /**
         * The name of a condition that determines if the output mapping is required in the stack group or not.  This
         * condition applies to the mapping as a whole and determines if we should evaluate the mapping or not.  If
         * this condition evaluates to true it doesn't mean that individual parameters extracted from the resulting
         * JSON document are processed only that the document is retrieved and parameters are considered based on their
         * own condition statements.
         *
         * @parameter condition is the name of the condition to check.
         */
        String condition = null;

        /**
         * If this is specified, it represents a check of an output parameter value to determine if it equals a
         * value to check against.  If it does, the condition is satisfied and the command is executed.  Otherwise,
         * the condition is not satisfied and the output mapping is ignored.  If this value is null, it is assumed
         * that there is no check condition and the output mapping is executed.
         */
        ParameterValueCheckCondition checkCondition = null;

        /**
         * The name of the region where this parameter mapping is valid.  If the deployment region doesn't match the
         * region in this condition, the command is not executed.  It it does match the region, the command is executed.
         * This is useful when a resource is only required in one region and all other regions will share that resource.
         *
         * @parameter regionCondition is the name of the region where this mapping is valid.
         */
        String regionCondition = null;

        /**
         * The name of the region where this parameter mapping is not valid.  If the deployment region matches the region
         * in this condition, the parameter mapping is not executed.  If it doesn't match the region, the parameter mapping
         * is executed.  This is useful when the output of command is not relevant for a particular region probably because
         * the resource the command is referencing is implemented in another region and is a shared resource.  This condition,
         * if satisfied, will prevent the command from executing .
         *
         * @parameter regionConditionExclude
         */
        String regionConditionExclude = null;

        /**
         * The command syntax to run.  For example, to retrieve information about a VPN connection you may run the
         * describe-vpn-connections command.
         *
         * Example: aws ec2 describe-vpn-connections
         *
         * @parameter command is the command to execute.
         * @required
         */
        String command;

        /**
         * This flag signals if spaces should be inserted before adding parameters to the command line and between
         * the parameter name and parameter value.  The default is to insert spaces because you are adding an option
         * to a command.  However, you may want to specify false if you are inserting a filter value into a --filter
         * option on an AWS describe command.
         *
         * @parameter commandParameterSpacing signals when to insert spaces with command and parameters.
         */
        Boolean commandParameterSpacing = true;

        /**
         * The command may have input parameters that need to be added to the command line.  For example a d
         * describe command may have a filter of some kind to limit what is described.
         *
         * @parameter commandParameters is an array of command parameters to add to the command.
         */
        StackInputParameter[] commandParameters = null;

        /**
         * The ARN of a role to use with the command.  This is an optional value and if not specified will default
         * to the role of the instance you are running on or the default cli command profile.
         *
         * @parameter profile is the profile to use when executing the command.
         */
        String roleArn = null;

        /**
         * A mapping output parameter name, to Stack Output Parameter Mappings.  The key parameter name is the default
         * name that will be added to the output parameters array if a mapParameterName is not specified in the
         * StackOutputParameterMapping.
         *
         * Example: Map["TunnelOneIPAddress", StackOutputParameterMapping{"/.../OutsideIpAddress"}
         * When evaluating the command for the OutsideIpAddress the result will be stored in "TunnelOneIPAddress"
         * You could override this by specifying a mapParameterName in the Stack Output Parameter Mapping.
         *
         * @parameter parameters are the parameters to extract from the result.
         */
        Map<String, StackOutputParameterMapping> parameters = null;

        /**
         * Set the description of the mapping.
         *
         * @param description is the mapping description
         * @return this instance for initialization chaining.
         */
        CliCommandOutputParameterMapping withDescription(String description) {

            this.description = description;

            return this;
        }

        /**
         * Set the command to execute to retrieve the parameters.
         *
         * @param command is the command to execute.
         * @return this instance for initialization chaining.
         */
        CliCommandOutputParameterMapping withCommand(String command) {

            this.command = command;

            return this;
        }

        /**
         * Set the parameter mappings to use when extracting parameters from the command results.
         *
         * @param parameters are the parameter mappings.
         * @return this instance for initialization chaining.
         */
        CliCommandOutputParameterMapping withParameters(Map<String, StackOutputParameterMapping> parameters) {

            this.parameters = new HashMap<>(parameters);

            return this;
        }
    }

    /**
     * Stores the secondary stack parameters when doing a two phased deploy such as a lambda followed by versions and
     * aliases.
     */
    @SuppressWarnings({"JavaDoc", "CanBeFinal"})
    static public class SecondaryStack {

        /**
         * The role to assume before performing any other AWS service calls.  By default this value is null which
         * means that AWS service calls will be assume the same role as the parent stack.
         *
         * @parameter roleArn contains the AWS ARN for the role to assume.
         */
        private String roleArn = null;

        /**
         * Use this flag to indicate that the secondary stack should be used as a reference stack for outputs
         * of a previous run as inputs into other secondary stacks.  If the secondary stack doesn't exist, it is
         * treated as not required and is not created.  The default is false indicating that the secondary stack
         * should be treated as a stack to create or update.
         *
         * @parameter stackReadOnly signals when the stack is used only as a reference for it previous outputs.
         */
        private Boolean stackReadOnly = false;

        /**
         * A conditionally optional prefix to a stack name that will be appended with a unique id when the stack is
         * created.  You must specify either a stack name prefix or a stack name in a secondary stack.
         *
         * @parameter stackNamePrefix contains a prefix for an auto-generated unique stack name.
         */
        String stackNamePrefix = null;

        /**
         * A conditionally optional name of the cloud formation stack that should be created or updated.  You must
         * specify either a stack name or a stack name prefix to use when creating a stack.
         *
         * @parameter stackName is the name of the stack.
         */
        String stackName = null;

        /**
         * The name of a condition that determines if the stack is required in the stack group or not.
         *
         * @parameter condition is the name of the condition to check.
         */
        String condition = null;

        /**
         * The name of the region where this template is valid.  If the deployment region doesn't match the region
         * in this condition, the template is not executed.  If it does match the region, the template is executed.
         * This is useful when a resource is only required in one region and all other regions will share that resource.
         * This condition if not satisfied will prevent the stack from executing and will not perform any output
         * parameter mappings including CLI command parameter mappings.
         *
         * @parameter regionCondition is the name of the region where this mapping is valid.
         */
        String regionCondition = null;

        /**
         * The name of the region where this template is not valid.  If the deployment region matches the region
         * in this condition, the template is not executed.  If it doesn't match the region, the template is executed.
         * This is useful when a resource is not relevant for a particular region probably because it is implemented in
         * another region and is a shared resource or it is implemented in a different template.  This condition, if
         * satisfied, will prevent the stack from executing and will not perform any output parameter mappings including
         * CLI command parameter mappings.
         *
         * @parameter regionConditionExclude
         */
        String regionConditionExclude = null;

        /**
         * Use this flag to enable stackReadOnly flag when the region condition is not satisfied or the exclusion is satisfied.
         * By default this is set to false indicating that when the region condition is not satisfied or the exclusion is
         * satisfied the stack is ignored.
         *
         * @parameter regionConditionElseStackReadOnly
         */
        private boolean regionConditionElseStackReadOnly = false;

        /**
         * If this is specified, it represents a check of an output parameter value to determine if it equals a
         * value to check against.  If it does, the condition is satisfied and the secondary stack is executed.  Otherwise,
         * the condition is not satisfied and the secondary stack is ignored.  If this value is null, it is assumed
         * that there is no check condition and the secondary stack is executed according to the rules of other conditions.
         */
        ParameterValueCheckCondition checkCondition = null;

        /**
         * The path to the file containing the CloudFormation Template.
         *
         * @parameter stackPath is the path to the CloudFormation Template.
         * @required
         */
        String stackPath;

        /**
         * The path to a file containing parameters for the CloudFormation Template
         *
         * @parameter stackParameterFilePath is the path to a file containing an array of parameters.
         * @required
         */
        String stackParameterFilePath;

        /**
         * This is the path and name to an artifact to deploy in the form a of a regular expression.  The expression
         * is used to find an artifact on the local file system.  The intent is to override the master stack artifact
         * with an artifact for this secondary stack.  It replaces the s3Bucket and s3Prefix in the output parameters
         * with a new artifact.  The original artifact is not saved and the output parameters are not restored.  This
         * artifact will be used even if there was no deployment artifact specified in the master stack.  This
         * parameter is null by default meaning that no override will take place.
         *
         * @parameter deploymentArtifactRegEx is a regular expression for an output artifact.
         */
        String deploymentArtifactRegEx = null;

        /**
         * An optional list of input parameters required by the stack template.
         *
         * @parameter inputParameters contains a list of input parameter required by the stack.
         */
        StackInputParameter[] inputParameters = null;

        /**
         * An optional is of parameter mappings.  These mappings are used to change the name of an output parameter in
         * the output parameter array or to store a parameter in the System Manager Parameter Store.  You can use both
         * fields to map the output and store in the System Manager.
         */
        StackOutputParameterMapping[] outputParameterMappings = null;

        /**
         * An optional set of parameter mappings.  These mappings are used to extract parameter values using AWS CLI
         * commands.  Some resource produce values that are not returned by CloudFormation; however, the AWS CLI provides
         * a set of describe commands that can be used to extract addition information.  These mapping can pull that
         * additional information out to use as inputs to other stacks or to populate the System Manager Parameter Store.
         *
         * @parameter cliCommandOutputParameterMappings contain a list of mappings to apply to the output parameters array.
         */
        private CliCommandOutputParameterMapping[] cliCommandOutputParameterMappings = null;

        /**
         * An optional field for specifying an override of the region in the client constructors for CloudFormation API.
         *
         * @parameter region is the region in AWS to connect to.
         */
        private String region = null;

        /**
         * Sets the role to use with this stack.
         *
         * @param roleArn is the role to use.
         * @return this instance for initialization chaining.
         */
        SecondaryStack withRoleArn(String roleArn) {

            this.roleArn = roleArn;

            return this;
        }

        /**
         * Sets the stack as a reference stack for its outputs from previous runs.  It isn't executed.
         *
         * @param stackReadOnly is a flag indicating if the stack is for output reference only.
         * @return this instance for initialization chaining.
         */
        SecondaryStack withStackReadOnly(Boolean stackReadOnly) {

            this.stackReadOnly = stackReadOnly;

            return this;
        }

        /**
         * Set the stack name prefix with initialization chaining.
         *
         * @param stackNamePrefix is the prefix to an auto-generated unique stack name.
         * @return this instance for initialization chaining.
         */
        SecondaryStack withStackNamePrefix(String stackNamePrefix) {

            this.stackNamePrefix = stackNamePrefix;

            return this;
        }

        /**
         * Set the stack name with initialization chaining.
         *
         * @param stackName is the name of the stack to deploy.
         * @return this instance for initialization chaining.
         */
        SecondaryStack withStackName(String stackName) {

            this.stackName = stackName;

            return this;
        }

        /**
         * Set the stack path with initialization chaining.
         *
         * @param stackPath is the path to the stack template to deploy.
         * @return this instance for initialization chaining.
         */
        SecondaryStack withStackPath(String stackPath) {

            this.stackPath = stackPath;

            return this;
        }

        /**
         * Set the stack parameter file path with initialization chaining.
         *
         * @param stackParameterFilePath is the path to a file containing the parameter values to deploy the stack with.
         * @return this instance for initialization chaining.
         */
        SecondaryStack withStackParameterFilePath(String stackParameterFilePath) {

            this.stackParameterFilePath = stackParameterFilePath;

            return this;
        }

        /**
         * Sets the stack input parameters.
         *
         * @param inputParameters are input parameters need by the stack template.
         * @return this instance for initialization chaining.
         */
        SecondaryStack withInputParameters(StackInputParameter[] inputParameters) {

            this.inputParameters = inputParameters;

            return this;
        }

        /**
         * Set the output parameter mappings to apply after the execution of the stack.
         *
         * @param outputParameterMappings are the mapping to apply to the output parameters.
         * @return this instance for initialization chaining.
         */
        SecondaryStack withOutputParameterMappings(StackOutputParameterMapping[] outputParameterMappings) {

            this.outputParameterMappings = outputParameterMappings;

            return this;
        }

        /**
         * Sets the name of the condition for executing the stack.
         *
         * @param condition is the name of the condition.
         * @return this instance for initialization chaining.
         */
        SecondaryStack withCondition(String condition) {

            this.condition = condition;

            return this;
        }
    }

    /**
     * This enumeration describes how artifacts are copied.  If they are copied before the stack is run or after
     * the stack is run.
     */
    public enum ArtifactCopyAction {

        BEFORE,
        AFTER
    }

    /**
     * Use this flag when you wish to run the deployment of the master template and secondary templates in a
     * different region then the pipeline is running.  This region will be used with regionCondition so that
     * the regionCondition will compare against the override when it is set instead of the region in which the
     * pipeline is running.  When the deploymentRegionOverride is null, it is considered as not set and the
     * regionCondition will apply to the region in which the pipeline is running.  Furthermore, this flag
     * will effectively set the region flag on the master template and secondary templates so that the template
     * is deployed in the override region.  However, if you do set the region variable it will be the region
     * used instead of the deploymentRegionOverride.
     *
     * @parameter deploymentRegionOverride
     */
    private String deploymentRegionOverride = null;

    /**
     * Location of the file.
     *
     * @parameter outputDirectory contains the location of the file (i.e. ${project.build.directory}).
     * @required
     */
    private File outputDirectory;

    /**
     * The role to assume before performing any other AWS service calls.  By default this value is null which
     * means that AWS service calls will be taken from the default provider chain.
     *
     * @parameter roleArn contains the AWS ARN for the role to assume.
     */
    private String roleArn = null;

    /**
     * Signals when to look for artifacts.  The field is optional and the default behavior is to look for artifacts
     * to deploy.  If the parameter is set to false, an artifact is not deployed.  This allows you to invoke
     * Cloud Formation Templates that don't deploy code but that do create resources such as IAM roles, etc.
     *
     * @parameter artifacts signals when to look for artifacts to deploy.
     */
    @SuppressWarnings("FieldCanBeLocal")
    private Boolean artifacts = true;

    /**
     * Signals when to add capabilities for requiring IAM permissions.  The field is optional and the default
     * behavior is not to require IAM capabilities.
     *
     * @parameter requiresIAM signals when IAM permissions are required.
     */
    private Boolean requiresIAM = false;

    /**
     * Specifies the bucket to use when storing CloudFormation Templates.
     *
     * @parameter templateS3Bucket is the location to store CF Templates.
     * @required
     */
    private String templateS3Bucket;

    /**
     * Specifies the prefix to us when storing CloudFormation Templates.  This is an optional field and is null
     * by default.
     *
     * @parameter templateS3Prefix is the prefix to use when storing CloudFormation Templates.
     */
    private String templateS3Prefix = null;

    /**
     * The name of the S3 Bucket that the jar class will be stored in.
     *
     * @parameter s3Bucket is the name of the bucket in S3.
     */
    private String s3Bucket;

    /**
     * A prefix (directory name) that is put in front the the artifact name when it is written to s3.  This field is
     * optional and the default action is to store to the bucket without a prefix.
     *
     * @parameter s3Prefix is the name of the prefix to apply to the artifact.
     */
    private String s3Prefix = null;

    /**
     * This represents when the artifact is copied to the bucket specified in s3Bucket.  If the action is before, it
     * will be copied before the master/primary template is executed.  If the action is after, it will be copied
     * after the master/primary template is execute.  The default behavior is before which assume the S3 bucket
     * already exists.  Using after allows you to create an S3 bucket with a template before using it in a secondary
     * stack.
     *
     * @parameter copyAction is the action that specifies when to copy the artifact.
     */
    private ArtifactCopyAction copyAction = ArtifactCopyAction.BEFORE;

    /**
     * The path to the local repository where the jar file is stored.
     *
     * @parameter repositoryPath is the path to the repository containing the jar file.
     */
    private String repositoryPath = null;

    /**
     * The id of the group that the artifact belongs to.
     *
     * @parameter groupId is the name of the artifact to deploy.
     */
    private String groupId;

    /**
     * The id of the artifact to deploy.
     *
     * @parameter artifactId is the name of the artifact to deploy.
     */
    private String artifactId;

    /**
     * The version of the artifact to deploy.
     *
     * @parameter version is version of the artifact to deploy.
     */
    private String version;

    /**
     * Type refers to what type of artifact was packaged.  Examples are jar, zip, etc.  Jar is the default type.
     *
     * @parameter packaging is how the artifact was packaged (file format).
     */
    private String type = "jar";

    /**
     * Use this flag to indicate that the master or primary stack should be used as a reference stack for outputs
     * of a previous run as inputs into the secondary stacks.  If the master or primary stack doesn't exist, it is
     * treated as not required and is not created.  The default is false indicating that the master or primary stack
     * should be treated as a stack to create or update.
     *
     * @parameter stackReadOnly signals when the stack is used only as a reference for it previous outputs.
     */
    @SuppressWarnings("FieldCanBeLocal")
    private Boolean stackReadOnly = false;

    /**
     * The name of the cloud formation stack that should be created or updated.
     *
     * @parameter stackName is the name of the stack.
     * @required
     */
    private String stackName;

    /**
     * The path to the file containing the CloudFormation Template.
     *
     * @parameter stackPath is the path to the CloudFormation Template.
     * @required
     */
    private String stackPath;

    /**
     * A map of boolean conditions that can be used to determine if a stack should be deployed or not in a
     * given stack set.
     *
     * @parameter conditions is a map of boolean conditions
     */
    private Map<String, Boolean> conditions = null;

    /**
     * The name of the region where the master template is valid.  If the deployment region doesn't match the
     * region in this condition, the master template is not executed.  If it does match the region, the master template
     * is executed.  This is useful when a resource is only required in one region (such as roles) and all other
     * regions will share that resource.  The flag doesn't prevent secondary stacks from executing.  It only determines
     * when the master stack template is executed.  Even if the master template is not executed, secondary stacks
     * may be executed.  If not statisfied, not only will the template not be executed, parameter mappings will not
     * occur including CLI command output parameters, unless the regionConditionElseReodOnly is set to true.
     *
     * @parameter regionCondition is the name of the region where this mapping is valid.
     */
    private String regionCondition = null;

    /**
     * The name of the region where this template is not valid.  If the deployment region matches the region
     * in this condition, the template is not executed.  If it doesn't match the region, the template is executed.
     * This is useful when a resource is not relevant for a particular region probably because it is implemented in
     * another region and is a shared resource or it is implemented in a different template.  This condition, if
     * satisfied, will prevent the stack from executing and will not perform any output parameter mappings including
     * CLI command parameter mappings.
     *
     * @parameter regionConditionExclude
     */
    String regionConditionExclude = null;

    /**
     * Use this flag to enable stackReadOnly flag when the region condition is not satisfied or the exclusion is satisfied.
     * By default this is set to false indicating that when the region condition is not satisfied or the exclusion is
     * satisfied the stack is ignored.
     *
     * @parameter regionConditionElseStackReadOnly
     */
    private boolean regionConditionElseStackReadOnly = false;

    /**
     * An optional list of input parameters for the stack template.  These can only include reserved matching parameters
     * provided by this application, static parameter values or parameters read from the system manager parameter store.
     *
     * Reserved matching parameters:
     * ArtifactS3Bucket - The S3 Bucket this application is configured to write artifacts to for deployment.
     * ArtifactS3Key - The name of the artifact written to the S3 Bucket.
     * CodeSHA256 - A Base 64 String of the SHA 256 Hash of the artifact written to the S3 Bucket.
     *
     * @parameter inputParameters contains a set of input parameters required by the template from external templates.
     */
    private StackInputParameter[] inputParameters = null;

    /**
     * An optional set of parameter mappings.  These mappings are used to change the name of an output parameter in
     * the output parameter array or to store a parameter in the System Manager Parameter Store.  You can use both
     * fields to map the output and store in the System Manager.  Output Parameter Mappings are considered suggestions,
     * if a mapping doesn't match any output parameters it is ignored.  A output parameter doesn't need a mapping
     * to be stored in the output parameter array.  That is a default action.
     *
     * @parameter outputParameterMappings contains a list of mappings to apply to output parameters.
     */
    private StackOutputParameterMapping[] outputParameterMappings = null;

    /**
     * An optional set of parameter mappings.  These mappings are used to extract parameter values using AWS CLI
     * commands.  Some resource produce values that are not returned by CloudFormation; however, the AWS CLI provides
     * a set of describe commands that can be used to extract addition information.  These mapping can pull that
     * additional information out to use as inputs to other stacks or to populate the System Manager Parameter Store.
     *
     * @parameter cliCommandOutputParameterMappings contain a list of mappings to apply to the output parameters array.
     */
    private CliCommandOutputParameterMapping[] cliCommandOutputParameterMappings = null;

    /**
     * The path to a file containing parameters for the CloudFormation Template
     *
     * @parameter stackParameterFilePath is the path to a file containing an array of parameters.
     * @required
     */
    private String[] stackParameterFilePaths;

    /**
     *  An optional array containing parameter sets of secondary stacks to run after the master stack list above
     *  and the secondary stack listed in secondary stack fields.  This allows us to deploy lambda's with
     *  multiple version and aliases of the same lambda function.  Each function can have separate environment
     *  variables.
     *
     *  @parameter secondaryStacks contains an array of secondary stack information.
     */
    private StackGroup[] secondaryStackGroups = null;

    /**
     * The audit log file.  It is created at the start of the maven plugin execution and filled in during the
     * execution of the plugin.
     */
    private FileWriter audit = null;

    /**
     * An optional field for specifying an override of the region in the client constructors for CloudFormation API.
     *
     * @parameter region is the region in AWS to connect to.
     */
    private String region = null;

    /**
     * Use this method to create or update a CloudFormation Stack based on specified input parameters.
     *
     * @throws MojoExecutionException when the method can't process the request.
     * @throws ClassCastException when the parameter isn't a string.  This should not happen (bug if it does).
     */
    public void execute() throws MojoExecutionException, ClassCastException {

        // Make sure the directory exists for the audit log file.
        System.out.format("Output Directory %s.\n", outputDirectory.getAbsolutePath());
        File f = outputDirectory;
        long time = LocalDateTime.now().toEpochSecond(ZoneOffset.UTC);

        if ( !f.exists() ) {
            //noinspection ResultOfMethodCallIgnored
            f.mkdirs();
        }

        if(!f.exists()) System.out.println("Didn't create directory.");

        // Create Audit Log File
        File touch = new File( f, "audit.txt" );

        try {

            // Get Audit Log file.
            audit = new FileWriter( touch );
            audit.flush();

            // Make sure array match length
            int stackParameterFileCount = stackParameterFilePaths.length;
            int secondaryStackGroupCount = secondaryStackGroups == null ? 0 : secondaryStackGroups.length;

            if(secondaryStackGroupCount == 0) {

                if (stackParameterFileCount != 1) {
                    audit.write("Can't have multiple parameter files without secondary stacks.\n");
                    throw new MojoExecutionException("Multiple Parameters without secondary stacks.");

                } else {

                    audit.write("Valid because no secondary stack exist and only one stack parameter file found.\n");
                }

            } else {

                boolean matchingCounts = (stackParameterFileCount == secondaryStackGroupCount);
                audit.write("Array counts " + (matchingCounts ? "match.\n" : "don't match.\n"));
                if(!matchingCounts) throw new MojoExecutionException("Array counts don't match.");
            }

            // Make sure that artifact id, group id and version are provided when artifacts is true
            if(artifacts) {

                if(artifactId == null || artifactId.isEmpty()) throw new MojoExecutionException("No artifact id.");
                if(groupId == null || groupId.isEmpty()) throw new MojoExecutionException("No group id.");
                if(version == null || version.isEmpty()) throw new MojoExecutionException("No version.");
            }

            // Get the credentials
            AwsCredentialsProvider sessionCredentials = getAwsCredentialsProvider(roleArn);

            File[] jars = null;
            if(artifacts) {

                if (repositoryPath == null) repositoryPath = System.getProperty("user.home") + "/.m2/repository";

                jars = new File(repositoryPath + "/" +
                        String.join("/", groupId.split("[.]")) + "/" + artifactId + "/" + version + "/")
                        .listFiles(file -> file.getName().toLowerCase().endsWith("." + type));

                if (jars == null) throw new Exception("No artifacts found to deploy");
            }

            // Process stack and secondary stack files
            for(int itemCount = 0; itemCount < stackParameterFileCount; itemCount++) {

                // Renew S3 client
                String currentRegion = effectiveRegion().toString();
                S3Client s3Client = (sessionCredentials != null) ?
                        new ClientBuilder<S3Client>().withRegion(currentRegion).build(s3Builder, sessionCredentials) :
                        new ClientBuilder<S3Client>().withRegion(currentRegion).build(s3Builder);

                Map<String,String> masterOutputParameters = new HashMap<>();
                if(artifacts && (copyAction == ArtifactCopyAction.BEFORE)) {

                    String filter = (secondaryStackGroups == null) ?
                            null : secondaryStackGroups[itemCount].repositoryFilter;

                    storeArtifact(s3Client, jars, filter, masterOutputParameters);
                }

                File templateFile = new File(stackPath);
                String templateName = (templateS3Prefix != null ? templateS3Prefix + "/" : "") +
                        ZonedDateTime.now().toEpochSecond() + "-" + stackName + "-" +
                        templateFile.getName();

                String templateUrl = "https://s3.amazonaws.com/" + templateS3Bucket + "/" + templateName;
                audit.write("Template URL: " + templateUrl + "\n");

                PutObjectRequest templateRequest = PutObjectRequest.builder().bucket(templateS3Bucket).key(templateName).build();
                s3Client.putObject(templateRequest, RequestBody.fromFile(templateFile));

                CloudFormationAsyncClient cfAsyncClient;
                boolean testedRegionCondition = testRegionCondition(regionCondition, regionConditionExclude);
                if((regionCondition != null) && !testedRegionCondition && regionConditionElseStackReadOnly) {
                    region = region == null ? regionCondition : region;

                } else {
                    if((regionCondition != null) && testedRegionCondition) region = region == null ? regionCondition : region;
                    else if((region == null) && (deploymentRegionOverride != null)) region = deploymentRegionOverride;
                }

                System.out.println("Region: " + (region == null ? "Default" : region));
                if(sessionCredentials != null) cfAsyncClient = (region == null) ?
                        new ClientBuilder<CloudFormationAsyncClient>().build(cfAsyncBuilder, sessionCredentials) :
                        new ClientBuilder<CloudFormationAsyncClient>().withRegion(region).build(cfAsyncBuilder, sessionCredentials);

                else cfAsyncClient = (region == null) ?
                        new ClientBuilder<CloudFormationAsyncClient>().build(cfAsyncBuilder) :
                        new ClientBuilder<CloudFormationAsyncClient>().withRegion(region).build(cfAsyncBuilder);

                // Read in the cloud formation template.
                audit.write("Stack Parameter Path: " + stackParameterFilePaths[itemCount] + "\n");

                if(testedRegionCondition || regionConditionElseStackReadOnly) {

                    if(!testedRegionCondition && regionConditionElseStackReadOnly) stackReadOnly = true;
                    String effectiveRegion = (region == null) && (deploymentRegionOverride != null) ? deploymentRegionOverride : region;

                    ExecuteTemplate(stackReadOnly, templateUrl, stackParameterFilePaths[itemCount], cfAsyncClient, s3Client,
                            stackName, null, null, sessionCredentials, inputParameters,
                            masterOutputParameters, outputParameterMappings, cliCommandOutputParameterMappings,
                            null, effectiveRegion);
                }

                if(artifacts && (copyAction == ArtifactCopyAction.AFTER)) {

                    String filter = (secondaryStackGroups == null) ?
                            null : secondaryStackGroups[itemCount].repositoryFilter;

                    storeArtifact(s3Client, jars, filter, masterOutputParameters);
                }

                if(secondaryStackGroupCount > 0) {

                    // Reset the session credentials if the time is approaching the time limit.
                    if(LocalDateTime.now().toEpochSecond(ZoneOffset.UTC) - time > 3400) {

                        sessionCredentials = getAwsCredentialsProvider(roleArn);
                        time = LocalDateTime.now().toEpochSecond(ZoneOffset.UTC);
                    }

                    Map<String,String> outputParameters = new HashMap<>(masterOutputParameters);

                    // Process each stack in the stack group.
                    for(SecondaryStack stack : secondaryStackGroups[itemCount].stacks) {

                        // Renew S3 client
                        testedRegionCondition = testRegionCondition(stack.regionCondition, stack.regionConditionExclude);
                        if((stack.regionCondition != null) && !testedRegionCondition && stack.regionConditionElseStackReadOnly) {
                            stack.region = stack.region == null ? stack.regionCondition : stack.region;

                        } else {
                            if ((stack.regionCondition != null) && testedRegionCondition) {
                                stack.region = stack.region == null ? stack.regionCondition : stack.region;

                            } else if ((stack.region == null) && (deploymentRegionOverride != null))
                                stack.region = deploymentRegionOverride;
                        }

                        System.out.println("Stack Region: " + (stack.region == null ? "Empty" : stack.region));
                        currentRegion = effectiveRegion().toString();
                        s3Client = (sessionCredentials != null) ?
                                new ClientBuilder<S3Client>().withRegion(currentRegion).build(s3Builder, sessionCredentials) :
                                new ClientBuilder<S3Client>().withRegion(currentRegion).build(s3Builder);

                        AwsCredentialsProvider stackCredentials;
                        CloudFormationAsyncClient tempCfAsyncClient = cfAsyncClient;
                        if(stack.roleArn != null) {

                            stackCredentials = getAwsCredentialsProvider(stack.roleArn);

                            tempCfAsyncClient = stack.region == null ?
                                    new ClientBuilder<CloudFormationAsyncClient>().build(cfAsyncBuilder, stackCredentials) :
                                    new ClientBuilder<CloudFormationAsyncClient>().withRegion(stack.region).build(cfAsyncBuilder, stackCredentials);

                        } else {

                            stackCredentials = sessionCredentials;
                            if(stack.region != null) {
                                tempCfAsyncClient = stackCredentials == null ?
                                        new ClientBuilder<CloudFormationAsyncClient>().withRegion(stack.region).build(cfAsyncBuilder) :
                                        new ClientBuilder<CloudFormationAsyncClient>().withRegion(stack.region).build(cfAsyncBuilder, stackCredentials);
                            }
                        }

                        // Read in the cloud formation template.
                        String secondaryStackName = (stack.stackName == null) ?
                                stack.stackNamePrefix + "-" + UUID.randomUUID().toString() + "Stack" :
                                stack.stackName;

                        templateFile = new File(stack.stackPath);
                        templateName = (templateS3Prefix != null ? templateS3Prefix + "/" : "") +
                                ZonedDateTime.now().toEpochSecond() + "-" + secondaryStackName +
                                "-" + templateFile.getName();

                        templateRequest = PutObjectRequest.builder().bucket(templateS3Bucket).key(templateName).build();
                        s3Client.putObject(templateRequest, RequestBody.fromFile(templateFile));
                        templateUrl = "https://s3.amazonaws.com/" + templateS3Bucket + "/" + templateName;

                        if(testedRegionCondition || stack.regionConditionElseStackReadOnly) {

                            if(!testedRegionCondition && stack.regionConditionElseStackReadOnly) stack.stackReadOnly = true;
                            String effectiveRegion = (stack.region == null) && (deploymentRegionOverride != null) ? deploymentRegionOverride : stack.region;

                            ExecuteTemplate(stack.stackReadOnly, templateUrl, stack.stackParameterFilePath,
                                    tempCfAsyncClient, s3Client, secondaryStackName, stack.condition,
                                    stack.deploymentArtifactRegEx, stackCredentials, stack.inputParameters,
                                    outputParameters, stack.outputParameterMappings,
                                    stack.cliCommandOutputParameterMappings, stack.checkCondition, effectiveRegion);
                        }
                    }
                }
            }
        }
        catch ( Exception e ) {

            try {

                audit.write("Error executing the template stack or stack group.\n");
                audit.write(e.getMessage() + "\n");
                e.printStackTrace();

            } catch (IOException ex) {

                // ignore
            }

            throw new MojoExecutionException( "Error executing the template stack or stack group.", e );

        } finally {

            if ( audit != null ) {
                try {

                    audit.close();

                } catch ( IOException e ) {

                    // ignore
                }
            }
        }
    }

    /**
     * Use this method to place the artifact in a s3 bucket.  The method also sets some output parameters that may be
     * used by stacks to work with the artifact.
     *
     * @param s3Client is the S3 API client to use when writing artifacts to an S3 bucket.
     * @param jars is the artifact to place in the bucket.
     * @param filter is an expression in the file name to look for when processing a stack group.
     * @param outputParameters is an array of output parameters where bucket, key, and hash are stored.
     * @throws IOException when the method can't invoke the S3 client.
     * @throws NoSuchAlgorithmException when the method can't generate the hash code for the artifact.
     */
    private void storeArtifact(S3Client s3Client, File[] jars, String filter,
                               Map<String, String> outputParameters)
            throws IOException, NoSuchAlgorithmException {

        File jarFile = getFile(jars, filter);

        audit.write("About to copy " + jarFile.getName() + " to S3.\n");

        String artifactName = s3Prefix != null ? s3Prefix + "/" + jarFile.getName() : jarFile.getName();

        s3Client.putObject(PutObjectRequest.builder().bucket(s3Bucket).key(artifactName).build(),
                RequestBody.fromFile(jarFile));
        audit.write(artifactName + " was copied to the s3 bucket (" + s3Bucket + ").\n");

        String sb = getBase64SHA256HashString(jarFile);
        audit.write("Base64 Encoded SHA256 HASH value: " + sb + "\n");

        outputParameters.put("ArtifactS3Bucket", s3Bucket);
        outputParameters.put("ArtifactS3Key", artifactName);
        outputParameters.put("CodeSHA256", sb);
    }

    /**
     * Use this method to retrieve a set of credentials from a role.
     *
     * @return the set of credentials to assume.
     * @throws IOException when we can't write to the audit log.
     * @throws MojoExecutionException when an error occurs trying to assume a role.
     */
    private AwsCredentialsProvider getAwsCredentialsProvider(String roleArn) throws IOException, MojoExecutionException {

        AwsCredentialsProvider sessionCredentials = null;

        if(roleArn == null) audit.write("roleArn: From default provider chain.\n");
        else {

            Credentials credentials;

            audit.write("roleArn: " + roleArn + "\n");

            try {

                credentials = new ClientBuilder<StsClient>().build(stsBuilder)
                        .assumeRole(AssumeRoleRequest.builder()
                                .roleArn(roleArn)
                                .roleSessionName(UUID.randomUUID().toString())
                                .build())
                        .credentials();

            } catch(Exception ex) {

                audit.write("Was't able to assume user.\n\t");
                audit.write(ex.getMessage() + "\n");

                throw new MojoExecutionException( "Unable to assume role.", ex);
            }

            sessionCredentials = StaticCredentialsProvider.create(AwsSessionCredentials.create(
                    credentials.accessKeyId(),
                    credentials.secretAccessKey(),
                    credentials.sessionToken()));

            audit.write("Role assumed.\n");
        }

        return sessionCredentials;
    }

    /**
     * Use this method to execute a template.  Replace input parameters; execute template and extract output parameters.
     *
     * @param readOnly is a flag to signal if the template is used to read the output parameters without update.
     * @param templateUrl contains the URL to the template to execute.
     * @param stackParameterFilePath is the file path to the parameter file to use with the template.
     * @param cfAsyncClient is the cloud formation client to use when performing the update or create stack.
     * @param s3client is a client to use when overriding the master stack artifact.
     * @param stackName is the name of the stack to create when executing the cloud formation template.
     * @param condition is the name of the condition for the template.
     * @param deploymentArtifactRegEx is the regular expression to use to find the deployment file.
     * @param credentials is the set of credential to use for the system manager.
     * @param inputParameters are the input parameters to substitute before executing the template.
     * @param outputParameters are the output parameters from this and previous template operations.
     * @param outputParameterMappings are the mappings to apply to output parameters.
     * @param cliCommandOutputParameterMappings is a set of CLI command to run to retrieve output parameters.
     * @param checkCondition is a condition to check against the value of an output parameter.
     * @param region is the region to use for executing templates and storing parameters.
     * @throws IOException when the parameter file can't be read from.
     * @throws InterruptedException when the operating system interrupts the execution of a CLI Command.
     * @throws NoSuchAlgorithmException when it to calculate a file hash.
     * @throws MojoExecutionException when the stack couldn't execute for a reason other then no changes.
     */
    private void ExecuteTemplate(Boolean readOnly, String templateUrl, String stackParameterFilePath,
                                 CloudFormationAsyncClient cfAsyncClient,
                                 S3Client s3client, String stackName, String condition,
                                 String deploymentArtifactRegEx, AwsCredentialsProvider credentials,
                                 StackInputParameter[] inputParameters, Map<String, String> outputParameters,
                                 StackOutputParameterMapping[] outputParameterMappings,
                                 CliCommandOutputParameterMapping[] cliCommandOutputParameterMappings,
                                 ParameterValueCheckCondition checkCondition, String region)
            throws IOException, InterruptedException, NoSuchAlgorithmException, MojoExecutionException {

        // Determine if the stack exists.
        boolean cloudFormationExists = isTemplatePreviouslyDeployed(cfAsyncClient, stackName);

        String auditString;
        Predicate<StackInputParameter> sha = param -> param.parameterName.equals("CodeSHA256");
        if(readOnly) {

            auditString = (cloudFormationExists ? ("Reading the output from " + stackName + ".\n") :
                    stackName + " is not required for for this deployment.\n");

        } else auditString = (cloudFormationExists ? "Updating" : "Creating") + " the CloudFormation Stack (" +
                stackName + ").\n";

        // Determine if the template is required in this deployment.
        if(shouldExecuteStack(condition) && evaluateCheckCondition(checkCondition, outputParameters)) {

            // Continue processing the template.
            audit.write(auditString);
            System.out.print(auditString);

            if(!readOnly) {

                storeDeploymentArtifact(s3client, deploymentArtifactRegEx, outputParameters);

                // Read the stack parameters.
                Parameter[] parameters = getInputParameters(stackParameterFilePath, credentials, inputParameters,
                        outputParameters, region);

                if((inputParameters != null) && Arrays.stream(inputParameters).anyMatch(sha)) Thread.sleep(10000);

                // Check to see if the stack has changes to process.
                if (cloudFormationExists) {

                    DetectAndProcessStackChanges(templateUrl, cfAsyncClient, stackName, parameters);

                } else {

                    // Create or update the Stack.
                    createStack(stackName, templateUrl, cfAsyncClient, parameters);

                    audit.write("Stack Finished.\n");
                    System.out.println("Stack Finished.");
                }
            }

            System.out.println();

            // Add output parameters from the stack run and optionally save them to the Parameter Store.
            SsmClient ssmClient;
            if(region != null) ssmClient = (credentials != null) ?
                    new ClientBuilder<SsmClient>().withRegion(region).build(ssmBuilder, credentials) :
                    new ClientBuilder<SsmClient>().withRegion(region).build((ssmBuilder));

            else ssmClient = (credentials != null) ?
                    new ClientBuilder<SsmClient>().build(ssmBuilder, credentials) :
                    new ClientBuilder<SsmClient>().build(ssmBuilder);


            processOutputParameters(cfAsyncClient, stackName, credentials, ssmClient, outputParameters,
                    outputParameterMappings, region);

            processCommandOutputParameters(credentials, ssmClient, outputParameters,
                    cliCommandOutputParameterMappings, region);

        } else {

            audit.write(stackName + " is not required.\n");
            System.out.println( stackName + " is not required.");
            System.out.println();
        }
    }

    /**
     * This method checks to see if the master artifact is overriden with a stack specific artifact.  If it is, the
     * method finds the artfact on the local file system and saves it to S3 using the s3Bucket and s3Prefix.
     *
     * @param s3client is the S3 client to use when saving the artifact.
     * @param deploymentArtifactRegEx is a regular expresion for a file to find.
     * @param outputParameters is a collection of output parameters.
     * @throws IOException is throw when it can't perfom a file or path function call.
     * @throws MojoExecutionException is thrown if the regular expression is bad.
     * @throws NoSuchAlgorithmException is thrown if the SHA-256 algorithm doesn't exist for calculating a file hash.
     */
    private void storeDeploymentArtifact(S3Client s3client, String deploymentArtifactRegEx,
                                         Map<String, String> outputParameters)
            throws IOException, MojoExecutionException, NoSuchAlgorithmException {

        if(deploymentArtifactRegEx != null) {

            audit.write("Finding a deployment artifact using regex: " + deploymentArtifactRegEx + "\n");
            if(!Pattern.matches(
                    "\\^?((/[A-Za-z0-9:\\[\\]{}_\\ -])+(.[A-Za-z0-9_-]+)?|[A-Za-z0-9:\\[\\]{}_\\ -.]+)[$]?",
                    deploymentArtifactRegEx))
                throw new MojoExecutionException("Invalid deployment artifact regular expression.");

            int endPos = deploymentArtifactRegEx.lastIndexOf('/');
            String path;
            String regex;
            if(endPos > -1) {

                path = deploymentArtifactRegEx.charAt(0) == '^' ?
                        deploymentArtifactRegEx.substring(1, endPos) :
                        deploymentArtifactRegEx.substring(0, endPos);

                regex = deploymentArtifactRegEx.substring(endPos + 1);

            } else {

                path = Paths.get("").toAbsolutePath().toString();
                regex = deploymentArtifactRegEx;
            }

            List<File> files = Files.find(Paths.get(path), 1, (file, attributes) ->
                    FileSystems.getDefault().getPathMatcher("regex:" + regex)
                            .matches(file.getFileName()))
                    .collect(ArrayList::new, (list, filePath) -> list.add(new File(filePath.toUri())), (f1, f2) -> {});

            if(files.size() != 1)
                throw new MojoExecutionException("Couldn't find deployment artifact.");

            audit.write("Deployment artifact: " + files.get(0).getName() + "\n");
            System.out.println("Deploying artifact to S3: " + files.get(0).getName());
            storeArtifact(s3client, files.toArray(new File[0]), null, outputParameters);
        }
    }

    /**
     * Test the condition to determine if the parameter value equals a check value.  If the check condition is null,
     * it is assumed that there is no check condition so the test passes.  If the parameter is not found in the
     * output parameters, it is considered an error.
     *
     * @param checkCondition is the check condition to evaluate.
     * @param outputParameters are the output parameters to search and test against.
     * @return a flag indicating if the check condition is satisfied.
     * @throws MojoExecutionException when the parameter doesn't exist in the output parameters.
     */
    private boolean evaluateCheckCondition(ParameterValueCheckCondition checkCondition,
                                           Map<String, String> outputParameters) throws MojoExecutionException {

        boolean test = true;
        if(checkCondition != null) {

            if(!outputParameters.containsKey(checkCondition.parameterName))
                throw new MojoExecutionException("Check Condition Parameter doesn't exist!");

            test = outputParameters.get(checkCondition.parameterName).equals(checkCondition.checkValue);
        }

        return test;
    }

    /**
     * This function test to see if the current region condition is satisfied and the current region exclusion is not satisfied.
     * If the current region mapping and exclusion are null, then it is assumed that there are no region restrictions and the mapping
     * is allowed.  A validRegion or an excludeRegion may be specified but not both.  One must be null.  Both parameter can be null.
     * If the region is specified and it matches the current region then it is satisfied and the mapping is allowed
     * to continue.  If an region is excluded and the current region matches the exclusion, the template is not allowed to continue.
     * The current region is set by the deploymentRegionOverride, if set.  Otherwise, the current region is set from the default
     * credentials chain.
     *
     * @param validRegion is the value of the region where the mapping is allowed.  It may be null.
     * @param excludeRegion is the value of a region where the mapping is excluded.  It may be null.
     * @return a flag signaling if the mapping is allowed or not.
     * @throws MojoExecutionException when the specified region is not null and not a valid region.
     */
    private boolean testRegionCondition(String validRegion, String excludeRegion) throws MojoExecutionException {

        boolean result = true;

        if((validRegion != null) || (excludeRegion != null)) {

            if((validRegion != null) && (excludeRegion != null))
                throw new MojoExecutionException("Can not specifiy a valid region and a region exclusion.");

            Region region = validRegion != null ? Region.of(validRegion) : null;
            if((region == null) && (validRegion != null)) throw new MojoExecutionException("Invalid region specified.");

            Region exclusion = excludeRegion != null ? Region.of(excludeRegion) : null;
            if((exclusion == null) && (excludeRegion != null)) throw new MojoExecutionException("Invalid exclusion specified.");

            Region currentRegion = effectiveRegion();

            result = ((validRegion != null) && (region == currentRegion)) ||
                    ((excludeRegion != null) && (exclusion != currentRegion));
        }

        return result;
    }

    /**
     * Retrieves the effective region which is the deployment override region when it is specified.  Otherwise, it is the region
     * defined in the default credentials chain.
     *
     * @return The effective region to use.
     * @throws MojoExecutionException is called when the specified deployment override region doesn't exist.
     */
    private Region effectiveRegion() throws MojoExecutionException {
        Region currentRegion;
        if(deploymentRegionOverride == null) {
            DefaultAwsRegionProviderChain chain = new DefaultAwsRegionProviderChain();
            currentRegion = chain.getRegion();
            if(currentRegion == null) currentRegion = Region.US_EAST_1;

        } else {
            currentRegion = Region.of(deploymentRegionOverride);
            if(currentRegion == null) throw new MojoExecutionException("Invalid override region specified.");
        }
        return currentRegion;
    }

    /**
     * Use this method to evaluate parameters from the execution of a describe AWS command.
     *
     * @param credentials is a default set of credentials.
     * @param outputParameters is the output parameters array to populate with the results from the command.
     * @param parameterMappings are the mappings to execute.
     * @param ssmClient is the client to use for the parameter store.
     * @param region is the region to store parameters in.
     * @throws IOException when an exception occurs while reading/writing to process or file system.
     * @throws ClassCastException when the parameter isn't a string.  This should never happen (bug if it does).
     * @throws MojoExecutionException when it can't find a parameter.
     */
    private void processCommandOutputParameters(AwsCredentialsProvider credentials,
                                                SsmClient ssmClient,
                                                Map<String, String> outputParameters,
                                                CliCommandOutputParameterMapping[] parameterMappings, String region)
            throws IOException, MojoExecutionException, ClassCastException {

        if(parameterMappings != null) {

            System.out.println("Command Output Parameter Mappings:");

            for (CliCommandOutputParameterMapping mapping : parameterMappings) {

                if (((mapping.condition == null) || conditions.get(mapping.condition)) &&
                        evaluateCheckCondition(mapping.checkCondition, outputParameters) &&
                        testRegionCondition(mapping.regionCondition, mapping.regionConditionExclude)) {

                    // Retrieve the credential values and set them for passing to the environment variables.
                    HashMap<String, String> environmentMap = new HashMap<>();
                    getCredentialMap(credentials, mapping.roleArn, environmentMap);

                    StringBuilder mappingCommand = new StringBuilder(mapping.command);
                    if (mapping.commandParameters != null) {

                        for (StackInputParameter commandParameter : mapping.commandParameters) {

                            String parameterValue = getInputParameterValue(outputParameters, ssmClient,
                                    commandParameter);

                            if(mapping.commandParameterSpacing) {

                                mappingCommand
                                        .append(" ")
                                        .append(commandParameter.parameterName)
                                        .append(" ")
                                        .append(parameterValue);

                            } else {

                                mappingCommand.append(commandParameter.parameterName).append(parameterValue);
                            }
                        }
                    }
                    String builtCommand = mappingCommand.toString().replace("{SPACE}", " ");

                    audit.write("Executing: " + mappingCommand.toString() + "\n");
                    ExecuteCommand command = new ExecuteCommand()
                            .withCommandInformation(builtCommand.split("\\s+(?=(?:[^\'\"`]*(?:(\')[^\']*\\1|(\")[^\"]*\\2|(`)[^`]*\\3))*[^\'\"`]*$)"))
                            .withEnvironmentMap(environmentMap);

                    command.executeCommand();

                    if(command.getExecutionErrors() != null) {

                        Arrays.stream(command.getExecutionErrors())
                                .forEach(error -> {

                                        try {

                                            audit.write(error.getMessage() + "\n");

                                        } catch (IOException ioex) { /* Ignore */ }
                                });

                        throw new RuntimeException("Unable to execute command.");

                    } else {

                        String errors = command.getStandardErrorFromCommand().toString();
                        if(errors.length() > 0) {

                            System.out.println("Errors: " + errors);
                            audit.write("Errors: " + errors + "\n");
                            throw new RuntimeException("Unable to execute command.");
                        }
                    }

                    if (mapping.parameters != null) {

                        String output = command.getStandardOutputFromCommand().toString();
                        ObjectMapper mapper = new ObjectMapper();
                        LinkedHashMap map = mapper.readValue(output, LinkedHashMap.class);

                        for (String key : mapping.parameters.keySet()) {

                            Pattern pattern = Pattern.compile("^(/[A-Za-z0-9_-]+(\\[[A-Za-z0-9_/= -]+\\])?)+$");
                            Matcher matcher = pattern.matcher(mapping.parameters.get(key).parameterName);
                            if(!matcher.matches()) throw new MojoExecutionException("Invalid parameter name syntax.");

                            String[] pathElements = mapping.parameters.get(key).parameterName.split("/(?!(?:[^\\[]+\\]))");
                            Object parameter = map;
                            String array = null;
                            for (String element : pathElements) {

                                if(array == null) {

                                    if (!parameter.getClass().isAssignableFrom(LinkedHashMap.class))
                                        throw new MojoExecutionException("Element is not a Dictionary");

                                } else {

                                    if (!parameter.getClass().isAssignableFrom(ArrayList.class))
                                        throw new MojoExecutionException("Element is not an Array.");
                                }

                                if (element.length() > 0) {

                                    pattern = Pattern.compile(
                                            "^([A-Za-z0-9_-]+)(\\[([0-9]+|[A-Za-z0-9_-]+=[A-Za-z0-9_ /-]+)\\])?$");

                                    matcher = pattern.matcher(element);
                                    if (!matcher.matches())
                                        throw new MojoExecutionException("Couldn't find parameter: " + element);

                                    String name = matcher.group(1);
                                    if (array != null) {

                                        parameter = processArray(parameter, array, false);
                                        if(parameter == null) break;
                                    }

                                    parameter = ((LinkedHashMap) parameter).get(name);
                                    array = matcher.group(3);
                                }
                            }

                            if ((parameter == null) || !parameter.getClass().isAssignableFrom(String.class)) {

                                if ((parameter != null) && parameter.getClass().isAssignableFrom(ArrayList.class) &&
                                        (array != null)) parameter = processArray(parameter, array, true);

                                if ((parameter == null) || !parameter.getClass().isAssignableFrom(String.class)) {

                                    if (mapping.parameters.get(key).defaultParameterValue != null) {

                                        parameter = mapping.parameters.get(key).defaultParameterValue;

                                    } else throw new MojoExecutionException("Couldn't find parameter: " + key);
                                }
                            }

                            //noinspection ConstantConditions
                            ProcessMapping(outputParameters, ssmClient, key, (String) parameter,
                                    mapping.parameters.get(key), region);
                        }
                    }
                }
            }

            System.out.println();
        }
    }

    /**
     * This method process a parameter as if it where an object representing an array list of dictionary items or
     * strings.  It extracts the array element from the array list based on an index or a dictionary filter.
     *
     * @param parameter is the parameter that represents an array list to process.
     * @param array is the contents of the array brackets which could be an index or a dictionary filter.
     * @param extractString signals if this is the intent is to process and array of strings.
     * @return the contents of the selected array element which could be a dictionary or string.
     */
    private Object processArray(Object parameter, String array, boolean extractString) throws MojoExecutionException {

        if(!parameter.getClass().isAssignableFrom(ArrayList.class))
            throw new MojoExecutionException("Invalid parameter type.");

        if(Pattern.matches("^[0-9]+$", array)) {

            // Array represents an index.
            if (((ArrayList) parameter).size() >= (Integer.parseInt(array) + 1)) {

                parameter = ((ArrayList) parameter).get(Integer.parseInt(array));
                if(parameter.getClass().isAssignableFrom(String.class) && !extractString) parameter = null;

            } else  parameter = null;

        } else {

            // Array represents a filter query for an array element.
            Pattern pattern = Pattern.compile("^([A-Za-z0-9_-]+)=([A-Za-z0-9_ /-]+)$");
            Matcher arrayMatcher = pattern.matcher(array);
            if(!arrayMatcher.matches())
                throw new MojoExecutionException("Invalid group filter.");

            List list = (List)((ArrayList)parameter)
                    .stream()
                    .filter(item -> ((LinkedHashMap)item).get(arrayMatcher.group(1))
                            .equals(arrayMatcher.group(2)))
                    .collect(Collectors.toList());

            if(list.size() > 1) throw new MojoExecutionException("Too many matches.");
            if(list.size() == 1) parameter = list.get(0);
            else parameter = null;
        }

        return parameter;
    }

    /**
     * Use this method to create a Map of Environment Variables containing the credentials to use for executing a CLI
     * Command.
     *
     * @param credentials are for the overall stack; which may be null if we are using default credentials.
     * @param roleArn is the ARN of a role to use instead of the stack credentials or default credentials.
     * @param environmentMap is the environment variable map to fill in.
     * @throws IOException when the process has trouble calling AWS commands.
     * @throws MojoExecutionException when a process error occurs in the method.
     */
    private void getCredentialMap(AwsCredentialsProvider credentials, String roleArn,
                                  HashMap<String, String> environmentMap)
            throws IOException, MojoExecutionException {

        // If the default credentials are not null
        if(credentials != null) {

            AwsSessionCredentials sessionCredentials;

            sessionCredentials = (AwsSessionCredentials) credentials.resolveCredentials();

            if (roleArn != null) {

                AwsCredentialsProvider session = getAwsCredentialsProvider(roleArn);
                sessionCredentials = (AwsSessionCredentials) session.resolveCredentials();
                audit.write("Using role: " + roleArn + "\n");

            } else audit.write("Using role from stack credentials.\n");

            environmentMap.put("AWS_ACCESS_KEY_ID", sessionCredentials.accessKeyId());
            environmentMap.put("AWS_SECRET_ACCESS_KEY", sessionCredentials.secretAccessKey());
            environmentMap.put("AWS_SESSION_TOKEN", sessionCredentials.sessionToken());

        } else {

            AwsSessionCredentials sessionCredentials;
            if (roleArn != null) {

                AwsCredentialsProvider session = getAwsCredentialsProvider(roleArn);
                sessionCredentials = (AwsSessionCredentials) session.resolveCredentials();
                environmentMap.put("AWS_ACCESS_KEY_ID", sessionCredentials.accessKeyId());
                environmentMap.put("AWS_SECRET_ACCESS_KEY", sessionCredentials.secretAccessKey());
                environmentMap.put("AWS_SESSION_TOKEN", sessionCredentials.sessionToken());
                audit.write("Using role: " + roleArn + "\n");

            } else {

                StsClient stsClient = new ClientBuilder<StsClient>().build(stsBuilder);

                GetCallerIdentityRequest request = GetCallerIdentityRequest.builder().build();
                GetCallerIdentityResponse result = stsClient.getCallerIdentity(request);
                AwsCredentialsProvider session = getAwsCredentialsProvider(result.arn());

                environmentMap.put("AWS_ACCESS_KEY_ID", session.resolveCredentials().accessKeyId());
                environmentMap.put("AWS_SECRET_ACCESS_KEY", session.resolveCredentials().secretAccessKey());

                if(session.resolveCredentials().getClass().isAssignableFrom(AwsSessionCredentials.class)) {

                    sessionCredentials = (AwsSessionCredentials)session.resolveCredentials();
                    environmentMap.put("AWS_SESSION_TOKEN", sessionCredentials.sessionToken());
                }
                audit.write("Using role: " + result.arn() + "\n");
            }
        }

        DefaultAwsRegionProviderChain chain = new DefaultAwsRegionProviderChain();
        Region region = chain.getRegion();
        if(region == null) region = Region.US_EAST_1;

        environmentMap.put("AWS_DEFAULT_REGION", region.toString());
        audit.write("Using region: " + region.toString() + "\n");
    }

    /**
     * Use this method to process the output parameters.  Use the stack output parameter mapping to determine if an
     * output parameter should be renamed before it is placed into the output parameter array.  The parameter may
     * optionally be store in the AWS System Manager Parameter Store.  There may be more than one mapping for a
     * given parameter.  If a map exist for a parameter that wasn't in the output, it is ignored.
     *
     * @param cfClient is the CloudFormation Client to use.
     * @param stackName is the name of the stack to fetch the output parameters from.
     * @param outputParameters is the array of output parameters to put the parameters into.
     * @param region is the region to store the parameter.
     * @param credentials are the credentials to use when connecting to AWS APIs.
     * @param outputParameterMappings is the mapping to use when determine parameter name and parameter value
     * @param ssmClient is the client to use when updating the parameter store.
     * @throws IOException when the routine is unable to assume a role.
     * @throws MojoExecutionException when a logic or validation error occurs with assuming a role.
     */
    private void processOutputParameters(CloudFormationAsyncClient cfClient, String stackName,
                                         AwsCredentialsProvider credentials, SsmClient ssmClient,
                                         Map<String, String> outputParameters,
                                         StackOutputParameterMapping[] outputParameterMappings, String region)
            throws IOException, MojoExecutionException {

        boolean retry;
        DescribeStacksResponse masterResult = null;

        do {

            try {

                masterResult = cfClient.describeStacks(DescribeStacksRequest.builder().stackName(stackName).build()).get();

                retry = false;

            } catch (Exception ex) {

                retry = isRetry(ex);
            }

        } while(retry);

        if((masterResult != null) && (masterResult.stacks().get(0).outputs().size() > 0)) {
            System.out.println("Output Parameters for " + masterResult.stacks().get(0).stackName() + ":");

            // For each output parameter, map it and save it.
            for (Output masterOutput : masterResult.stacks().get(0).outputs()) {

                Boolean mapped = false;

                if (outputParameterMappings != null) {

                    // Map the parameter with all matching rules.  The same parameter may be mapped multiple times.
                    for (StackOutputParameterMapping mapping : outputParameterMappings) {

                        if (masterOutput.outputKey().equals(mapping.parameterName)) {

                            mapped = ProcessMapping(outputParameters, ssmClient, masterOutput.outputKey(),
                                    masterOutput.outputValue(), mapping, region);
                        }
                    }
                }

                // Default action to save parameters without any mappings or parameters with mapping restrictions.
                if (!mapped) {

                    outputParameters.put(masterOutput.outputKey(), masterOutput.outputValue().trim());

                    System.out.println("DEBUG: Output Parameter: " + masterOutput.outputKey());
                    System.out.println("  With value: " + masterOutput.outputValue());
                    System.out.println();
                }
            }

            System.out.println();
        }
    }

    /**
     * This function test to see if an exception is Rate exceeded.  If it is, it sets the result to true signaling
     * that the caller should retry the request.  It also waits for 1 second before returning.  The reason for this
     * error is that we are exceeding the rate of calls to the cloud formation api per second.  Resting for 1 second
     * resets the clock.
     *
     * @param ex is the exception to test for.
     * @return a flag indicating that a rety should occur.
     * @throws MojoExecutionException when the input exception wasn't a rate exceeded exception.
     */
    private boolean isRetry(Exception ex) throws MojoExecutionException{

        // Check the exception message.
        if ((ex.getMessage() != null) && ex.getMessage().contains("Rate exceeded")) {

            try {

                Thread.sleep(1000);

            } catch (InterruptedException iex) {

                /* Ignore */
            }

        } else {

            throw ex.getClass().isAssignableFrom(MojoExecutionException.class) ?
                    (MojoExecutionException) ex :
                    new MojoExecutionException("CloudFormation Error: " + ex.getMessage(), ex);
        }

        return true;
    }

    /**
     * This method process a Stack Output Parameter Mapping.  It saves the parameter to the specified array of
     * output parameters.  It also, if specified, saves the parameter to the System Manager Parameter Store.  It
     * uses the supplied ssmClient to write the parameters to the Parameter Store.
     *
     * @param outputParameters are the output parameters to save the parameter to.
     * @param ssmClient is the client to use when writing parameters to the parameter store.
     * @param parameterName is name of the parameter from the CloudFormation Template or CLI Command.
     * @param mapping is the mapping to process.
     * @param parameterValue is the value of the parameter.
     * @param region is the region to store the parameter.
     * @return a boolean indicating if the mapping was processed (It had no restrictions).
     * @throws MojoExecutionException when a processing error occurs in the method.
     * @throws IOException when the method is unable to read/write to the parameter store.
     */
    private Boolean ProcessMapping(Map<String, String> outputParameters, SsmClient ssmClient,
                                   String parameterName, String parameterValue, StackOutputParameterMapping mapping, String region)
            throws MojoExecutionException, IOException {

        // Check for mapping restriction by looking at the condition for executing the mapping.
        Boolean noMappingRestrictions = true;

        if(mapping.condition != null) {

            if(!conditions.containsKey(mapping.condition))
                throw new MojoExecutionException("Condition doesn't exist in the conditions map.");

            noMappingRestrictions = conditions.get(mapping.condition);
        }

        // If there are no restrictions and the parameter has a mapping, perform the mapping.
        String name = parameterName;

        if(noMappingRestrictions) {

            // Store the parameter in System Manager Parameter Store if a field is specified.
            if(mapping.parameterStoreFieldName != null) {

                SsmClient client = ssmClient;
                if(mapping.roleArn != null) {

                    AwsCredentialsProvider session = getAwsCredentialsProvider(mapping.roleArn);

                    client = region != null ?
                            new ClientBuilder<SsmClient>().withRegion(region).build(ssmBuilder, session) :
                            new ClientBuilder<SsmClient>().build(ssmBuilder, session);
                }

                boolean update = false;
                try {

                    System.out.println("DEBUG: Getting Parameter: " + mapping.parameterStoreFieldName);
                    GetParameterRequest getParameterRequest = GetParameterRequest.builder()
                            .name(mapping.parameterStoreFieldName)
                            .withDecryption(true)
                            .build();

                    GetParameterResponse result = client.getParameter(getParameterRequest);

                    if(!result.parameter().value().trim()
                            .equals(parameterValue.trim())) {

                        update = true;
                    }

                } catch (ParameterNotFoundException ex)
                {
                    update = true;
                }

                if(update) {

                    System.out.println("DEBUG: Putting Parameter: " + name);
                    System.out.println("  With Parameter Type: " + mapping.parameterStoreFieldType);

                    if(mapping.parameterStoreFieldType != ParameterType.SECURE_STRING)
                        System.out.println("  With value: " + parameterValue);

                    System.out.println("  With Description: " + mapping.description);
                    System.out.println("  To: " + mapping.parameterStoreFieldName);
                    if(mapping.mapParameterName != null) System.out.println("And: " + mapping.mapParameterName);
                    System.out.println();

                    PutParameterRequest parameterRequest = PutParameterRequest.builder()
                            .name(mapping.parameterStoreFieldName)
                            .overwrite(true)
                            .description(mapping.description)
                            .type(mapping.parameterStoreFieldType.toString())
                            .value(parameterValue)
                            .build();

                    client.putParameter(parameterRequest);

                } else {

                    System.out.println("DEBUG: Output Parameter: " + name);

                    if(mapping.parameterStoreFieldType != ParameterType.SECURE_STRING)
                        System.out.println("  With value: " + parameterValue);

                    System.out.println("  With Description: " + mapping.description);
                    if( mapping.mapParameterName != null) System.out.println("  To: " + mapping.mapParameterName);
                    System.out.println();
                }

            } else {

                System.out.println("DEBUG: Output Parameter: " + name);
                System.out.println("  With value: " + parameterValue);
                System.out.println("  With Description: " + mapping.description);
                if( mapping.mapParameterName != null) System.out.println("  To: " + mapping.mapParameterName);
                System.out.println();
            }

            // Map the name if a mapping is specified.
            if(mapping.mapParameterName != null) name = mapping.mapParameterName;

            // Save the parameter to the output parameter array.
            outputParameters.put(name, parameterValue.trim());
        }

        return noMappingRestrictions;
    }

    /**
     * Use this method to determine if the CloudFormation Template was previously deployed to AWS.
     *
     * @param cfClient is the CloudFormation Client to use.
     * @param stackName is the name of the stack to investigate.
     * @return a flag indicating if the template was previously deployed.
     */
    private boolean isTemplatePreviouslyDeployed(CloudFormationAsyncClient cfClient, String stackName)  {
        boolean cloudFormationExists = true;
        boolean retry;
        int retry_count = 0;
        Random random = new Random();

        do {

            try {

                DescribeStacksResponse result = cfClient.describeStacks(DescribeStacksRequest.builder().stackName(stackName).build()).get();
                if (((result.stacks() == null) || (result.stacks().size() < 1)) && (retry_count == 2)) {

                    cloudFormationExists = false;
                    retry = false;

                } else if((result.stacks() != null) && (result.stacks().size() >= 1)) {

                    retry = false;

                } else if(retry_count < 2){

                    retry = true;
                    Thread.sleep(random.nextInt(5000) + 1);

                } else retry = false;

            } catch (InterruptedException ie) {

                // Ignore interruptions and retry.
                retry = true;

            } catch (ExecutionException acfEx) {

                if (acfEx.getCause().getMessage().contains("Rate exceeded")) {

                    retry = true;

                } else if (acfEx.getCause().getMessage().contains("does not exist")) {

                    retry = false;
                    cloudFormationExists = false;

                } else {

                    System.out.println("Error encountered (retry): " + acfEx.getMessage());
                    retry = retry_count < 2;
                    if(!retry) cloudFormationExists = false;
                }

                try { Thread.sleep(random.nextInt(5000) + 1); } catch (InterruptedException iex) {  /* Ignore */ }
            }

            retry_count++;

        } while(retry);

        return cloudFormationExists;
    }

    /**
     * Use the method to determine if the stack should be executed.  A condition may exist on the stack that signals
     * when the stack is required or not in the current deployment.
     *
     * @param condition is the name of a condition to investigate.
     * @return a flag indicating if the condition is true or false.
     * @throws MojoExecutionException when a validation or logic error occurs.
     */
    private boolean shouldExecuteStack(String condition) throws MojoExecutionException {

        boolean execute = true;
        if((conditions == null) && (condition != null))
            throw new MojoExecutionException("The condition map is missing even though stack condition exists.");

        if((conditions != null) && (condition != null)) {

            boolean found = false;

            for (Map.Entry<String, Boolean> entry : conditions.entrySet()) {

                if (entry.getKey().equals(condition)) {

                    execute = entry.getValue();
                    found = true;
                    break;
                }
            }

            if(!found) throw new MojoExecutionException("Condition not found in condition map.");
        }

        return execute;
    }

    /**
     * Use this function to determine if an update to an existing stack will produce any changes.  If it will produce
     * changes, execute the change set.  If it doesn't produce any changes, don't execute the stack and inform the
     * user and audit of the the fact that there are no changes to be made.
     *
     * @param templateUrl is the URL to the template to deploy.
     * @param cfAsyncClient is the CloudFormation Client to use when inquiring about stacks and deploying changes.
     * @param stackName is the name of the stack to update.
     * @param parameters are the parameters to update the stack with.
     * @throws MojoExecutionException when a validation or logic error occurs.
     * @throws IOException when an error occurs trying to connect to the AWS API.
     */
    private void DetectAndProcessStackChanges(String templateUrl, CloudFormationAsyncClient cfAsyncClient, String stackName,
                                              Parameter[] parameters) throws MojoExecutionException, IOException {

        boolean retry;

        String changeSetName = "N-" + UUID.randomUUID().toString();
        String changeSetToken = UUID.randomUUID().toString();
        CreateChangeSetRequest changeSetRequest;
        if(requiresIAM) {
            changeSetRequest = CreateChangeSetRequest.builder()
                    .parameters(parameters)
                    .stackName(stackName)
                    .changeSetType("UPDATE")
                    .templateURL(templateUrl)
                    .changeSetName(changeSetName)
                    .usePreviousTemplate(false)
                    .clientToken(changeSetToken)
                    .capabilities(Capability.CAPABILITY_NAMED_IAM)
                    .build();
        } else {
            changeSetRequest = CreateChangeSetRequest.builder()
                    .parameters(parameters)
                    .stackName(stackName)
                    .changeSetType("UPDATE")
                    .templateURL(templateUrl)
                    .changeSetName(changeSetName)
                    .usePreviousTemplate(false)
                    .clientToken(changeSetToken)
                    .build();
        }

        DescribeChangeSetResponse describeStacksResult = null;

        do {

            try {

                cfAsyncClient.createChangeSet(changeSetRequest).thenRunAsync(() ->
                    WaitChangeSet(cfAsyncClient, stackName, changeSetName)).join();

                changeSetToken = UUID.randomUUID().toString();
                DescribeChangeSetRequest describeChangeSetRequest = DescribeChangeSetRequest.builder()
                        .changeSetName(changeSetName)
                        .stackName(stackName)
                        .nextToken(changeSetToken)
                        .build();

                describeStacksResult = cfAsyncClient.describeChangeSet(describeChangeSetRequest).get();

                if((describeStacksResult.status() == ChangeSetStatus.FAILED)) {
                    if ((describeStacksResult.changes().size() <= 0) &&
                            describeStacksResult.statusReason()
                                    .startsWith("The submitted information didn't contain changes.")) {

                        DeleteChangeSet(cfAsyncClient, describeStacksResult.stackName(), describeStacksResult.changeSetName());
                        retry = false;

                    } else if (describeStacksResult.statusReason().contains("Rate exceeded")) {

                        retry = true;

                    } else {

                        throw new MojoExecutionException("ChangeSet Error: " + describeStacksResult.statusReason());
                    }

                } else retry = false;

            } catch (MojoExecutionException mojo) {

                throw mojo;

            } catch (Exception ex) {

                retry = isRetry(ex);
                if(!retry) throw new MojoExecutionException(ex.getMessage(), ex);
            }

        } while(retry);

        // Process any changes
        if ((describeStacksResult != null) && (describeStacksResult.changes().size() > 0)) {

            int retry_count = 0;
            do {

                try {
                    ExecuteChangeSetRequest executeChangeSetRequest = ExecuteChangeSetRequest.builder()
                            .changeSetName(changeSetName)
                            .stackName(stackName)
                            .clientRequestToken(changeSetToken)
                            .build();

                    cfAsyncClient.executeChangeSet(executeChangeSetRequest).get();
                    WaitStackInProgress(stackName, cfAsyncClient);

                    DescribeStacksResponse describeResponse = cfAsyncClient.describeStacks(DescribeStacksRequest.builder()
                            .stackName(stackName).build()).get();

                    if(describeResponse.stacks().size() == 1) {
                        switch(describeResponse.stacks().get(0).stackStatus()) {

                            case UPDATE_ROLLBACK_COMPLETE:
                            case UPDATE_ROLLBACK_FAILED:
                                String reason = (describeResponse.stacks() != null) && (describeResponse.stacks().size() == 1) &&
                                        (describeResponse.stacks().get(0).stackStatusReason() != null) ?
                                        "CloudFormation Error: " + describeResponse.stacks().get(0).stackStatusReason() :
                                        "See CloudFormation Console for errors.";

                                throw new MojoExecutionException("CloudFormation Error: " + reason);
                        }

                        retry = false;

                    } else {
                        if (retry_count == 2) throw new MojoExecutionException("Invalid response from stack update.");
                        retry = true;
                    }

                } catch (Exception ex) {

                    retry = isRetry(ex);
                }

                retry_count++;

            } while(retry);

            audit.write("Updated " + stackName + " with id: " +
                    describeStacksResult.stackId() + ".\n");

            audit.write("Stack Finished.\n");
            System.out.println("Stack Finished.");

        } else {

            audit.write("No changes to the Stack required.\n");
            System.out.println("No changes to the Stack required.");
        }
    }

    /**
     * Waits for a change set to complete.
     * @param cfAsyncClient
     * @param stackName
     * @param changeSetName
     */
    private void WaitChangeSet(CloudFormationAsyncClient cfAsyncClient, String stackName, String changeSetName) {

        boolean retry;
        Random random = new Random();
        do {
            String token = UUID.randomUUID().toString();
            DescribeChangeSetRequest describeChangeSetRequest = DescribeChangeSetRequest.builder()
                    .changeSetName(changeSetName)
                    .stackName(stackName)
                    .nextToken(token)
                    .build();

            try {

                DescribeChangeSetResponse result = cfAsyncClient.describeChangeSet(describeChangeSetRequest).get();
                retry = (result.status() == ChangeSetStatus.CREATE_PENDING) ||
                        (result.status() == ChangeSetStatus.CREATE_IN_PROGRESS);

                if(retry) Thread.sleep(random.nextInt(10000) + 1);

            } catch (InterruptedException ie) { retry = true; }
            catch (ExecutionException ex) { retry = ex.getMessage().contains("Rate exceeded"); }

        } while (retry);
    }

    /**
     * This routine deletes a specified change set.
     *
     * @param cfAsyncClient The CloudFormation client to use.
     * @param stackName The name of the stack to delete the change set from.
     * @param changeSetName The Name of the change set to delete.
     */
    private void DeleteChangeSet(CloudFormationAsyncClient cfAsyncClient, String stackName, String changeSetName) {

        try {
             DeleteChangeSetRequest deleteChangeSetRequest = DeleteChangeSetRequest.builder()
                     .changeSetName(changeSetName)
                     .stackName(stackName)
                     .build();

             cfAsyncClient.deleteChangeSet(deleteChangeSetRequest).get();

         } catch (Exception dcsex) {
             // Don't care if it isn't able to delete.
         }
    }

    /**
     * Use this method to retrieve the input parameters and perform mappings with previous output parameters, static
     * values or parameter values for the System Manager Parameter Store.
     *
     * @param stackParameterFilePath is the path the the parameter file to read the parameters from.
     * @param credentials is a set of credentials to use when accessing the System Manager Parameter Store.
     * @param inputParameters is the stack input parameter instructions to use for parameter mappings.
     * @param outputParameters is the array of output parameters from other stacks that have run.
     * @param region is the region the stack will execute or read from.
     * @return the array of parameters produced after reading them from the file and applying the map instructions.
     * @throws IOException when having problems reading the parameters from the input parameters file.
     * @throws MojoExecutionException when validation or logic errors occur.
     */
    private Parameter[] getInputParameters(String stackParameterFilePath, AwsCredentialsProvider credentials,
                                           StackInputParameter[] inputParameters, Map<String, String> outputParameters,
                                           String region)
            throws IOException, MojoExecutionException {

        File file = new File(stackParameterFilePath);

        String parametersString = new String(Files.readAllBytes(Paths.get(file.toURI())));

        ObjectMapper mapper = new ObjectMapper();
        StackParameter[] parameters_array = mapper.readValue(parametersString.getBytes(), StackParameter[].class);
        Parameter[] parameters = Arrays.stream(parameters_array).collect(ArrayList<Parameter>::new, (array, p) ->
                array.add(Parameter.builder().parameterKey(p.parameterKey).parameterValue(p.parameterValue)
                        .usePreviousValue(p.usePreviousValue).build()), (a1, a2) -> {}).toArray(Parameter[]::new);

        // Update the input parameters with values from the output parameters of previous stack runs.
        if (inputParameters != null) {

            SsmClient client;
            if(region != null) client = (credentials != null) ?
                    new ClientBuilder<SsmClient>().withRegion(region).build(ssmBuilder, credentials) :
                    new ClientBuilder<SsmClient>().withRegion(region).build((ssmBuilder));

            else client = (credentials != null) ?
                    new ClientBuilder<SsmClient>().build(ssmBuilder, credentials) :
                    new ClientBuilder<SsmClient>().build(ssmBuilder);

            for (StackInputParameter paramItem : inputParameters) {

                for (int i = 0; i < parameters.length; i++) {

                    Parameter parameter = parameters[i];
                    if (parameter.parameterKey().equals(paramItem.parameterName)) {

                        parameters[i] = Parameter.builder()
                                .parameterKey(parameter.parameterKey())
                                .parameterValue(getInputParameterValue(outputParameters, client, paramItem))
                                .usePreviousValue(parameter.usePreviousValue())
                                .build();
                        break;
                    }
                }
            }
        }

        return parameters;
    }

    /**
     * Use this method to extract the parameter value from a StackInputParameter instance.  The parameter value may
     * be a static value in the instance, a matching parameter in the Output Parameters array or a parameter from the
     * System Manager Parameter Store.  The method returns the parameter value as a string or throws an exception if
     * it is unable to read the parameter value.
     *
     * @param outputParameters is the map of output parameters.
     * @param client is the AWS Simple System Manager Client to use when fetching parameters from the parameter store.
     * @param paramItem is the StackInputParameter instance to use when fetching the parameter value.
     * @return the string representation of the parameter value.
     * @throws MojoExecutionException when an method error occurs.
     */
    private String getInputParameterValue(Map<String, String> outputParameters, SsmClient client,
                                           StackInputParameter paramItem)
            throws MojoExecutionException {

        String parameterValue;
        int fieldCount = paramItem.matchingParameterName != null ? 1 : 0;
        fieldCount += paramItem.parameterStoreFieldName != null ? 1 : 0;
        fieldCount += paramItem.parameterValue != null ? 1 : 0;

        if (fieldCount == 0)
            throw new MojoExecutionException("Invalid Stack Input Syntax.");

        if ((paramItem.parameterStoreFieldName != null) && (fieldCount > 2))
            throw new MojoExecutionException("Invalid Stack Input Syntax.");

        if((fieldCount == 2) && (paramItem.parameterStoreFieldName != null)) {

            if(paramItem.parameterValue == null) throw new MojoExecutionException("Invalid Stack Input Syntax.");
        }

        if (paramItem.matchingParameterName != null) {

            if (outputParameters.containsKey(paramItem.matchingParameterName))
                parameterValue = outputParameters.get(paramItem.matchingParameterName);

            else {

                if (paramItem.parameterValue != null) parameterValue = paramItem.parameterValue;
                else throw new MojoExecutionException("Matching Parameter not found (" +
                        paramItem.matchingParameterName + ").");
            }

        } else if(paramItem.parameterStoreFieldName != null){

            try {

                GetParameterRequest request = GetParameterRequest.builder().name(paramItem.parameterStoreFieldName).withDecryption(true).build();

                GetParameterResponse result = client.getParameter(request);
                parameterValue = result.parameter().value();

            } catch (ParameterNotFoundException ex) {

                if(paramItem.parameterValue != null) parameterValue = paramItem.parameterValue;
                else throw new MojoExecutionException("Parameter not found: " + paramItem.parameterStoreFieldName);
            }

        } else {

            parameterValue = paramItem.parameterValue.replace("{UUID}", UUID.randomUUID().toString());

        }

        return parameterValue;
    }

    /**
     * Use this method to get a jar file for deployment.  It uses a passed in filter to filter the jar files.  The
     * filter may be null.  The first jar of the filtered list is returned.  It also remove snapshot versions of
     * the jar.
     *
     * @param jars contains a list of jar files.
     * @param filter is a string to use as the filter.
     * @return a file to use for the deployment.
     * @throws IOException
     */
    private File getFile(File[] jars, String filter) throws IOException {

        // Sort the list of files
        Map<String, File> jarMap = new TreeMap<>(Collections.reverseOrder());

        jarMap.putAll(Arrays.stream(jars)
                .filter(jar -> !jar.getName().contains("-SNAPSHOT") &&
                        ((filter == null) || jar.getName().contains(filter)))
                .collect(LinkedHashMap::new, (map, jar) -> map.put(jar.getName(), jar), (b1, b2) -> {
                }));

        List<File> jarList = jarMap.entrySet().stream()
                .collect(ArrayList::new, (list, map) -> list.add(map.getValue()), (b1, b2) -> {
                });

        audit.write(jars.length + (jars.length == 1 ? " artifact was" : " artifacts were") +
                " found.\n");

        audit.write(Arrays.stream(jars)
                .collect(StringBuilder::new, (builder, jar) -> builder.append(jar).append('\n'),
                        (build1, build2) -> {
                        }).toString());

        return jarList.get(0);
    }

    /**
     * Use this method to return a Base 64 representation for a SHA 256 Hash of a file.
     *
     * @param file is the file to perform the hash on.
     * @return a Base 64 String representing the SHA 256 Hash of a file.
     * @throws NoSuchAlgorithmException is thrown when java can't reference the SHA-256 hash algorithm.
     * @throws IOException is thrown when java can read from the file.
     */
    private String getBase64SHA256HashString(File file) throws NoSuchAlgorithmException, IOException {

        // Calculate the SHA256 value of the file
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        FileInputStream fis = new FileInputStream(file.getPath());

        byte[] dataBytes = new byte[1024];

        int nread;
        while ((nread = fis.read(dataBytes)) != -1) {
            md.update(dataBytes, 0, nread);
        }

        //noinspection SpellCheckingInspection
        byte[] mdbytes = md.digest();

        // Convert the byte to hex format method 1
        return Base64.getEncoder().encodeToString(mdbytes);
    }

    /**
     * Use this method to create or update a cloud formation stack.
     *
     * @param stackName is the name of the stack to create.
     * @param templateUrl is the URL to the template to use when creating or updating the stack.
     * @param cfClient is the cloud formation client to use when creating or updating the stack.
     * @param parameters is a list of parameters to update the stack with.
     * @throws MojoExecutionException when a retry exception isn't a Rate Exceeded exception.
     */
    private void createStack(String stackName, String templateUrl, CloudFormationAsyncClient cfClient,
                             Parameter[] parameters) throws MojoExecutionException {

        boolean retry;

        // Create the stack.
        CreateStackRequest request;
        if(requiresIAM) {

            request = CreateStackRequest.builder()
                    .stackName(stackName)
                    .templateURL(templateUrl)
                    .parameters(parameters)
                    .capabilities(Capability.CAPABILITY_NAMED_IAM)
                    .build();
        } else {

            request = CreateStackRequest.builder()
                    .stackName(stackName)
                    .templateURL(templateUrl)
                    .parameters(parameters)
                    .build();
        }

        do {

            try {

                CreateStackResponse result = cfClient.createStack(request).get();
                WaitStackInProgress(stackName, cfClient);

                DescribeStacksResponse describeResponse = cfClient.describeStacks(DescribeStacksRequest.builder()
                        .stackName(stackName).build()).get();

                if(describeResponse.stacks().get(0).stackStatus() != StackStatus.CREATE_COMPLETE)
                    throw new MojoExecutionException(describeResponse.stacks().get(0).stackStatusReason());

                audit.write("Created " + stackName + " with id: " + result.stackId() + ".\n");
                retry = false;

            } catch (Exception ex) {

                retry = isRetry(ex);
            }

        } while(retry);
    }

    /**
     * Waits for a stack to complete.
     *
     * @param stackName The name of the stack being created
     * @param cfClient The CloudFormation client to use.
     * @throws InterruptedException Occurs when a process is interrupted.
     * @throws ExecutionException Occurs when an error happens during the execution of the describe operation.
     */
    private void WaitStackInProgress(String stackName, CloudFormationAsyncClient cfClient)
            throws InterruptedException, ExecutionException {

        boolean retry;
        Random random = new Random();
        do {

            DescribeStacksResponse describeResponse = cfClient.describeStacks(DescribeStacksRequest.builder()
                    .stackName(stackName).build()).get();

            switch(describeResponse.stacks().get(0).stackStatus()) {

                case CREATE_IN_PROGRESS:
                case ROLLBACK_IN_PROGRESS:
                case UPDATE_IN_PROGRESS:
                case REVIEW_IN_PROGRESS:
                case IMPORT_ROLLBACK_IN_PROGRESS:
                case UPDATE_ROLLBACK_IN_PROGRESS:
                case DELETE_IN_PROGRESS:
                case IMPORT_IN_PROGRESS:
                case UPDATE_COMPLETE_CLEANUP_IN_PROGRESS:
                case UPDATE_ROLLBACK_COMPLETE_CLEANUP_IN_PROGRESS:
                    retry = true;
                    Thread.sleep(random.nextInt(10000) + 1);
                    break;

                default:
                    retry = false;
                    break;
            }

        } while(retry);
    }
}
