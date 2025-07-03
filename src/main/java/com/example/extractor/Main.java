package com.example.extractor;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport; // NEW: Import for file writing errors
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.util.store.FileDataStoreFactory;   // NEW: Modern way to handle files
import com.google.api.services.docs.v1.Docs;   // NEW: Used to specify file paths
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

    // NEW: Define the output filename as a constant for easy access
    private static final String OUTPUT_FILENAME = "document_output.json";

    // AWS S3 Configuration
    private static final String AWS_ACCESS_KEY = EnvLoader.get("AWS_ACCESS_KEY_ID");
    private static final String AWS_SECRET_KEY = EnvLoader.get("AWS_SECRET_ACCESS_KEY");
    private static final String AWS_REGION = EnvLoader.get("AWS_REGION", "us-east-1");
    private static final String S3_BUCKET_NAME = EnvLoader.get("S3_BUCKET_NAME");
    private static S3Client s3Client;

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
        // !!! IMPORTANT: PASTE YOUR DOCUMENT ID HERE !!!
        // final String DOCUMENT_ID = "16tPiar8lKkqW4sFKjS1VVbhYgXpJUCfRncLY8XamRVM";
        final String DOCUMENT_ID = "1OsiBbW0KtwKypXgPc8auxvxgYxpWna4VZKSyWJcFnAY";
        
        if (DOCUMENT_ID.equals("PASTE_YOUR_DOCUMENT_ID_HERE")) {
            System.err.println("!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");
            System.err.println("!!! ERROR: Please open Main.java and set the DOCUMENT_ID !!!");
            System.err.println("!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");
            return;
        }

        final NetHttpTransport HTTP_TRANSPORT = GoogleNetHttpTransport.newTrustedTransport();
        Docs service = new Docs.Builder(HTTP_TRANSPORT, JSON_FACTORY, getCredentials())
                .setApplicationName(APPLICATION_NAME)
                .build();

        System.out.println("Fetching document...");
        Document document = service.documents().get(DOCUMENT_ID).execute();
        System.out.println("Document fetched: " + document.getTitle());

        // Initialize S3 uploader if AWS credentials are available
        if (AWS_ACCESS_KEY != null && AWS_SECRET_KEY != null && S3_BUCKET_NAME != null) {
            try {
                // s3Uploader = new S3Uploader(AWS_ACCESS_KEY, AWS_SECRET_KEY, AWS_REGION, S3_BUCKET_NAME);
                AwsBasicCredentials awsCredentials = AwsBasicCredentials.create(AWS_ACCESS_KEY, AWS_SECRET_KEY);
                StaticCredentialsProvider credentialsProvider = StaticCredentialsProvider.create(awsCredentials);
                s3Client = S3Client.builder()
                                        .region(Region.of(AWS_REGION))
                                        .credentialsProvider(credentialsProvider)
                                        .build();
                System.out.println("S3 uploader initialized successfully");
                
                // Check if credentials came from .env.local file
                if (EnvLoader.has("AWS_ACCESS_KEY_ID")) {
                    System.out.println("AWS credentials loaded from .env.local file");
                } else {
                    System.out.println("AWS credentials loaded from environment variables");
                }
            } catch (Exception e) {
                System.err.println("Failed to initialize S3 uploader: " + e.getMessage());
                System.err.println("Continuing without S3 upload functionality");
            }
        } else {
            System.out.println("AWS credentials not found. S3 upload functionality disabled.");
            System.out.println("Create a .env.local file or set environment variables:");
            System.out.println("  AWS_ACCESS_KEY_ID=your-access-key");
            System.out.println("  AWS_SECRET_ACCESS_KEY=your-secret-key");
            System.out.println("  AWS_REGION=us-east-1  # optional, defaults to us-east-1");
            System.out.println("  S3_BUCKET_NAME=your-bucket-name");
        }

                  
        // GoogleDocExtractor extractor = new GoogleDocExtractor();

         // Use our extractor to process the document.   
        GoogleDocExtractor extractor = new GoogleDocExtractor(s3Client, S3_BUCKET_NAME);
        String jsonOutput = extractor.extractContentAsJson(document);
        extractor.downloadAndUploadImagesToS3(document);

        // --- MODIFIED SECTION: Write JSON to a file ---
        try {
            // This single line writes the entire string to the specified file.
            // It automatically handles opening, writing, and closing the file.
            Files.writeString(Paths.get(OUTPUT_FILENAME), jsonOutput);
            
            System.out.println("----------------------------------------------------------------");
            System.out.println("✅ Success! Extracted JSON has been written to the file: " + OUTPUT_FILENAME);
            System.out.println("   You can find this file in the project's root directory.");
            System.out.println("----------------------------------------------------------------");
        } catch (IOException e) {
            System.err.println("❌ Error: Failed to write JSON to file.");
            e.printStackTrace();
        }
    }
}