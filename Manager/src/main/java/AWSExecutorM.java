import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.Tag;
import software.amazon.awssdk.services.ec2.model.*;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;

import java.io.File;
import java.io.InputStream;
import java.util.Base64;
import java.util.List;

import com.amazonaws.util.EC2MetadataUtils;

public class AWSExecutorM {
        private static S3Client s3Client;
        private static SqsClient sqsClient;
        private static Ec2Client ec2Client;

        public static Region region1 = Region.US_WEST_2;
        public static Region region2 = Region.US_EAST_1;

        static final String delimiter = "###"; 

        static final String BUCKET_JAR_NAME = "sarcasm-jar-bucket";

        private final static Filter RUNNING_INSTANCE_FILTER = Filter.builder()
                .name("instance-state-name")
                .values("running")
                .build();

        // Manager-and-Local-Application-only variables:

        private final static String ami = "ami-00e95a9222311e8ed";

        static final String managerJarName = "ManagerJar.jar";
        static final String workerJarName = "WorkerJar.jar";

        static final String TO_MANAGER_QUEUE_NAME = "ToManagerQueue"; 
        static final String FROM_MANAGER_QUEUE_NAME = "FromManagerQueue"; 

        static final String TERMINATION_MESSAGE = "Termination and distruction";

        // Manager-and-Worker-only variables:

        static final String TO_WORKERS_QUEUE_NAME = "ToWorkerQueue";
        static final String FROM_WORKER_QUEUE_NAME = "FromWorkerQueue";

        // Manager-only variables:

        static final String BACKUP_QUEUE_NAME = "BackupQueue";

        static final int MAX_WORKERS = 9 - 1; // 9 is the maximum number of instances allowed to a student account, and 1 is the manager

        static final int max_tasks_parallel = 3; // So that the deleting from the backup queue won't be effected from the big data // TODO: verify

        private static final String workerScript = "#!/bin/bash\n" + 
                                        "sudo yum install -y java-1.8.0-openjdk\n" + // Install OpenJDK 8
                                        "aws s3 cp s3://" + BUCKET_JAR_NAME + "/" + workerJarName + " /home/ec2-user/" + workerJarName + "\n"+ // Copy the jar from an S3 bucket to the local /home/ec2-user/ directory TODO: so it will be ok if a LA deletes the jar and it's bucket?
                                        "java -jar /home/ec2-user/"+ workerJarName +"\n" +
                                        "echo Running WorkerJar.jar...\n"; // TODO: but we dont know how to print shit

        private static final Filter WORKER_TAG_FILTER = Filter.builder()
                .name("tag:Type")
                .values("Worker")
                .build();

        private static final InstanceType workerType = InstanceType.T2_LARGE; // TODO: verify size

        

        private static final AWSExecutorM instance = new AWSExecutorM();

        private AWSExecutorM() {
                s3Client = S3Client.builder().region(region1).build();
                sqsClient = SqsClient.builder().region(region1).build();
                ec2Client = Ec2Client.builder().region(region2).build();
        }

        public static AWSExecutorM getInstance() {
                return instance;
        }

        public static String getQueueUrl(String name) {
                GetQueueUrlRequest getQueueRequest = GetQueueUrlRequest.builder()
                        .queueName(name)
                        .build();
                return sqsClient.getQueueUrl(getQueueRequest).queueUrl();
                }

        public static void sendMessageToQueue(String queueName, String message) {
                SendMessageRequest sendMessageRequest = SendMessageRequest.builder()
                        .queueUrl(getQueueUrl(queueName))
                        .messageBody(message)
                        .build();
                sqsClient.sendMessage(sendMessageRequest);
        }

        public static List<Message> receiveMessagesFromQueue(String queueName) {
                ReceiveMessageRequest receiveRequest = ReceiveMessageRequest.builder()
                        .queueUrl(getQueueUrl(queueName))
                        .maxNumberOfMessages(max_tasks_parallel)
                        .waitTimeSeconds(20) // 0 is short polling, 1-20 is long polling
                        .build();
                return sqsClient.receiveMessage(receiveRequest).messages();
        }

        public static void deleteMessageFromQueue(String queueName, Message message) {
                DeleteMessageRequest deleteRequest = DeleteMessageRequest.builder()
                        .queueUrl(getQueueUrl(queueName))
                        .receiptHandle(message.receiptHandle())
                        .build();
                sqsClient.deleteMessage(deleteRequest);
        }

        public void uploadFileToS3(String bucketName, String key, String filePath) {
                try {
                        s3Client.putObject(
                                PutObjectRequest.builder().bucket(bucketName).key(key).build(),
                                RequestBody.fromFile(new File(filePath)));
        
                        System.out.println("File " + filePath + " uploaded successfully to bucket " + bucketName + "!");
                } catch (S3Exception e) {
                        System.err.println("Error uploading file to S3: " + e.getMessage());
                }
        }

        public static InputStream downloadFromS3(String bucketName, String key) { 
                return s3Client.getObject(
                        GetObjectRequest.builder().bucket(bucketName).key(key).build(),
                        ResponseTransformer.toBytes()).asInputStream();
        }

        // Manager-and-Local-Application-only methods:

        public static void createBucketIfNotExists(String bucketName) {
                try {
                        s3Client.createBucket(CreateBucketRequest
                                .builder()
                                .bucket(bucketName)
                                .createBucketConfiguration(
                                        CreateBucketConfiguration.builder()
                                                .locationConstraint(BucketLocationConstraint.US_WEST_2)
                                                .build())
                                .build());
                        s3Client.waiter().waitUntilBucketExists(HeadBucketRequest.builder()
                                .bucket(bucketName)
                                .build());
                } catch (S3Exception e) {
                        System.out.println(e.getMessage()); // If the bucket already exists, it will print an error message
                }
        }

        public static void createEC2(String script, String tagName, InstanceType insType,  int numberOfInstances) {
                // Create RunInstancesRequest
                RunInstancesRequest runRequest = (RunInstancesRequest) RunInstancesRequest.builder()
                        .instanceType(insType) 
                        .imageId(ami)
                        .maxCount(numberOfInstances)
                        .minCount(1)
                        .keyName("vockey")
                        .iamInstanceProfile(IamInstanceProfileSpecification.builder().name("LabInstanceProfile").build())
                        .userData(Base64.getEncoder().encodeToString((script).getBytes()))
                        .build();

                // Send the request to launch the instance
                RunInstancesResponse response = ec2Client.runInstances(runRequest); 

                // Tagging the instances:
                List<Instance> instances = response.instances();

                for(Instance instance : instances) {
                        String instanceId = instance.instanceId();

                        // Create a tag for the instance
                        software.amazon.awssdk.services.ec2.model.Tag tag = Tag.builder()
                                .key("Type")
                                .value(tagName)
                                .build();
        
                        // Create a tag request
                        CreateTagsRequest tagRequest = (CreateTagsRequest) CreateTagsRequest.builder() 
                                .resources(instanceId)
                                .tags(tag)
                                .build();
        
                        try {
                                ec2Client.createTags(tagRequest); // Tag the instance
                                System.out.printf(
                                        "[DEBUG] Successfully started EC2 instance %s based on AMI " + tagName + " %s\n",
                                        instanceId, ami);
        
                        } catch (Ec2Exception e) {
                                System.err.println("[ERROR] " + e.getMessage());
                                System.exit(1);
                        }   
                }

        }

        /**
         * If there already exists SQS queueu with the name queueName,
         * it will not create a new one, and won't override the existing one.
         * @param queueName
         */
        public static void createSqsQueue(String queueName) {
                CreateQueueRequest createQueueRequest = CreateQueueRequest.builder()
                        .queueName(queueName)
                        .build();
                sqsClient.createQueue(createQueueRequest);
                System.out.println("Queue " + queueName + " created successfully!");
        }

        public static void deleteQueue(String name) {
                try{
                        DeleteQueueRequest request = DeleteQueueRequest.builder()
                        .queueUrl(getQueueUrl(name))
                        .build();
                        sqsClient.deleteQueue(request);
                }
                catch (QueueDoesNotExistException e) {
                        System.out.println("[DEGUB]: Error in queue " + name + " :" + e.getMessage());
                }
        }
        
        public static boolean isFileExistsInBucket(String bucketName, String fileKey) {
                try {
                    s3Client.headObject(HeadObjectRequest.builder().bucket(bucketName).key(fileKey).build());
                    return true;
                } catch (S3Exception e) {
                    return false;
                }
        }

        /**
         * Note: bucket MUST be empty to be deleted.
         * @param bucket
         */
        public static void deleteS3Bucket(String bucket) {
                DeleteBucketRequest request = DeleteBucketRequest.builder()
                        .bucket(bucket)
                        .build();
                s3Client.deleteBucket(request);
        }

        public static void deleteObjectFromBucket(String bucket, String key) { 
                DeleteObjectRequest request = DeleteObjectRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .build();
                s3Client.deleteObject(request);
        }

        // Manager-and-Worker-only methods:

        public static void terminateMyself() {
                // Get the instance ID from instance metadata
                String instanceId = EC2MetadataUtils.getInstanceId();

                // Termination request
                TerminateInstancesRequest terminateRequest = TerminateInstancesRequest.builder()
                        .instanceIds(instanceId)
                        .build();

                try {
                ec2Client.terminateInstances(terminateRequest);
                } catch (Ec2Exception e) {
                System.err.println("Error deleting instance: " + e.getMessage());
                }
        }
        
        public static boolean isQueueEmpty(String queueName) {
                return receiveMessagesFromQueue(queueName).isEmpty();
        }

        // Manager-only methods:

        public static void createWorkersInstances(int numWorkers){
                createEC2(workerScript, "Worker", workerType, numWorkers);
                System.out.println("Creating " + numWorkers + " worker instances.");
        }
        
        public static void terminateWorkerInstances(int numToTerminate){
                if(numToTerminate > 0){
                        DescribeInstancesRequest describeRequest = DescribeInstancesRequest.builder()
                                .filters(WORKER_TAG_FILTER, RUNNING_INSTANCE_FILTER)
                                .build();

                        // Decribe instances and process the response:
                        DescribeInstancesResponse describeResponse = ec2Client.describeInstances(describeRequest);

                        //  Check if there are instances with the specified tag:
                        List<Reservation> list = describeResponse.reservations();
                        if(!list.isEmpty()){
                                int num_of_terminated_workers = 0;
                                while(num_of_terminated_workers < numToTerminate){
                                        for(Reservation reservation : list){
                                                for(Instance instance : reservation.instances()){
                                                        if(num_of_terminated_workers < numToTerminate){
                                                                String instanceId = instance.instanceId();
                                                                TerminateInstancesRequest terminateRequest = TerminateInstancesRequest.builder()
                                                                        .instanceIds(instanceId)
                                                                        .build();
                                                                try {
                                                                        ec2Client.terminateInstances(terminateRequest);
                                                                        num_of_terminated_workers++;
                                                                } catch (Ec2Exception e) {
                                                                        System.err.println("Error deleting instance: " + e.getMessage());
                                                                }
                                                        }
                                                        else{
                                                                System.out.println("Terminated " + num_of_terminated_workers + " workers.");
                                                                return; // We terminated the requested number of workers
                                                        }
                                                }
                                        }
                                }
                        }
                }
                else
                        System.out.println("No workers to terminate.");
        }
        
        public static int getNumOfRuningWorkers(){ // TODO: check if it works
                DescribeInstancesRequest describeRequest = DescribeInstancesRequest.builder()
                        .filters(WORKER_TAG_FILTER, RUNNING_INSTANCE_FILTER)
                        .build();

                // Decribe instances and process the response:
                DescribeInstancesResponse describeResponse = ec2Client.describeInstances(describeRequest);

                //  Check if there are instances with the specified tag:
                List<Reservation> list = describeResponse.reservations();
                int counter = 0;
                for(Reservation reservation : list)
                        counter+= reservation.instances().size();

                System.out.println("Number of running workers from function: " + counter);
                               
                return counter;
        }

        public static void shutdownWorkers() { 
                DescribeInstancesRequest describeRequest = DescribeInstancesRequest.builder()
                        .filters(WORKER_TAG_FILTER, RUNNING_INSTANCE_FILTER)
                        .build();

                // Decribe instances and process the response:
                DescribeInstancesResponse describeResponse = ec2Client.describeInstances(describeRequest);

                //  Check if there are instances with the specified tag:
                List<Reservation> list = describeResponse.reservations();
                if(!list.isEmpty()){
                        for(Reservation reservation : list){
                                for(Instance instance : reservation.instances()){
                                        String instanceId = instance.instanceId();
                                        TerminateInstancesRequest terminateRequest = TerminateInstancesRequest.builder()
                                                .instanceIds(instanceId)
                                                .build();
                                        try {
                                                ec2Client.terminateInstances(terminateRequest);
                                        } catch (Ec2Exception e) {
                                                System.err.println("Error deleting instance: " + e.getMessage());
                                        }
                                }
                        }
                }
        }

        public static void uploadContentToS3File(String bucketName, String key, String content) {
                try {
                        s3Client.putObject(
                                PutObjectRequest.builder().bucket(bucketName).key(key).build(),
                                RequestBody.fromString(content));
                        System.out.println("Content uploaded successfully to bucket " + bucketName + "!");
                        
                } catch (S3Exception e) {
                        System.err.println("Error uploading content to S3: " + e.getMessage());
                }
        }

        public static String sendMessageToQueueAndReturnId(String queueName, String message) {
                SendMessageRequest sendMessageRequest = SendMessageRequest.builder()
                        .queueUrl(getQueueUrl(queueName))
                        .messageBody(message)
                        .build();
                SendMessageResponse resultMessage =  sqsClient.sendMessage(sendMessageRequest);
                System.out.println("Message sent to queue " + queueName + " with ID: " + resultMessage.messageId());
                return resultMessage.messageId();
        }

        public static void deleteMessageFromQueueViaID(String queueName, String messageId) {
                List<Message> messages = receiveMessagesFromQueue(queueName);
                for(Message message : messages){
                        System.out.println("Message ID: " + message.messageId() + " Message Body: " + message.body());
                        if(messageId.equals(message.messageId())){
                                deleteMessageFromQueue(queueName, message);
                                System.out.println("Message with ID " + messageId + " deleted from queue " + queueName + "!");
                                return;
                        }
                }
        }
  
}
