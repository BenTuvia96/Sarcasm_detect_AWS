import software.amazon.awssdk.services.sqs.model.*;
import software.amazon.awssdk.auth.credentials.*;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

public class LocalApp{

    final static String del = AWSExecutorLA.delimiter;

    final static String managerJarName = AWSExecutorLA.managerJarName;
    final static String managerJarPath = AWSExecutorLA.managerJarPath; 

    final static String workerJarName = AWSExecutorLA.workerJarName;
    final static String workerJarPath = AWSExecutorLA.workerJarPath;
    

    public static void main(String[] args) {
        // args = [inputFileName1,... inputFileNameN, outputFileName1,... outputFileNameN, n ]
        
        long startTime = System.currentTimeMillis();

        final String localApplicationID = "mylocalup";//UUID.randomUUID().toString(); // TODO: return unique id
        System.out.println("Local Application ID: " + localApplicationID);

        // Calculating the n = number of messages per worker
        int n = Integer.parseInt(args[args.length-1]);

        // Creat the S3 bucket for the local application
        String bucketS3Name = localApplicationID + "bucket";
        AWSExecutorLA.createBucketIfNotExists(bucketS3Name);

        // Create the S3 bucket for the manager jar and the worker jar
        AWSExecutorLA.createBucketIfNotExists(AWSExecutorLA.BUCKET_JAR_NAME);

        // Creating the sqs queues to communicate with the manager
        String toManagerQueueN = AWSExecutorLA.TO_MANAGER_QUEUE_NAME;
        AWSExecutorLA.createSqsQueue(toManagerQueueN);  
        // This queue (above) is for all the local applications, only one like that exists.
        String fromManagerQueueN = AWSExecutorLA.FROM_MANAGER_QUEUE_NAME + localApplicationID; // + localApplicationID to make the name singular
        AWSExecutorLA.createSqsQueue(fromManagerQueueN);
        
        // Calculating the number of files
        int numFiles = (args.length-1)/2;
        System.out.println("Number of files: " + numFiles);

        // Key list to the input files
        List<String> inputFilesKeys = new ArrayList<String>();
        // Key list to the output files
        List<String> outputFilesKeys = new ArrayList<String>();

        // Uploading the input files to the S3 bucket and sending a message to the manager
        for (int i = 0; i < numFiles; i++) {
            String inputFilePath = args[i];
            String[] pathArgs = args[i].split("/");
            String fileKey = pathArgs[pathArgs.length-1]; // The file name without the path
            inputFilesKeys.add(fileKey);
            AWSExecutorLA.uploadFileToS3(bucketS3Name, fileKey, inputFilePath); // TODO: delete
            // Sending a message to the manager:
             String task = fileKey + del + i + del + bucketS3Name + del + n; 
           // Task for manager: "fileKey, i = fileNum, bucketS3Name (= localApplicationID + "Bucket"), n" separated by delimiter
            AWSExecutorLA.sendMessageToQueue(toManagerQueueN, task);
        }

        // Creating the manager
        //AWSExecutorLA.createManagerIfNotExists(); // TODO: delete comment
        // Preparing for manager collapse
        boolean isManagerAlive = true;

        // Waiting for the manager to finish
        boolean isManagerDone = false;
        boolean[] gotFileProduct = new boolean[numFiles]; // initiated with false

        // Waiting 30 sec so the manager instance will be up and running
        try {
            Thread.sleep(30000);
        } catch (InterruptedException e) {
            System.out.println("Interrupted while waiting.");
            e.printStackTrace();
        }
    
        while (!isManagerDone) {
            // Checking for messages from the manager
            List<Message> messages = AWSExecutorLA.receiveMessagesFromQueue(fromManagerQueueN);
            // Message from manager format: "fileKey, i" separated by delimiter 
            for (Message message : messages) { 
                String[] msgSplits = message.body().split(del);
                String fileKey = msgSplits[0];
                outputFilesKeys.add(fileKey);
                int i = Integer.parseInt(msgSplits[1]);
                gotFileProduct[i] = true;
                // The output file for the i-th input file (it has the same path as the i-th input file)
                String outputFilePath = extractFolderPath(args[i]) + "/" + args[numFiles + i]; 
                // Downloading the output file from the S3 bucket
                InputStream stream = AWSExecutorLA.downloadFromS3(bucketS3Name, fileKey);
                // Write the product to the output html file
                writeProductToFile(stream, outputFilePath);
                // Deleting the message from the queue
                AWSExecutorLA.deleteMessageFromQueue(fromManagerQueueN, message); 
            }
            
            isManagerDone = isAllArrTrue(gotFileProduct);
            isManagerAlive = AWSExecutorLA.isManagerRuning();

            if(!isManagerAlive && !isManagerDone){
                // Tring to revive the manager
                AWSExecutorLA.createManagerIfNotExists();
            }
              
        }

        // Sending a termination message to the manager
        AWSExecutorLA.sendMessageToQueue(toManagerQueueN, AWSExecutorLA.TERMINATION_MESSAGE);

        // Deleting the queue
        AWSExecutorLA.deleteQueue(fromManagerQueueN);
        // Note that the queueu toManagerQueueN is not deleted, it is used by all the local applications.

        // Deleting the input files from the S3 bucket 
        for(String key : inputFilesKeys){
            AWSExecutorLA.deleteObjectFromBucket(bucketS3Name, key);
        } 
        // Deleting the output files from the S3 bucket 
        for(String key : outputFilesKeys){
            AWSExecutorLA.deleteObjectFromBucket(bucketS3Name, key);
        }
        // Deleting the empty S3 bucket
        AWSExecutorLA.deleteS3Bucket(bucketS3Name);

        // Calculating the run time
        long endTime = System.currentTimeMillis();
        long runTime = endTime - startTime;
        System.out.println("Run time: " + runTime + " milliseconds");

    }


    // Writing the product to the output html file
    public static void writeProductToFile(InputStream stream, String outputFilePath) {
        // creating the output file
        createFileFromPath(outputFilePath);

        BufferedReader reader = new BufferedReader(new InputStreamReader(stream));
        /// Line format: "link, sentiment, entitiesStringFormat (=NONE if there are no entities), isSarcastic" separated by delimiter

        String body = "<h2>"+"Reviews from Amazon"+"</h2>\n";
        body += "<ul>\n";
        for(String line : reader.lines().collect(Collectors.toList())) {
            String[] lineSplits = line.split(del);
            String link = lineSplits[0];
            String sentiment = lineSplits[1];
            String entitiesStringFormat = lineSplits[2];
            if(entitiesStringFormat.equals("NONE")) entitiesStringFormat = "No entities recognition were found."; // if there are no entities
            String isSarcastic = lineSplits[3];
            body += "<li>\n";
            String color = colorFromSentiment(sentiment);
            body += "<a href=\"" + link + "\" style=\"color:" + color + ";\">" + link + "</a>\n";
            body += "<p>" + entitiesStringFormat +"</p>\n";
            body += "<p>" + isSarcastic +"</p>\n";
            body += "</li>\n";
        }

        String htmlString = templateHTML;
        htmlString = htmlString.replace("$body", body);
        try {
            BufferedWriter writer = new BufferedWriter(new FileWriter(outputFilePath + ".html"));
            writer.write(htmlString);
            writer.close();
            System.out.println("The product was written to the file: " + outputFilePath + ".html");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static String colorFromSentiment(String sentiment) {
        switch (sentiment) {
            case "0":
                return "darkRed";
            case "1":
                return "Red";
            case "2":
                return "Black";
            case "3":
                return "LightGreen";
            case "4":
                return "DarkGreen";
        }
        return "Yellow"; // means something went wrong
    }

    public static boolean isAllArrTrue(boolean[] arr) {
        for (boolean b : arr) 
            if (!b) 
                return false;
        return true;
    }

    private static String extractFolderPath(String filePath) {
        // Create a File object with the given file path
        File file = new File(filePath);
        
        // Get the parent directory of the file
        File parentDir = file.getParentFile();
        
        // Return the absolute path of the parent directory
        String dir = parentDir.getAbsolutePath();
        System.out.println("The parent directory of the file is: " + dir);
        return dir;
    }
    
    private static void createFileFromPath(String filePath) {
        File file = new File(filePath);

        try {
            // Create the file
            boolean created = file.createNewFile();
            if (created) {
                System.out.println("File created successfully.");
            } else {
                System.out.println("File already exists.");
            }
        } catch (IOException e) {
            System.out.println("An error occurred while creating the file: " + e.getMessage());
        }
    }

    private static final String templateHTML =
    "<!DOCTYPE html>\n" +
            "<html>\n" +
            "<head>\n" +
            "<meta charset=UTF-8\">\n" +
            "</head>\n" +
            "<body>\n" +
            "$body" +
            "</body>\n" +
            "</html>";
}