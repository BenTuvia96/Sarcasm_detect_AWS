import java.util.List;

import software.amazon.awssdk.services.sqs.model.Message;

public class ManagerJob implements Runnable{

    private String localApplicationID;
    private String fileKey;
    private int fileNum;
    private List<String> stringReviews; // String reviews in a JSON format of all file
    private String bucketName;
    private String msgId;
    private int numWorkersToCreate;
    final private String del = AWSExecutorM.delimiter;

    public ManagerJob(String msgBody, String msgId, List<String> stringReviews, int numWorkersToCreate) {
        String[] messageArgs = msgBody.split(del);
        this.fileKey = messageArgs[0];
        this.fileNum = Integer.parseInt(messageArgs[1]);
        this.bucketName = messageArgs[2];
        this.localApplicationID = bucketName.substring(0, bucketName.length() - 6); // remove "Bucket"
        this.msgId = msgId;
        this.stringReviews = stringReviews;
        this.numWorkersToCreate = numWorkersToCreate;
    }

    @Override
    public void run() {
        // Create tasks for workers
        for(String reviewStr : stringReviews){
            // Task for worker format: "reviewJsonObj, fileKey, fileNum, bucketName(= localApplicationID + "Bucket")" separated by delimiter
            String task = reviewStr + del + fileKey + del + fileNum + del + bucketName;
            AWSExecutorM.sendMessageToQueue(AWSExecutorM.TO_WORKERS_QUEUE_NAME, task);
        }

        String fromWorkerSpecificQueueN = AWSExecutorM.FROM_WORKER_QUEUE_NAME + localApplicationID + fileNum; 

        int totalTasks = stringReviews.size();
        int tasksDone = 0;

        String summaryString = "";
        
        while(tasksDone < totalTasks){ 
            // Recieve messages from the workers
            // TODO : deal with duplicated info --> use review id?
            List<Message> messages = AWSExecutorM.receiveMessagesFromQueue(fromWorkerSpecificQueueN);
            for (Message message : messages) {
                // Product from worker format: "reviewLink, reviewRating, sentiment, entityRecStr(= NONE if there is no entities)" separated by delimiter
                String body = message.body();
                String[] messageArgs = body.split(del);
                String reviewLink = messageArgs[0];
                int reviewRating = Integer.parseInt(messageArgs[1]);
                int sentiment = Integer.parseInt(messageArgs[2]);
                String entityRecStr = messageArgs[3];

                String isSarcstic = "";
                if((reviewRating-1) != sentiment)
                    isSarcstic = "Sarcastic";
                else
                    isSarcstic = "Not Sarcastic";

                // Line format: "link, sentiment, entitiesStringFormat (=NONE if there are no entities), isSarcastic" separated by delimiter
                String line = reviewLink + del + sentiment + del + entityRecStr + del + isSarcstic + "\n";
                summaryString += line;

                tasksDone++;

                // Delete the message from the queue
                AWSExecutorM.deleteMessageFromQueue(fromWorkerSpecificQueueN, message);
                /* In a case where the manager collapes before the managerJob finished the output file, 
                    then when it will revive, it will get the task rfo, the LA from the backup queue and start over. 
                    We acknowlaged that "big data" refers to the number of the files, and not the size of each file. */

            }
        }

        // Remove the last newline character
        summaryString = summaryString.substring(0, summaryString.length() - 1);

        // Upload the summary to the S3 bucket
        String outputFileKey = "output" + fileNum + ".txt";
        AWSExecutorM.uploadContentToS3File(bucketName, outputFileKey, summaryString);

        /* In case the manager collapses before sending the message to the LA and/or before deleting the queue, 
            when it revives it will first look for the output file in the bucket, and delete the queue. */

        // Delete the queue which was specific to this file and localApplication
        AWSExecutorM.deleteQueue(fromWorkerSpecificQueueN);

        // Message from manager format: "fileKey, i" separated by delimiter 
        String message = outputFileKey + del + fileNum;
        AWSExecutorM.sendMessageToQueue(AWSExecutorM.FROM_MANAGER_QUEUE_NAME + localApplicationID, message);

        /* Delete the message from the backup queue, 'cause the the job is DONE!
            note that the message from the main queue was deleted right after the managerJob was created. */
        AWSExecutorM.deleteMessageFromQueueViaID(AWSExecutorM.BACKUP_QUEUE_NAME, this.msgId); // TODO: works only localy?

        Manager.updateTaskIsDone();
        Manager.terminateNumOfWorkers(numWorkersToCreate/2);
    }
    
}
