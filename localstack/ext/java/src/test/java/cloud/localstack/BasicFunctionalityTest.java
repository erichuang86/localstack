package cloud.localstack;

import cloud.localstack.sample.KinesisLambdaHandler;
import cloud.localstack.sample.S3Sample;
import com.amazonaws.services.kinesis.AmazonKinesis;
import com.amazonaws.services.kinesis.model.ListStreamsResult;
import com.amazonaws.services.kinesis.model.PutRecordRequest;
import com.amazonaws.services.lambda.AWSLambda;
import com.amazonaws.services.lambda.model.CreateEventSourceMappingRequest;
import com.amazonaws.services.lambda.model.CreateFunctionRequest;
import com.amazonaws.services.lambda.model.ListFunctionsResult;
import com.amazonaws.services.lambda.model.Runtime;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.Bucket;
import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.model.CreateQueueRequest;
import com.amazonaws.services.sqs.model.CreateQueueResult;
import com.amazonaws.services.sqs.model.DeleteQueueRequest;
import com.amazonaws.services.sqs.model.ListQueuesResult;
import com.amazonaws.services.sqs.model.ReceiveMessageRequest;
import com.amazonaws.services.sqs.model.ReceiveMessageResult;
import com.amazonaws.services.sqs.model.SendMessageRequest;
import com.amazonaws.services.sqs.model.SendMessageResult;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static cloud.localstack.TestUtils.TEST_CREDENTIALS;

/**
 * Simple class to test basic functionality and interaction with LocalStack.
 *
 * @author Waldemar Hummer
 */
@RunWith(LocalstackTestRunner.class)
@ExtendWith(LocalstackExtension.class)
public class BasicFunctionalityTest {

    static {
        /*
         * Need to disable CBOR protocol, see:
         * https://github.com/mhart/kinesalite/blob/master/README.md#cbor-protocol-issues-with-the-java-sdk
         */
        TestUtils.setEnv("AWS_CBOR_DISABLE", "1");
        /* Disable SSL certificate checks for local testing */
        if (Localstack.useSSL()) {
            TestUtils.disableSslCertChecking();
        }
    }

    @org.junit.Test
    @org.junit.jupiter.api.Test
    public void testLocalKinesisAPI() throws InterruptedException {
        AmazonKinesis kinesis = TestUtils.getClientKinesis();
        ListStreamsResult streams = kinesis.listStreams();
        Assertions.assertThat(streams.getStreamNames()).isNotNull();
        String streamName = "testStreamJUnit";
        kinesis.createStream(streamName, 1);
        // sleep required because of kinesalite
        Thread.sleep(500);
        PutRecordRequest req = new PutRecordRequest();
        req.setPartitionKey("foobar-key");
        req.setData(ByteBuffer.wrap("{}".getBytes()));
        req.setStreamName(streamName);
        kinesis.putRecord(req);
        final ByteBuffer data = ByteBuffer.wrap("{\"test\":\"test\"}".getBytes());
        kinesis.putRecord(streamName, data, "partition-key");
    }

    @org.junit.Test
    @org.junit.jupiter.api.Test
    public void testKinesisLambdaIntegration() throws Exception {
        AmazonKinesis kinesis = TestUtils.getClientKinesis();
        AWSLambda lambda = TestUtils.getClientLambda();
        String functionName = UUID.randomUUID().toString();
        String streamName = UUID.randomUUID().toString();

        // create function
        CreateFunctionRequest request = new CreateFunctionRequest();
        request.setFunctionName(functionName);
        request.setRuntime(Runtime.Java8);
        request.setCode(LocalTestUtil.createFunctionCode(KinesisLambdaHandler.class));
        request.setHandler(KinesisLambdaHandler.class.getName());
        lambda.createFunction(request);

        // create stream
        kinesis.createStream(streamName, 1);
        Thread.sleep(500);
        String streamArn = kinesis.describeStream(streamName).getStreamDescription().getStreamARN();

        // create mapping
        CreateEventSourceMappingRequest mapping = new CreateEventSourceMappingRequest();
        mapping.setFunctionName(functionName);
        mapping.setEventSourceArn(streamArn);
        mapping.setStartingPosition("LATEST");
        lambda.createEventSourceMapping(mapping);

        // push event
        kinesis.putRecord(streamName, ByteBuffer.wrap("{\"foo\": \"bar\"}".getBytes()), "partitionKey1");
        // TODO: have Lambda store the record to S3, retrieve it from there, compare result
    }

    @org.junit.Test
    @org.junit.jupiter.api.Test
    public void testLocalS3API() throws Exception {
        AmazonS3 s3 = TestUtils.getClientS3();
        List<Bucket> buckets = s3.listBuckets();
        Assertions.assertThat(buckets).isNotNull();

        // run S3 sample
        S3Sample.runTest(TEST_CREDENTIALS);

        // run example with ZIP file upload
        String testBucket = UUID.randomUUID().toString();
        s3.createBucket(testBucket);
        File file = Files.createTempFile("localstack", "s3").toFile();
        file.deleteOnExit();
        ZipOutputStream zipOutputStream = new ZipOutputStream(new FileOutputStream(file));
        zipOutputStream.putNextEntry(new ZipEntry("Some content"));
        zipOutputStream.write("Some text content".getBytes());
        zipOutputStream.closeEntry();
        zipOutputStream.close();
        s3.putObject(testBucket, file.getName(), file);
    }

    @org.junit.Test
    @org.junit.jupiter.api.Test
    public void testLocalLambdaAPI() {
        AWSLambda lambda = TestUtils.getClientLambda();
        ListFunctionsResult functions = lambda.listFunctions();
        Assertions.assertThat(functions.getFunctions()).isNotNull();
    }

    @org.junit.Test
    @org.junit.jupiter.api.Test
    public void testLocalSQSAPI() {
        AmazonSQS sqs = TestUtils.getClientSQS();
        ListQueuesResult queues = sqs.listQueues();
        Assertions.assertThat(queues.getQueueUrls()).isNotNull();

        for (String queueName : Arrays.asList("java_test_queue", "java_test_queue.fifo")) {
            // create queue
            CreateQueueRequest createQueueRequest = new CreateQueueRequest();
            createQueueRequest.setQueueName(queueName);
            CreateQueueResult newQueue = sqs.createQueue(createQueueRequest);
            String queueUrl = newQueue.getQueueUrl();

            // send message
            SendMessageRequest send = new SendMessageRequest(queueUrl, "body");
            SendMessageResult sendResult = sqs.sendMessage(send);
            Assertions.assertThat(sendResult.getMD5OfMessageBody()).isNotNull();

            // receive message
            ReceiveMessageRequest request = new ReceiveMessageRequest(queueUrl);
            request.setWaitTimeSeconds(1);
            request.setMaxNumberOfMessages(1);
            request.setMessageAttributeNames(Arrays.asList("All"));
            request.setAttributeNames(Arrays.asList("All"));
            ReceiveMessageResult result = sqs.receiveMessage(request);
            Assertions.assertThat(result.getMessages()).isNotNull().hasSize(1);

            // delete queue
            DeleteQueueRequest deleteQueue = new DeleteQueueRequest();
            deleteQueue.setQueueUrl(queueUrl);
            sqs.deleteQueue(deleteQueue);
        }
    }

}
