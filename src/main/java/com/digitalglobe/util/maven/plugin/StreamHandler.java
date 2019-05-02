package com.digitalglobe.util.maven.plugin;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

/**
 * This class is reads buffers the output of a stream asynchronously.
 */
class StreamHandler extends Thread
{
    private InputStream inputStream;                            // The input stream to read from.
    private StringBuilder outputBuffer = new StringBuilder();   // A string buffer holding the input stream content.
    private Exception exception = null;                         // Exceptions that occur while working with the stream.
    private Exception closeException = null;                    // Any stream closing exceptions.

    /**
     * Get a buffer containing the content of the input stream.
     *
     * @return an output buffer containing the output from the execution.
     */
    StringBuilder getOutputBuffer()
    {
        return this.outputBuffer;
    }

    /**
     * Get any exception that occurred during the process of working with the buffer.  It may be null if no exception
     * occurred.
     *
     * @return the exception.
     */
    Exception getException() {

        return this.exception;
    }

    /**
     * Get the exeption that occured when trying to close the input stream.  It may be null if no exception occurred.
     *
     * @return the exception.
     */
    Exception getCloseException() {

        return this.closeException;
    }

    /**
     * This constructor initializes the input stream.
     *
     * @param inputStream
     */
    StreamHandler(InputStream inputStream)
    {
        this.inputStream = inputStream;
    }

    /**
     * Runs a process to read the output an input stream.  Store any exceptions that occur.
     */
    public void run()
    {
        BufferedReader bufferedReader = null;
        try
        {
            bufferedReader = new BufferedReader(new InputStreamReader(inputStream));
            String line = null;
            while ((line = bufferedReader.readLine()) != null)
            {
                this.outputBuffer.append(line + "\n");
            }
        }
        catch (Exception ex)
        {
            this.exception = ex;
        }
        finally
        {
            try
            {
                bufferedReader.close();
            }
            catch (IOException e)
            {
                this.closeException = e;
            }
        }
    }
}