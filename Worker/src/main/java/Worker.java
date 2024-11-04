import java.util.List;

import org.json.JSONObject;

import software.amazon.awssdk.services.sqs.model.Message;

public class Worker {

    static sentimentAnalysisHandler sentimentAnalysisHandler = new sentimentAnalysisHandler();
    static namedEntityRecognitionHandler namedEntityRecognitionHandler = new namedEntityRecognitionHandler();

    static final String del = AWSExecutorW.delimiter;
    static boolean isManagerAlive = true;

    public static void main(String[] args) {

        /*System.out.println();
        long startTime = System.currentTimeMillis();
        String check = namedEntityRecognitionHandler.getEntitiesInStrFormat("Obama was the president of the US. He was born in Hawaii. Hana was very pretty today, she went to disneyland.");
        System.out.println(check);
        long endTime = System.currentTimeMillis();
        long runTime = endTime - startTime;
        System.out.println("Run time: " + runTime + " milliseconds");
        System.out.println();*/
        
        while(isManagerAlive){
            // Recieve messages from the Manager
            List<Message> list = AWSExecutorW.receiveMessagesFromQueueWithVT(AWSExecutorW.TO_WORKERS_QUEUE_NAME);
            // The max num of messages that can be received is 1
            for(Message message : list){ 
                String body = message.body();
                // Task for worker format: "reviewJsonObj, fileKey, fileNum, bucketName(= localApplicationID + "Bucket")" separated by delimiter
                String[] taskArgs = body.split(del);
                JSONObject review = new JSONObject(taskArgs[0]);
                String fileKey = taskArgs[1]; // TODO : we use the file key as the fileNum so why do we need this?
                int fileNum = Integer.parseInt(taskArgs[2]);
                String bucketName = taskArgs[3];
                String localAppID = bucketName.substring(0, bucketName.length() - 6); // remove "Bucket"

                // Parse the review as a JSON object
                String id = review.getString("id"); // TODO : maybe add to the product for verifing duplicated data
                String reviewLink = review.getString("link");
                //String reviewTitle = review.getString("title");
                String reviewText = review.getString("text").replace("\"", "''");
                System.out.println("Review text: " + reviewText);
                int rating = review.getInt("rating");
                //String author = review.getString("author");
                //String date = review.getString("date");

                // Product from worker format: "reviewLink, reviewRating, sentiment, entityRecStr(= NONE if there is no entities)" separated by delimiter
                int sentiment = sentimentAnalysisHandler.findSentiment(reviewText);
                String entityRecStr = namedEntityRecognitionHandler.getEntitiesInStrFormat(reviewText);
                if(entityRecStr == "") entityRecStr = "NONE";
                String product = reviewLink + del + rating + del + sentiment + del + entityRecStr;
                AWSExecutorW.sendMessageToQueue(AWSExecutorW.FROM_WORKER_QUEUE_NAME + localAppID + fileNum, product);

                // Delete the message from the worker's task queue, because the task is done
                AWSExecutorW.deleteMessageFromQueue(AWSExecutorW.TO_WORKERS_QUEUE_NAME, message);    
            }

            // Check if the manager is still alive
            //isManagerAlive = AWSExecutorW.isManagerRunning(); // TODO: check before sending the message - delete the comment
        } 
        
        /* End of while loop = The manager is not running and didn't shut the worker down =
            = The manager collapsed --> need to terminate myself.
            Note: This is THE ONLY scenario where a worker terminates itself! */
        //AWSExecutorW.terminateMyself(); // TODO: delete comment

    }

}
