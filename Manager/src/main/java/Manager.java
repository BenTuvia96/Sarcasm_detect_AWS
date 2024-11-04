import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.io.IOException;
import org.json.JSONArray;
import org.json.JSONObject;

import software.amazon.awssdk.services.sqs.model.Message;

public class Manager {

    private static final int maxWorkersNum = AWSExecutorM.MAX_WORKERS; 
    private static int numOfRunningWorkers = 0;

    private static final int maxTasksParallel = AWSExecutorM.max_tasks_parallel; 
    private static int numOfRunningTasks = 0; // TODO: implement in the code
    
    private static boolean toTerminate = false;

    private static final int threadsNum = 10;
    private static final ThreadPoolExecutor executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(threadsNum);
    // TODO : is it ok to initiate it outside of the main?

    static final String del = AWSExecutorM.delimiter;

    // TODO : what about checking the from workers queueu in the manager job if the task was in progress? 
    //          if we decide we don't use it we should delete all the messages from the queue to prevent dipliaction
    
    public static void main(String[] args) {

        // Create the sqs queues to communicate with the workers
        AWSExecutorM.createSqsQueue(AWSExecutorM.TO_WORKERS_QUEUE_NAME);

        // Create the sqs queues for backups
        AWSExecutorM.createSqsQueue(AWSExecutorM.BACKUP_QUEUE_NAME);

        // First going over tasks that were not finished before the manager collapsed
        List<Message> backUpMessages = AWSExecutorM.receiveMessagesFromQueue(AWSExecutorM.BACKUP_QUEUE_NAME);
        System.out.println("Number of messages in the backup queue: " + backUpMessages.size());
        for (Message message : backUpMessages) {
            while(numOfRunningTasks >= maxTasksParallel){
                try {
                    Thread.sleep(30000);
                } catch (InterruptedException e) {
                    System.out.println(e.getMessage());;
                }
            }
            doTask(message);
            AWSExecutorM.deleteMessageFromQueue(AWSExecutorM.BACKUP_QUEUE_NAME, message);
            // In the function "do task" we send the message again to the backup queue
        }

        // Moving to the regular process, where we check the main queue from the local apps
        while(!toTerminate){
            // Recieve messages from the local apps
            List<Message> messages = AWSExecutorM.receiveMessagesFromQueue(AWSExecutorM.TO_MANAGER_QUEUE_NAME);

            while(numOfRunningTasks >= maxTasksParallel){
                try {
                    Thread.sleep(30000);
                } catch (InterruptedException e) {
                    System.out.println(e.getMessage());;
                }
            }

            for (Message message : messages) {
                String body = message.body();

                if (body.equals(AWSExecutorM.TERMINATION_MESSAGE)){
                    System.out.println("Got termination message");
                    toTerminate = true;
                } 
                /* By doing that, the manager will finish working on the current messages and then terminate.
                    It will not accept any new messages. */
                else{
                    doTask(message);
                }

                AWSExecutorM.deleteMessageFromQueue(AWSExecutorM.TO_MANAGER_QUEUE_NAME, message);
                
            }

            // TODO : managing number of workers - 1. termination due to lack of work, 2. checking if some of the workers collapsed

        } // end of while loop = Got termination message

        executor.shutdown();
        
        // Wait for threads to finish
        while (true) {
            try {
                if (executor.awaitTermination(4, TimeUnit.SECONDS)) break;
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        // Terminate the workers
        AWSExecutorM.shutdownWorkers();

        // Delete the queue to workers
        AWSExecutorM.deleteQueue(AWSExecutorM.TO_WORKERS_QUEUE_NAME);
        /* Note that the queues fromWorkers were already deleted 
            when the job was done (for each file and local app) in the ManagerJob class */

        // Delete the queue to manager
        AWSExecutorM.deleteQueue(AWSExecutorM.TO_MANAGER_QUEUE_NAME);

        // Delete the backup queue ONLY if it's empty 
        if(AWSExecutorM.isQueueEmpty(AWSExecutorM.BACKUP_QUEUE_NAME)){
            AWSExecutorM.deleteQueue(AWSExecutorM.BACKUP_QUEUE_NAME);
        }

        // Delete the jars from the bucket
        AWSExecutorM.deleteObjectFromBucket(AWSExecutorM.BUCKET_JAR_NAME, AWSExecutorM.managerJarName);
        AWSExecutorM.deleteObjectFromBucket(AWSExecutorM.BUCKET_JAR_NAME, AWSExecutorM.workerJarName);
        // Delete the bucket
        AWSExecutorM.deleteS3Bucket(AWSExecutorM.BUCKET_JAR_NAME); //TODO: remove comments

        // Terminate my EC2 instance
        AWSExecutorM.terminateMyself();

    }


    /**
     * Execute the task (not a terminate task).
     * @param message The message from the queue
     */
    private static void doTask(Message message){
        numOfRunningTasks++;
        String body = message.body();
        // Task for manager: "fileKey, i = fileNum, bucketS3Name (= localApplicationID + "Bucket"), n" separated by delimiter
        String[] messageArgs = body.split(del);
        String fileKey = messageArgs[0];
        int fileNum = Integer.parseInt(messageArgs[1]);
        String bucketName = messageArgs[2];
        String localApplicationID = bucketName.substring(0, bucketName.length() - 6); // remove "Bucket"
        int num_tasks_per_worker = Integer.parseInt(messageArgs[3]);
        
        /* Check if the output file is already in the S3 bucket,
            (meaning the manager collapsed after the job was done, but before the message was sent to the local app) */
        String outputFileName = "output" + fileNum + ".txt";
        if(AWSExecutorM.isFileExistsInBucket(bucketName, outputFileName)){
            // Message from manager format: "fileKey, i" separated by delimiter 
            String messageToLA = outputFileName + del + fileNum;
            AWSExecutorM.sendMessageToQueue(AWSExecutorM.FROM_MANAGER_QUEUE_NAME + localApplicationID, messageToLA);
            AWSExecutorM.deleteQueue(AWSExecutorM.FROM_WORKER_QUEUE_NAME + localApplicationID + fileNum);
            numOfRunningTasks--;
        }
        else{
            // Download the file from the S3 bucket
            InputStream inputStream = AWSExecutorM.downloadFromS3(bucketName, fileKey);
            // Parse the file as a list of reviews (strings in a json format - the whole review!!!)
            List<String> stringReviews = parseTxtAsJson(inputStream);
            int numReviews = stringReviews.size();

            // Create SQS queue for workers to sent outputs for a specific LA
            String fromWorkerSpecificQueueN = AWSExecutorM.FROM_WORKER_QUEUE_NAME + localApplicationID + fileNum;
            AWSExecutorM.createSqsQueue(fromWorkerSpecificQueueN);

            // Wait 30 seconds for checking how many workers are running
            try {
                Thread.sleep(30000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            // Check the number of running workers
           AWSExecutorM.getNumOfRuningWorkers(); // TODO: checking if it's the right position by only printing the number of running workers

            // Calculating num of workers needed and create them
            int numWorkersNeeded = Math.max((numReviews / num_tasks_per_worker), 1);
            int numWorkersToCreate = calculateNumOfWorkersTpCreate(numWorkersNeeded);

            // Upload the task to the backup queue
            String msgId = AWSExecutorM.sendMessageToQueueAndReturnId(AWSExecutorM.BACKUP_QUEUE_NAME, body); 

            // Run the executor tasks
            executor.execute(new ManagerJob(body, msgId, stringReviews, numWorkersToCreate));
            System.out.println("Manager: Sent a task to the executor");

            // Create the workers
            if(numWorkersToCreate > 0){                    
                AWSExecutorM.createWorkersInstances(numWorkersToCreate);
                numOfRunningWorkers += numWorkersToCreate;
            }
            
        } // end of else = The output file wasn't in the S3 bucket
    } // End of doTask

    /**
     * Parse the input stream as a json file and return a list of the reviews as strings in json format
     * @param inputStream The input stream of the file
     * @return List of all the reviews in the file as strings in json format
     */
    private static List<String> parseTxtAsJson(InputStream inputStream) {
        try {
            BufferedReader bufferReader = new BufferedReader(new InputStreamReader(inputStream));
            String line;
            List<String> stringReviews = new ArrayList<>();
            while ((line = bufferReader.readLine()) != null) {
                JSONObject jsonObject = new JSONObject(line);
                //String title = jsonObject.getString("title"); 
                JSONArray reviews = jsonObject.getJSONArray("reviews");
                for (int i = 0; i < reviews.length(); i++) {
                    JSONObject review = reviews.getJSONObject(i);
                    stringReviews.add(review.toString());
                    // These parse will be done in the worker:
                    /*String id = review.getString("id");
                    String reviewLink = review.getString("link");
                    String reviewTitle = review.getString("title");
                    String reviewText = review.getString("text");
                    int rating = review.getInt("rating");
                    String author = review.getString("author");
                    String date = review.getString("date");*/ 
                }
            }
            bufferReader.close();
            return stringReviews;
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    private static int calculateNumOfWorkersTpCreate(int numWorkersNeeded){
        int maxNumCanCreate = Math.max((maxWorkersNum - numOfRunningWorkers), 0);
        if(numWorkersNeeded > numOfRunningWorkers){
            return Math.min((numWorkersNeeded - numOfRunningWorkers), maxNumCanCreate);
        }
        else{
            return Math.min((int)(numWorkersNeeded/3), maxNumCanCreate);
        }
    }

    public static void terminateNumOfWorkers(int numOfWorkersToTerminate){
        if(numOfRunningWorkers == numOfWorkersToTerminate){
            numOfWorkersToTerminate--;
        } // We keep at least one worker running 'case other tasks's workers number was calculated from it.
        System.out.println("Running num of workers: " + numOfRunningWorkers +",   Terminating " + numOfWorkersToTerminate + " workers");
        AWSExecutorM.terminateWorkerInstances(numOfWorkersToTerminate);
        numOfRunningWorkers -= numOfWorkersToTerminate;
    }

    public static void updateTaskIsDone(){
        numOfRunningTasks--;
        System.out.println("Task is done! numOfRunningTasks: " + numOfRunningTasks);
    }

}
