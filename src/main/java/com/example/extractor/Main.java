package com.example.extractor;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileWriter; // Using FileWriter for clarity in the loop
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.util.store.FileDataStoreFactory;
import com.google.api.services.docs.v1.Docs;
import com.google.api.services.docs.v1.DocsScopes;
import com.google.api.services.docs.v1.model.Document;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

public class Main {

    private static final String APPLICATION_NAME = "Google Docs Extractor";
    private static final GsonFactory JSON_FACTORY = GsonFactory.getDefaultInstance();
    private static final String TOKENS_DIRECTORY_PATH = "tokens";

    private static final List<String> SCOPES = Collections.singletonList(DocsScopes.DOCUMENTS_READONLY);
    private static final String CREDENTIALS_FILE_PATH = "/credentials.json";

    // AWS S3 Configuration (loaded from EnvLoader)
    private static final String AWS_ACCESS_KEY = EnvLoader.get("AWS_ACCESS_KEY_ID");
    private static final String AWS_SECRET_KEY = EnvLoader.get("AWS_SECRET_ACCESS_KEY");
    private static final String AWS_REGION = EnvLoader.get("AWS_REGION", "us-east-1");
    private static final String S3_BUCKET_NAME = EnvLoader.get("S3_BUCKET_NAME");

    private static Credential getCredentials() throws Exception {
        InputStream in = Main.class.getResourceAsStream(CREDENTIALS_FILE_PATH);
        if (in == null) {
            throw new FileNotFoundException("Resource not found: " + CREDENTIALS_FILE_PATH);
        }
        GoogleClientSecrets clientSecrets = GoogleClientSecrets.load(JSON_FACTORY, new InputStreamReader(in));

        final NetHttpTransport HTTP_TRANSPORT = GoogleNetHttpTransport.newTrustedTransport();
        GoogleAuthorizationCodeFlow flow = new GoogleAuthorizationCodeFlow.Builder(
                HTTP_TRANSPORT, JSON_FACTORY, clientSecrets, SCOPES)
                .setDataStoreFactory(new FileDataStoreFactory(new File(TOKENS_DIRECTORY_PATH)))
                .setAccessType("offline")
                .build();
        LocalServerReceiver receiver = new LocalServerReceiver.Builder().setPort(8888).build();
        return new AuthorizationCodeInstalledApp(flow, receiver).authorize("user");
    }

    public static void main(String... args) throws Exception {
        // --- 1. PARSE COMMAND-LINE ARGUMENTS ---
        if (args.length == 0) {
            System.err.println("Usage:");
            System.err.println("  java -jar your-app.jar <docId1> <docId2> ...");
            System.err.println("  java -jar your-app.jar --file path/to/ids.txt");
            System.exit(1);
        }

        List<String> docIds = new ArrayList<>();
        if (args[0].equalsIgnoreCase("--file") || args[0].equalsIgnoreCase("-f")) {
            if (args.length < 2) {
                System.err.println("Error: --file flag requires a file path argument.");
                System.exit(1);
            }
            String filePath = args[1];
            System.out.println("Reading document IDs from file: " + filePath);
            try {
                docIds = Files.readAllLines(Paths.get(filePath))
                        .stream()
                        .map(String::trim)
                        .filter(line -> !line.isEmpty() && !line.startsWith("#")) // Also ignore comments
                        .collect(Collectors.toList());
            } catch (IOException e) {
                System.err.println("Error reading file '" + filePath + "': " + e.getMessage());
                System.exit(1);
            }
        } else {
            System.out.println("Reading document IDs from command-line arguments.");
            docIds.addAll(Arrays.asList(args));
        }

        if (docIds.isEmpty()) {
            System.out.println("No document IDs to process.");
            return;
        }

        // --- 2. INITIALIZE SERVICES (ONE TIME) ---
        final NetHttpTransport HTTP_TRANSPORT = GoogleNetHttpTransport.newTrustedTransport();
        Docs docsService = new Docs.Builder(HTTP_TRANSPORT, JSON_FACTORY, getCredentials())
                .setApplicationName(APPLICATION_NAME)
                .build();

        S3Client s3Client = initializeS3Client();
        GoogleDocExtractor extractor = new GoogleDocExtractor(s3Client, S3_BUCKET_NAME);
        
        System.out.printf("\nFound %d document(s) to process.\n", docIds.size());

        // --- 3. PROCESS EACH DOCUMENT IN A LOOP ---
        for (String docId : docIds) {
            System.out.println("=================================================");
            System.out.printf("Processing Document ID: %s\n", docId);
            System.out.println("=================================================");
            
            try {
                System.out.println("Fetching document...");
                Document document = docsService.documents().get(docId).execute();
                System.out.println("Document fetched: " + document.getTitle());

                // a) Extract content to JSON
                String jsonOutput = extractor.extractContentAsJson(document);
                String outputFilename = docId + ".json";
                try (FileWriter fileWriter = new FileWriter(outputFilename)) {
                    fileWriter.write(jsonOutput);
                    System.out.println("✅ Success! Extracted JSON written to " + outputFilename);
                }

                // b) Upload images to S3 (if S3 client is available)
                if (s3Client != null) {
                    System.out.println("Uploading images to S3...");
                    extractor.downloadAndUploadImagesToS3(document);
                }
                
                System.out.println("--- Finished processing " + docId + " ---");

            } catch (Exception e) {
                System.err.println("❌ Error processing document " + docId + ": " + e.getMessage());
                // Continue to the next document
            }
            System.out.println(); // Add a blank line for readability
        }

        // --- 4. CLEAN UP ---
        if (s3Client != null) {
            s3Client.close();
        }
        System.out.println("All documents processed. Application finished.");
    }
    
    /**
     * Initializes the S3 client if credentials are available.
     * @return A configured S3Client, or null if configuration is missing.
     */
    private static S3Client initializeS3Client() {
        if (AWS_ACCESS_KEY != null && AWS_SECRET_KEY != null && S3_BUCKET_NAME != null) {
            try {
                AwsBasicCredentials credentials = AwsBasicCredentials.create(AWS_ACCESS_KEY, AWS_SECRET_KEY);
                StaticCredentialsProvider provider = StaticCredentialsProvider.create(credentials);
                S3Client client = S3Client.builder()
                        .region(Region.of(AWS_REGION))
                        .credentialsProvider(provider)
                        .build();
                System.out.println("S3 client initialized successfully for region " + AWS_REGION);
                return client;
            } catch (Exception e) {
                System.err.println("Failed to initialize S3 client: " + e.getMessage());
                return null;
            }
        } else {
            System.out.println("AWS credentials not found. S3 upload functionality is disabled.");
            return null;
        }
    }
}