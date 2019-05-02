package com.digitalglobe.util.maven.plugin;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Use this to execute a system command.
 */
public class ExecuteCommand {

    private String[] commandInformation;        // An array of command segments.
    private Map<String, String> environmentMap; // A map of environment variables.
    private StreamHandler inputStreamHandler;   // A stream to read the standard output of the execution.
    private StreamHandler errorStreamHandler;   // A stream to read the standard error of the execution.
    private Exception[] executionErrors;        // An array of execution exceptions.

    /**
     * Set the elements of the command used to execute the command.
     *
     * @param commandInformation is the elements to use when executing the command.
     * @return this instance for initialization chaining.
     */
    ExecuteCommand withCommandInformation(String[] commandInformation) {

        this.commandInformation = commandInformation;
        return this;
    }

    /**
     * Set the environment map containing environment variables to set with the execution.
     *
     * @param environmentMap contains environment variables.
     * @return this instance for initialization chaining.
     */
    ExecuteCommand withEnvironmentMap(Map<String, String> environmentMap) {

        this.environmentMap = environmentMap;
        return this;
    }

    /**
     * Get the standard output (stdout) from the command you just executed.
     */
    StringBuilder getStandardOutputFromCommand()
    {
        return this.inputStreamHandler.getOutputBuffer();
    }

    /**
     * Get the standard error (stderr) from the command you just executed.
     */
    StringBuilder getStandardErrorFromCommand()
    {
        return this.errorStreamHandler.getOutputBuffer();
    }

    /**
     * Get any execution errors that may have occured.  Could be null if there weren't any errors.
     *
     * @return an array of exceptions that occured.
     */
    Exception[] getExecutionErrors() {

        return this.executionErrors;
    }

    /**
     * Use this method to execute the command.
     */
    void executeCommand()
    {
        List<Exception> errors = new ArrayList<>();

        try {

            ProcessBuilder pb = new ProcessBuilder(this.commandInformation);
            pb.environment().putAll(this.environmentMap);
            Process process = pb.start();

            InputStream inputStream = process.getInputStream();
            InputStream errorStream = process.getErrorStream();

            // Reading from the process outputs in an asynchronous manner.
            // Note that the outputs of the process are inputs to this application thus they are input streams.
            this.inputStreamHandler = new StreamHandler(inputStream);
            this.errorStreamHandler = new StreamHandler(errorStream);

            this.inputStreamHandler.start();
            this.errorStreamHandler.start();

            process.waitFor();

            // Make sure that we have read all the data from the streams.
            this.inputStreamHandler.join();
            this.errorStreamHandler.join();

            // Record any exceptions that where thrown.
            if(this.inputStreamHandler.getException() != null)
                errors.add(this.inputStreamHandler.getException());

            if(this.inputStreamHandler.getCloseException() != null)
                errors.add(this.inputStreamHandler.getCloseException());

            if(this.errorStreamHandler.getException() != null)
                errors.add(this.errorStreamHandler.getException());

            if(this.errorStreamHandler.getCloseException() != null)
                errors.add(this.errorStreamHandler.getCloseException());

        } catch (Exception ex) {

            try {

                if (this.inputStreamHandler != null) {

                    if (this.inputStreamHandler.getException() != null)
                        errors.add(this.inputStreamHandler.getException());

                    if (this.inputStreamHandler.getCloseException() != null)
                        errors.add(this.inputStreamHandler.getCloseException());
                }

                if(this.errorStreamHandler != null) {

                    if(this.errorStreamHandler.getException() != null)
                        errors.add(this.errorStreamHandler.getException());

                    if(this.errorStreamHandler.getCloseException() != null)
                        errors.add(this.errorStreamHandler.getCloseException());
                }

            } catch (Exception e) {
                // Ignore
                System.out.println(e.getMessage());
            }

            errors.add(ex);
            System.out.println(ex.getMessage());
        }

        this.executionErrors = errors.size() > 0 ? (Exception[])errors.toArray() : null;
    }
}

